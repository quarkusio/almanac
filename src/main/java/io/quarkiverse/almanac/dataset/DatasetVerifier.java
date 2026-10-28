package io.quarkiverse.almanac.dataset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkiverse.almanac.model.ConfigProperty;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.model.ExtensionInfo;
import io.quarkiverse.almanac.util.Json;
import io.quarkiverse.almanac.util.SnapshotStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// `dataset verify`: checks that no write was interrupted ([DatasetFormat#WRITE_MARKER]), that the
/// dataset files are well formed (one JSON object per line, nothing after it, no field twice) and canonical, that
/// every state a history refers to exists (and no other), that `releases.jsonl` agrees with the histories and
/// `config-meta.jsonl`, that every release restores to a consistent snapshot, and that re-encoding the restored
/// contents gives the files byte for byte, twice (round trip and determinism). Optionally compares every restored
/// snapshot with the work snapshot of the same release.
public final class DatasetVerifier {

    /// The result: a summary for the console and the problems found (none when the dataset is sound).
    ///
    /// @param summary  per-file line counts and sizes, counts per kind, comparison counts
    /// @param problems what is wrong, one message each
    public record Report(List<String> summary, List<String> problems) {

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    /// Problems listed per check before the rest are counted only.
    private static final int MAX_LISTED = 20;

    private final Path dir;
    private final List<String> summary = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private final Map<String, Integer> suppressed = new LinkedHashMap<>();
    private final Map<String, String> files = new LinkedHashMap<>();
    /// The `config` summary of each `releases.jsonl` line that has one, in line order.
    private final Map<String, Dataset.ConfigInfo> releaseConfigs = new LinkedHashMap<>();

    private DatasetVerifier(Path dir) {
        this.dir = dir;
    }

    /// Verifies the dataset in a folder.
    ///
    /// @param work a store of work snapshots (without dataset fallback) to compare every restored snapshot with, or
    ///             null to skip that comparison
    public static Report verify(Path dir, SnapshotStore work) throws IOException {
        DatasetVerifier verifier = new DatasetVerifier(dir);
        verifier.run(work);
        verifier.suppressed.forEach((check, count) -> verifier.problems.add(check + ": " + count + " more problems"));
        return new Report(List.copyOf(verifier.summary), List.copyOf(verifier.problems));
    }

    private void run(SnapshotStore work) throws IOException {
        if (!Files.isRegularFile(dir.resolve(DatasetFormat.RELEASES))) {
            problem("files", dir.resolve(DatasetFormat.RELEASES) + " does not exist: no dataset");
            return;
        }
        readFiles();
        if (files.containsKey(DatasetFormat.README) && !files.get(DatasetFormat.README).equals(DatasetFormat.readme())) {
            problem("files", DatasetFormat.README + " differs from the generated one of format " + DatasetFormat.VERSION);
        }
        checkReleaseLines();
        checkHistoryLines(DatasetFormat.BOM, "id");
        checkHistoryLines(DatasetFormat.PLATFORM, "id");
        Set<String> configIds = checkHistoryLines(DatasetFormat.CONFIG, "key");
        Set<String> extensionIds = checkHistoryLines(DatasetFormat.EXTENSIONS, "id");
        checkStateLines(DatasetFormat.CONFIG_STATES, ConfigProperty.class, configIds);
        checkStateLines(DatasetFormat.EXTENSION_STATES, ExtensionInfo.class, extensionIds);
        checkMetaLines();

        Dataset dataset;
        Contents contents;
        try {
            // the files as they are: readFiles reported a write marker already
            dataset = Dataset.openFiles(dir);
            contents = dataset.contents();
        } catch (IOException e) {
            problem("decode", e.getMessage());
            return;
        }
        checkReleases(dataset, contents);
        checkRoundTrip(contents);
        if (work != null) {
            compareWithWork(contents, work);
        }
    }

