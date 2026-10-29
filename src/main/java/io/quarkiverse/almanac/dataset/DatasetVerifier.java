package io.quarkiverse.almanac.dataset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkiverse.almanac.api.ApiGrammar;
import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.model.ApiSnapshot;
import io.quarkiverse.almanac.model.ClassApi;
import io.quarkiverse.almanac.model.ClassesSnapshot;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// `dataset verify`: checks that no write was interrupted ([DatasetFormat#WRITE_MARKER]), that the
/// dataset files are well formed (one JSON object per line, nothing after it, no field twice) and canonical, that
/// every state a history refers to exists (and no other), that `releases.jsonl` agrees with the histories and
/// `config-meta.jsonl`, that the lines of the API files have the id shapes and values of their grammar (and member
/// values only where their class has one), that every release restores to a consistent snapshot (API classes among the
/// release's classes, in the same jar, from one validated reader per major line), and that re-encoding the restored
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
    private static final Pattern ID_PART = Pattern.compile("\\S+");
    /// Problems listed per check before the rest are counted only.
    private static final int MAX_LISTED = 20;

    private final Path dir;
    private final List<String> summary = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private final Map<String, Integer> suppressed = new LinkedHashMap<>();
    private final Map<String, String> files = new LinkedHashMap<>();
    /// The `config` summary of each `releases.jsonl` line that has one, in line order.
    private final Map<String, Dataset.ConfigInfo> releaseConfigs = new LinkedHashMap<>();
    /// The major lines of the `releases.jsonl` lines with classes: the classes files the dataset must have.
    private final Set<Integer> classesLines = new TreeSet<>();
    /// The classes files of the folder, by name (`classes/3.x.jsonl`).
    private final List<String> classesFiles = new ArrayList<>();
    /// The major lines of the `releases.jsonl` lines with API data: the API files the dataset must have.
    private final Set<Integer> apiLines = new TreeSet<>();
    /// The API files of the folder, by name (`api/3.x.jsonl`).
    private final List<String> apiFiles = new ArrayList<>();
    /// The position of each release in `releases.jsonl`, for the order of change points.
    private final Map<String, Integer> releaseIndex = new HashMap<>();

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
        checkClassesFiles();
        checkApiFiles();
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

    /// Reads the files of [DatasetFormat#FILES], the classes files and the API files, in writing order (the classes
    /// and API files before `README.md`), checking each one's encoding and line ends.
    private void readFiles() throws IOException {
        for (String name : DatasetFormat.filesInClassesDir(dir)) {
            if (DatasetFormat.majorOfClassesFile(name) >= 0) {
                classesFiles.add(name);
            } else {
                summary.add("(not part of the dataset: " + name + ")");
            }
        }
        for (String name : DatasetFormat.filesInApiDir(dir)) {
            if (DatasetFormat.majorOfApiFile(name) >= 0) {
                apiFiles.add(name);
            } else {
                summary.add("(not part of the dataset: " + name + ")");
            }
        }
        List<String> names = new ArrayList<>(DatasetFormat.FILES);
        List<String> lineFiles = new ArrayList<>(classesFiles);
        lineFiles.addAll(apiFiles);
        names.addAll(names.indexOf(DatasetFormat.README), lineFiles);
        for (String name : names) {
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
                    .filter(name -> !DatasetFormat.FILES.contains(name) && !name.equals(DatasetFormat.WRITE_MARKER)
                            && !((name.equals(DatasetFormat.CLASSES_DIR) || name.equals(DatasetFormat.API_DIR))
                                    && Files.isDirectory(dir.resolve(name))))
                    .sorted()
                    .forEach(name -> summary.add("(not part of the dataset: " + name + ")"));
        }
    }

    /// The classes files: one for each major line that has classes in `releases.jsonl`, no other, each a history file.
    private void checkClassesFiles() {
        Set<Integer> present = new TreeSet<>();
        for (String name : classesFiles) {
            int major = DatasetFormat.majorOfClassesFile(name);
            present.add(major);
            if (!classesLines.contains(major)) {
                problem("files", name + ": no release of the " + major + ".x line has classes in "
                        + DatasetFormat.RELEASES);
            }
            checkHistoryLines(name, "id");
        }
        for (int major : classesLines) {
            if (!present.contains(major)) {
                problem("files", DatasetFormat.classesFile(major) + " is missing, but " + DatasetFormat.RELEASES
                        + " has classes of the " + major + ".x line");
            }
        }
    }

    /// The API files: one for each major line that has API data in `releases.jsonl`, no other, each a history file
    /// whose lines follow the API grammar.
    private void checkApiFiles() {
        Set<Integer> present = new TreeSet<>();
        for (String name : apiFiles) {
            int major = DatasetFormat.majorOfApiFile(name);
            present.add(major);
            if (!apiLines.contains(major)) {
                problem("files", name + ": no release of the " + major + ".x line has API data in "
                        + DatasetFormat.RELEASES);
            }
            checkHistoryLines(name, "id", new ApiLines(name));
        }
        for (int major : apiLines) {
            if (!present.contains(major)) {
                problem("files", DatasetFormat.apiFile(major) + " is missing, but " + DatasetFormat.RELEASES
                        + " has API data of the " + major + ".x line");
            }
        }
    }

    /// What one line of a history file must also be, beyond its history.
    @FunctionalInterface
    private interface LineCheck {
        void check(int lineNumber, String id, JsonNode history);
    }

    /// The lines of an API file, in order: ids of 2 or 3 parts without whitespace, class and member values in the
    /// grammar, member keys in it, a member line right after its class line (and its other member lines), with values
    /// only where the class line has one.
    private final class ApiLines implements LineCheck {
        private final String file;
        private String classId;
        private JsonNode classHistory;

        ApiLines(String file) {
            this.file = file;
        }

        @Override
        public void check(int lineNumber, String id, JsonNode history) {
            String where = file + ":" + lineNumber + ": ";
            String[] parts = id.split(" ", -1);
            if ((parts.length != 2 && parts.length != 3)
                    || !Arrays.stream(parts).allMatch(part -> ID_PART.matcher(part).matches())) {
                problem(file, where + id + " is neither <artifactId> <class> nor <artifactId> <class> <member>");
                return;
            }
            if (parts.length == 2) {
                classId = id;
                classHistory = history;
                for (JsonNode point : history) {
                    if (point.path(1).isTextual()) {
                        ApiGrammar.classValueProblem(point.path(1).textValue())
                                .ifPresent(problem -> problem(file, where + id + ": " + problem));
                    }
                }
                return;
            }
            if (!(parts[0] + " " + parts[1]).equals(classId)) {
                problem(file, where + "member line " + id + " does not follow the line of its class");
                return;
            }
            ApiGrammar.memberKeyProblem(parts[2]).ifPresent(problem -> problem(file, where + problem));
            for (JsonNode point : history) {
                if (point.path(1).isTextual()) {
                    ApiGrammar.memberValueProblem(parts[2], point.path(1).textValue())
                            .ifPresent(problem -> problem(file, where + id + ": " + problem));
                }
            }
            String without = memberWithoutClass(history);
            if (without != null) {
                problem(file, where + id + " has a value at " + without + ", where its class line has none");
            }
        }

        /// The first release where the member line has a value and its class line none, null when there is none.
        private String memberWithoutClass(JsonNode history) {
            TreeMap<Integer, String> boundaries = new TreeMap<>();
            for (JsonNode lines : List.of(history, classHistory)) {
                for (JsonNode point : lines) {
                    String version = point.path(0).asText();
                    Integer index = releaseIndex.get(version);
                    if (index != null) {
                        boundaries.put(index, version);
                    }
                }
            }
            for (Map.Entry<Integer, String> boundary : boundaries.entrySet()) {
                if (valueAt(history, boundary.getKey()) != null && valueAt(classHistory, boundary.getKey()) == null) {
                    return boundary.getValue();
                }
            }
            return null;
        }

        /// The value of a history at a release (by its position in `releases.jsonl`), null when absent.
        private String valueAt(JsonNode history, int index) {
            String value = null;
            for (JsonNode point : history) {
                Integer at = releaseIndex.get(point.path(0).asText());
                if (at == null || at > index) {
                    break;
                }
                value = point.path(1).isTextual() ? point.path(1).textValue() : null;
            }
            return value;
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
            releaseIndex.putIfAbsent(version, releaseIndex.size());
            if (row.get().path("api").isObject()) {
                try {
                    apiLines.add(DatasetFormat.majorOf(version));
                } catch (IllegalArgumentException e) {
                    problem("releases", DatasetFormat.RELEASES + ":" + lineNumber + ": " + e.getMessage());
                }
            }
            if (row.get().path("classes").asBoolean(false)) {
                try {
                    classesLines.add(DatasetFormat.majorOf(version));
                } catch (IllegalArgumentException e) {
                    problem("releases", DatasetFormat.RELEASES + ":" + lineNumber + ": " + e.getMessage());
                }
            }
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
        return checkHistoryLines(file, idField, null);
    }

    /// A history file, each line also checked by `lineCheck` unless it is null.
    ///
    /// @return the state ids the file refers to
    private Set<String> checkHistoryLines(String file, String idField, LineCheck lineCheck) {
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
            if (lineCheck != null) {
                lineCheck.check(lineNumber, id, history);
            }
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

    /// The release rules: config only with extensions (extensions need no config), config, extensions and classes
    /// only with a BOM (a platform BOM without its BOM does not even decode), the API only with the BOM and the
    /// classes, its classes among those of the release in the same jar and one validated reader per major line, every
    /// restored snapshot consistent and from a validated extractor.
    private void checkReleases(Dataset dataset, Contents contents) {
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        // an API class restored for several releases is one record: its content is checked once
        Set<ClassApi> checked = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Integer, Set<String>> apiReaders = new TreeMap<>();
        for (Dataset.Release release : dataset.releases()) {
            String version = release.version();
            for (Kind kind : Kind.values()) {
                if (release.has(kind)) {
                    counts.merge(kind, 1, Integer::sum);
                    Object snapshot = contents.get(version, kind);
                    for (String problem : SnapshotChecks.problems(version, kind, snapshot, checked)) {
                        problem("snapshots", kind + " " + version + " restores inconsistently: " + problem);
                    }
                }
            }
            if (release.has(Kind.CONFIG) && !release.has(Kind.EXTENSIONS)
                    && QuarkusVersion.parse(version).hasExtensionCatalog()) {
                problem("releases", version + " has config without extensions");
            }
            if ((release.has(Kind.CONFIG) || release.has(Kind.EXTENSIONS)) && !release.bom()) {
                problem("releases", version + " has " + (release.has(Kind.CONFIG) ? "config" : "extensions")
                        + " but no BOM");
            }
            if (release.has(Kind.CLASSES) && !release.bom()) {
                problem("releases", version + " has classes but no BOM");
            }
            if (release.has(Kind.API)) {
                checkApiRelease(release, contents, apiReaders);
            }
            ConfigSnapshot config = contents.configs().get(version);
            if (config != null) {
                SnapshotChecks.unvalidated(config).ifPresent(why -> problem("releases", version + ": " + why));
            }
        }
        apiReaders.forEach((major, readers) -> {
            if (readers.size() > 1) {
                problem("releases", "the " + major + ".x line has API data of the readers " + String.join(" and ",
                        readers) + " (one reader per line)");
            }
        });
        summary.add(String.format(Locale.ROOT, "%d known releases: %d with bom, %d with config, %d with extensions, "
                + "%d with classes, %d with platform, %d with api", dataset.releases().size(),
                counts.getOrDefault(Kind.BOM, 0), counts.getOrDefault(Kind.CONFIG, 0),
                counts.getOrDefault(Kind.EXTENSIONS, 0), counts.getOrDefault(Kind.CLASSES, 0),
                counts.getOrDefault(Kind.PLATFORM, 0), counts.getOrDefault(Kind.API, 0)));
    }

    /// A release with API data: it has the BOM and the classes, every API class among the classes in the same jar, and
    /// a validated reader, recorded per major line.
    private void checkApiRelease(Dataset.Release release, Contents contents, Map<Integer, Set<String>> apiReaders) {
        String version = release.version();
        if (!release.bom()) {
            problem("releases", version + " has api but no BOM");
        }
        if (!release.classes()) {
            problem("releases", version + " has api but no classes");
        }
        String reader = release.api().reader();
        if (reader == null || !DatasetFormat.API_READERS.contains(reader)) {
            problem("releases", version + ": API reader " + reader + " is not validated");
        }
        try {
            apiReaders.computeIfAbsent(DatasetFormat.majorOf(version), major -> new TreeSet<>())
                    .add(String.valueOf(reader));
        } catch (IllegalArgumentException e) {
            problem("releases", e.getMessage());
        }
        ApiSnapshot api = contents.apis().get(version);
        ClassesSnapshot classes = contents.classes().get(version);
        if (api != null && classes != null) {
            SnapshotChecks.apiOutsideClasses(api, classes).ifPresent(problem -> problem("releases", "api " + version
                    + ": " + problem));
        }
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