    private void readFiles() throws IOException {
        for (String name : DatasetFormat.FILES) {
            Path file = dir.resolve(name);
            if (!Files.isRegularFile(file)) {
                problem("files", name + " is missing");
                continue;
            }
            byte[] bytes = Files.readAllBytes(file);
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                problem("files", name + " is not UTF-8");
                continue;
            }
            files.put(name, text);
            if (text.indexOf('\r') >= 0) {
                problem("files", name + " has CRLF line ends (a checkout with core.autocrlf?): the dataset is LF only, "
                        + "see .gitattributes");
            }
            if (!text.isEmpty() && !text.endsWith("\n")) {
                problem("files", name + " does not end with a newline");
            }
            List<String> lines = DatasetFormat.lines(text);
            if (!name.equals(DatasetFormat.README)) {
                long blank = lines.stream().filter(String::isBlank).count();
                if (blank > 0) {
                    problem("files", name + " has " + blank + " blank lines");
                }
            }
            summary.add(String.format(Locale.ROOT, "%-24s %,8d lines %,12d bytes", name, lines.size(), bytes.length));
        }
        Path marker = dir.resolve(DatasetFormat.WRITE_MARKER);
        if (Files.exists(marker)) {
            problem("files", DatasetFormat.interruptedWrite(marker));
        }
        try (Stream<Path> entries = Files.list(dir)) {
            entries.map(entry -> entry.getFileName().toString())
                    .filter(name -> !DatasetFormat.FILES.contains(name) && !name.equals(DatasetFormat.WRITE_MARKER))
                    .sorted()
                    .forEach(name -> summary.add("(not part of the dataset: " + name + ")"));
        }
    }

    /// `releases.jsonl`: versions unique and in release order, dates `yyyy-MM-dd`; records the config summaries for
    /// [#checkMetaLines()].
    private void checkReleaseLines() {
        String previous = null;
        int lineNumber = 0;
        for (String line : DatasetFormat.lines(files.getOrDefault(DatasetFormat.RELEASES, ""))) {
            lineNumber++;
            Optional<JsonNode> row = parse(DatasetFormat.RELEASES, lineNumber, line);
            if (row.isEmpty()) {
                continue;
            }
            String version = row.get().path("version").asText("");
            JsonNode config = row.get().get("config");
            if (config != null && config.isObject()) {
                releaseConfigs.put(version, new Dataset.ConfigInfo(text(config, "source"), text(config, "extractor"),
                        config.path("roots").asInt()));
            }
            String date = row.get().path("date").asText(null);
            if (date != null && !DATE.matcher(date).matches()) {
                problem("releases", DatasetFormat.RELEASES + ":" + lineNumber + ": date " + date + " of " + version
                        + " is not yyyy-MM-dd");
            }
            if (previous != null && ReleaseOrder.compare(previous, version) >= 0) {
                problem("releases", DatasetFormat.RELEASES + ":" + lineNumber + ": " + version + " is not after "
                        + previous + " (lines are in release order, each release once)");
            }
            previous = version;
        }
    }

    /// A history file: ids unique and sorted, change points minimal (the first one not null, each one a change).
    ///
    /// @return the state ids the file refers to
    private Set<String> checkHistoryLines(String file, String idField) {
        Set<String> referenced = new HashSet<>();
        String previous = null;
        int lineNumber = 0;
        for (String line : DatasetFormat.lines(files.getOrDefault(file, ""))) {
            lineNumber++;
            Optional<JsonNode> node = parse(file, lineNumber, line);
            if (node.isEmpty()) {
                continue;
            }
            String id = node.get().path(idField).asText("");
            if (previous != null && previous.compareTo(id) >= 0) {
                problem(file, file + ":" + lineNumber + ": " + id + " is not after " + previous
                        + " (lines sorted by " + idField + ", each once)");
            }
            previous = id;
            JsonNode history = node.get().path("h");
            String value = null;
            for (int p = 0; p < history.size(); p++) {
                JsonNode current = history.get(p).path(1);
                String text = current.isTextual() ? current.textValue() : null;
                if (p == 0 && text == null) {
                    problem(file, file + ":" + lineNumber + ": " + id + " starts with null (nothing is recorded before "
                            + "the first appearance)");
                } else if (p > 0 && Objects.equals(text, value)) {
                    problem(file, file + ":" + lineNumber + ": " + id + " repeats its value at "
                            + history.get(p).path(0).asText() + " (only changes are recorded)");
                }
                value = text;
                if (text != null) {
                    referenced.add(text);
                }
            }
        }
        return referenced;
    }

    /// A state file: ids unique and sorted, each id the hash of its state's canonical JSON, the state JSON canonical,
    /// every referenced state present and every state referenced.
    private <S> void checkStateLines(String file, Class<S> type, Set<String> referenced) {
        Set<String> present = new TreeSet<>();
        String previous = null;
        int lineNumber = 0;
        for (String line : DatasetFormat.lines(files.getOrDefault(file, ""))) {
            lineNumber++;
            Optional<JsonNode> node = parse(file, lineNumber, line);
            if (node.isEmpty()) {
                continue;
            }
            String id = node.get().path("id").asText("");
            JsonNode state = node.get().path("state");
            if (previous != null && previous.compareTo(id) >= 0) {
                problem(file, file + ":" + lineNumber + ": state " + id + " is not after " + previous);
            }
            previous = id;
            present.add(id);
            if (!state.isObject()) {
                problem(file, file + ":" + lineNumber + ": state " + id + " is not an object");
                continue;
            }
            String stored = DatasetFormat.canonicalJson(state);
            if (!DatasetFormat.stateId(stored).equals(id)) {
                problem(file, file + ":" + lineNumber + ": id " + id + " is not the hash of its state ("
                        + DatasetFormat.stateId(stored) + ")");
            }
            try {
                String canonical = DatasetFormat.canonicalJson(Json.mapper().treeToValue(state, type));
                if (!canonical.equals(stored)) {
                    problem(file, file + ":" + lineNumber + ": state " + id + " is not in canonical form ("
                            + JsonDiff.of(Json.readTree(canonical.getBytes(StandardCharsets.UTF_8)), state)
                            .orElse("field order") + ")");
                }
            } catch (IOException e) {
                problem(file, file + ":" + lineNumber + ": state " + id + " is not a " + type.getSimpleName() + ": "
                        + e.getMessage());
            }
        }
        for (String id : new TreeSet<>(referenced)) {
            if (!present.contains(id)) {
                problem(file, "state " + id + " is referenced but missing from " + file);
            }
        }
        for (String id : present) {
            if (!referenced.contains(id)) {
                problem(file, "state " + id + " in " + file + " is never referenced");
            }
        }
    }

    /// The release rules: config only with extensions (extensions need no config), both only with a BOM (a platform
    /// BOM without its BOM does not even decode), every restored snapshot consistent and from a validated extractor.
    private void checkReleases(Dataset dataset, Contents contents) {
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        for (Dataset.Release release : dataset.releases()) {
            String version = release.version();
            for (Kind kind : Kind.values()) {
                if (release.has(kind)) {
                    counts.merge(kind, 1, Integer::sum);
                    Object snapshot = contents.get(version, kind);
                    for (String problem : SnapshotChecks.problems(version, kind, snapshot)) {
                        problem("snapshots", kind + " " + version + " restores inconsistently: " + problem);
                    }
                }
            }
            if (release.has(Kind.CONFIG) && !release.has(Kind.EXTENSIONS)) {
                problem("releases", version + " has config without extensions");
            }
            if ((release.has(Kind.CONFIG) || release.has(Kind.EXTENSIONS)) && !release.bom()) {
                problem("releases", version + " has " + (release.has(Kind.CONFIG) ? "config" : "extensions")
                        + " but no BOM");
            }
            ConfigSnapshot config = contents.configs().get(version);
            if (config != null) {
                SnapshotChecks.unvalidated(config).ifPresent(why -> problem("releases", version + ": " + why));
            }
        }
        summary.add(String.format(Locale.ROOT, "%d known releases: %d with bom, %d with config, %d with extensions, "
                        + "%d with platform", dataset.releases().size(), counts.getOrDefault(Kind.BOM, 0),
                counts.getOrDefault(Kind.CONFIG, 0), counts.getOrDefault(Kind.EXTENSIONS, 0),
                counts.getOrDefault(Kind.PLATFORM, 0)));
    }

    /// `config-meta.jsonl` against `releases.jsonl`, line by line (decoding stops at the first disagreement): one line
    /// per release with config, in the same order, with the same source, extractor and roots.
    private void checkMetaLines() {
        List<String> actual = new ArrayList<>();
        int lineNumber = 0;
        for (String line : DatasetFormat.lines(files.getOrDefault(DatasetFormat.CONFIG_META, ""))) {
            lineNumber++;
            Optional<JsonNode> meta = parse(DatasetFormat.CONFIG_META, lineNumber, line);
            if (meta.isEmpty()) {
                continue;
            }
            String version = meta.get().path("version").asText("");
            actual.add(version);
            Dataset.ConfigInfo info = releaseConfigs.get(version);
            String source = text(meta.get(), "source");
            String extractor = text(meta.get(), "extractor");
            int roots = meta.get().path("roots").asInt();
            if (info != null && !info.equals(new Dataset.ConfigInfo(source, extractor, roots))) {
                problem("releases", version + ": " + DatasetFormat.RELEASES + " says config " + info + ", "
                        + DatasetFormat.CONFIG_META + " says source=" + source + ", extractor=" + extractor + ", roots="
                        + roots);
            }
        }
        List<String> expected = List.copyOf(releaseConfigs.keySet());
        if (!actual.equals(expected)) {
            problem("releases", DatasetFormat.CONFIG_META + " lists " + actual.size() + " releases, "
                    + DatasetFormat.RELEASES + " has config for " + expected.size() + " (one line each, in release "
                    + "order)");
        }
    }

    /// A text field, null when absent or null (as the reader takes it).
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /// Re-encodes the restored contents: the files must come out byte for byte, twice, and decode to the same
    /// contents.
    private void checkRoundTrip(Contents contents) throws IOException {
        Map<String, String> encoded = DatasetEncoder.encode(contents);
        int identical = 0;
        for (Map.Entry<String, String> file : encoded.entrySet()) {
            String actual = files.get(file.getKey());
            if (actual == null) {
                continue;
            }
            if (actual.equals(file.getValue())) {
                identical++;
            } else {
                problem("round trip", file.getKey() + " is not what encoding its restored contents gives: "
                        + firstDifferentLine(actual, file.getValue()));
            }
        }
        boolean deterministic = DatasetEncoder.encode(contents).equals(encoded);
        if (!deterministic) {
            problem("determinism", "encoding the same contents twice gives different bytes");
        }
        boolean restores = Dataset.of(encoded).contents().equals(contents);
        if (!restores) {
            problem("round trip", "the re-encoded files do not restore the same contents");
        }
        summary.add(String.format(Locale.ROOT, "round trip: %d of %d files re-encoded byte for byte from the restored "
                + "releases; second encoding %s; re-encoded files %s", identical, encoded.size(),
                deterministic ? "identical" : "DIFFERENT", restores ? "restore the same contents" : "DO NOT RESTORE"));
    }

    private static String firstDifferentLine(String actual, String expected) {
        List<String> a = DatasetFormat.lines(actual);
        List<String> b = DatasetFormat.lines(expected);
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            String left = i < a.size() ? a.get(i) : "(end of file)";
            String right = i < b.size() ? b.get(i) : "(end of file)";
            if (!left.equals(right)) {
                return "line " + (i + 1) + " is " + abbreviate(left) + ", expected " + abbreviate(right);
            }
        }
        return "line ends differ";
    }

    private static String abbreviate(String line) {
        return line.length() > 160 ? line.substring(0, 160) + "..." : line;
    }

    /// Compares every restored snapshot with the work snapshot of the same release, as records and as JSON.
    private void compareWithWork(Contents contents, SnapshotStore work) throws IOException {
        Map<Kind, int[]> tally = new EnumMap<>(Kind.class);
        for (String version : contents.releases()) {
            for (Kind kind : Kind.values()) {
                Object restored = contents.get(version, kind);
                if (restored == null || !work.inWork(version, kind.snapshotFile())) {
                    continue;
                }
                int[] counts = tally.computeIfAbsent(kind, k -> new int[2]);
                counts[0]++;
                Path file = work.file(version, kind.snapshotFile());
                JsonNode tree = Json.readTree(Files.readAllBytes(file));
                Object record = Json.mapper().treeToValue(tree, kind.recordType());
                Optional<String> difference = JsonDiff.of(Json.mapper().valueToTree(restored), tree);
                if (!restored.equals(record) || difference.isPresent()) {
                    counts[1]++;
                    problem("work", kind + " " + version + " differs from " + file + ": "
                            + difference.orElse("records differ"));
                }
            }
        }
        StringBuilder line = new StringBuilder("compared with ").append(work.root()).append(':');
        for (Kind kind : Kind.values()) {
            int[] counts = tally.getOrDefault(kind, new int[2]);
            line.append(String.format(Locale.ROOT, " %s %d (%d different)", kind, counts[0], counts[1]));
        }
        summary.add(line.toString());
    }

    private Optional<JsonNode> parse(String file, int lineNumber, String line) {
        if (line.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = DatasetFormat.parseLine(line);
            if (node != null && node.isObject()) {
                return Optional.of(node);
            }
            problem(file, file + ":" + lineNumber + ": not a JSON object");
        } catch (JsonProcessingException e) {
            problem(file, file + ":" + lineNumber + ": not JSON: " + e.getOriginalMessage());
        }
        return Optional.empty();
    }

    private void problem(String check, String message) {
        long listed = problems.stream().filter(p -> p.startsWith(check + ": ")).count();
        if (listed < MAX_LISTED) {
            problems.add(check + ": " + message);
        } else {
            suppressed.merge(check, 1, Integer::sum);
        }
    }
}
