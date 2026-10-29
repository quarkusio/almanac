package io.quarkiverse.almanac.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkiverse.almanac.model.ApiSnapshot;
import io.quarkiverse.almanac.model.ArtifactApi;
import io.quarkiverse.almanac.model.ArtifactClasses;
import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ClassApi;
import io.quarkiverse.almanac.model.ClassesSnapshot;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.model.MemberApi;
import io.quarkiverse.almanac.util.AtomicFiles;
import io.quarkiverse.almanac.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/// Changes the committed dataset: imports work snapshots, removes data, rebuilds the release list,
/// then [writes][#write()] the files again. Every file is re-encoded from the full contents, so the result never
/// depends on the order of the changes, and a file is rewritten only when its bytes change.
///
/// Before writing, the encoded files are decoded again and must restore exactly the contents (every release, date and
/// snapshot), and re-encoding them must give the same bytes; otherwise nothing is written.
public final class DatasetEditor {

    /// A work snapshot to import.
    ///
    /// @param version the release (the name of its work snapshot folder)
    /// @param kind    which snapshot
    /// @param file    the snapshot file, e.g. `work/snapshots/3.40.1/bom.json`
    public record Candidate(String version, Kind kind, Path file) {
    }

    /// A snapshot that was not imported.
    ///
    /// @param version     the release
    /// @param kind        which snapshot
    /// @param reason      why
    /// @param unvalidated true when it was refused only because its config extractor or API reader is not validated
    ///                    (or its config snapshot was), false when the snapshot is inconsistent or conflicts with the
    ///                    dataset
    public record Refusal(String version, Kind kind, String reason, boolean unvalidated) {

        @Override
        public String toString() {
            return kind + " " + version + ": " + reason;
        }
    }

    /// The outcome of [#importSnapshots]: `kind version` entries.
    ///
    /// @param added     snapshots the dataset did not have
    /// @param replaced  snapshots that replaced different ones (only with `replace`)
    /// @param unchanged snapshots equal to the dataset's
    /// @param refusals  snapshots that were not imported
    /// @param warnings  `kind version: what` of imported snapshots that keep something as written: the managed
    ///                  dependencies of a BOM or platform BOM with a `${...}` reference
    /// @param removed   snapshots the import removed, since what they were read from is replaced: the API of a release
    ///                  whose classes the import replaces without bringing its API (or with an API it refuses)
    public record ImportReport(List<String> added, List<String> replaced, List<String> unchanged,
            List<Refusal> refusals, List<String> warnings, List<String> removed) {

        /// Whether any snapshot was refused for a reason other than an unvalidated extractor.
        public boolean hasInconsistencies() {
            return refusals.stream().anyMatch(refusal -> !refusal.unvalidated());
        }
    }

    /// The outcome of [#refreshReleases].
    ///
    /// @param known   releases known afterwards
    /// @param added   releases that were not known before
    /// @param dropped releases without data that the release list no longer has
    /// @param dated   releases without a BOM that got a date from `dateOf`
    public record RefreshReport(int known, List<String> added, List<String> dropped, int dated) {
    }

    private final Path dir;
    private final Contents contents;
    private final Map<String, String> onDisk;

    private DatasetEditor(Path dir, Contents contents, Map<String, String> onDisk) {
        this.dir = dir;
        this.contents = contents;
        this.onDisk = onDisk;
    }

    /// Reads the dataset in a folder (an empty one when the folder or its `releases.jsonl` does not exist).
    public static DatasetEditor open(Path dir) throws IOException {
        Contents contents = Dataset.open(dir).contents();
        Map<String, String> onDisk = new HashMap<>();
        for (String name : datasetFiles(dir)) {
            onDisk.put(name, Files.readString(dir.resolve(name), StandardCharsets.UTF_8));
        }
        return new DatasetEditor(dir, contents, onDisk);
    }

    /// The dataset files in a folder: those of [DatasetFormat#FILES] that exist, its classes files and its API files.
    private static List<String> datasetFiles(Path dir) throws IOException {
        List<String> names = new ArrayList<>();
        for (String name : DatasetFormat.FILES) {
            if (Files.isRegularFile(dir.resolve(name))) {
                names.add(name);
            }
        }
        for (String name : DatasetFormat.filesInClassesDir(dir)) {
            if (DatasetFormat.majorOfClassesFile(name) >= 0) {
                names.add(name);
            }
        }
        for (String name : DatasetFormat.filesInApiDir(dir)) {
            if (DatasetFormat.majorOfApiFile(name) >= 0) {
                names.add(name);
            }
        }
        return names;
    }

    /// The dataset folder.
    public Path dir() {
        return dir;
    }

    /// Whether the dataset (as edited so far) holds the release's snapshot of a kind.
    public boolean has(String version, Kind kind) {
        return contents.has(version, kind);
    }

    /// Imports work snapshots. A snapshot is refused when its file cannot be read losslessly into its record, when it
    /// breaks the snapshot invariants, when its config extractor or API reader is not validated
    /// ([DatasetFormat#UNVALIDATED_EXTRACTORS], [DatasetFormat#API_READERS]), when it differs from the dataset's
    /// snapshot of the same release and kind and `replace` is false, or when it would leave a release from 1.0 on
    /// (with an extension catalog) with config but no extensions, with config, extensions, classes, a platform BOM or
    /// API but no BOM, with API but no classes, with API classes its classes do not list in the same jar, or a major
    /// line with the API of two readers. Extensions need no config, classes need neither. The others are imported;
    /// refusals never stop them. A BOM whose managed dependencies keep a `${...}` reference is imported as it is, with
    /// a warning. Replacing a release's classes removes its API, which was read from the replaced jars, unless the
    /// import brings an API of the release that it takes (`api --missing` lists it). `api` extracts it again from the
    /// new `classes.json` of the work folder, since it reads a release again whose `api.json` there is older; when
    /// that `api.json` is newer (classes that came from elsewhere), `api --force --versions <release>` does.
    public ImportReport importSnapshots(List<Candidate> candidates, boolean replace) {
        List<Refusal> refusals = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        SortedMap<String, Map<Kind, Object>> accepted = new TreeMap<>(ReleaseOrder.COMPARATOR);
        Map<String, Refusal> refusedExtensions = new HashMap<>();
        // the class names of one release are mostly those of the others: one copy of each for all of them
        Map<String, String> classNames = new HashMap<>();
        // so is the API: one record of each class and member state for all of them
        ApiRecords apiRecords = new ApiRecords();
        for (Candidate candidate : candidates) {
            String version = candidate.version();
            Kind kind = candidate.kind();
            Object snapshot;
            try {
                snapshot = read(candidate);
                if (snapshot instanceof ClassesSnapshot classes) {
                    snapshot = shared(classes, classNames);
                } else if (snapshot instanceof ApiSnapshot api) {
                    snapshot = apiRecords.shared(api);
                }
            } catch (IOException e) {
                refusals.add(new Refusal(version, kind, e.getMessage(), false));
                continue;
            }
            List<String> problems = SnapshotChecks.problems(version, kind, snapshot);
            Optional<String> unvalidated = switch (kind) {
                case CONFIG -> SnapshotChecks.unvalidated((ConfigSnapshot) snapshot);
                case API -> SnapshotChecks.unvalidated((ApiSnapshot) snapshot);
                default -> Optional.empty();
            };
            Object existing = contents.get(version, kind);
            Refusal refusal = null;
            if (!problems.isEmpty()) {
                refusal = new Refusal(version, kind, "inconsistent: " + String.join("; ", problems), false);
            } else if (unvalidated.isPresent()) {
                refusal = new Refusal(version, kind, unvalidated.get(), true);
            } else if (existing != null && !existing.equals(snapshot) && !replace) {
                refusal = new Refusal(version, kind, "differs from the dataset's snapshot ("
                        + JsonDiff.of(existing, snapshot).orElse("?") + "); --force replaces it", false);
            }
            if (refusal != null) {
                refusals.add(refusal);
                if (kind == Kind.EXTENSIONS) {
                    refusedExtensions.putIfAbsent(version, refusal);
                }
            } else {
                accepted.computeIfAbsent(version, v -> new EnumMap<>(Kind.class)).put(kind, snapshot);
                if (kind == Kind.BOM || kind == Kind.PLATFORM) {
                    SnapshotChecks.references((BomSnapshot) snapshot)
                            .ifPresent(references -> warnings.add(kind + " " + version + ": " + references));
                }
            }
        }
        requirePrerequisites(accepted, refusedExtensions, refusals);

        List<String> added = new ArrayList<>();
        List<String> replaced = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        accepted.forEach((version, kinds) -> kinds.forEach((kind, snapshot) -> {
            Object existing = contents.get(version, kind);
            String entry = kind + " " + version;
            if (existing == null) {
                added.add(entry);
            } else if (existing.equals(snapshot)) {
                unchanged.add(entry);
                return;
            } else {
                replaced.add(entry);
            }
            contents.put(version, kind, snapshot);
        }));
        // the API of the replaced classes was read from other jars than the new classes list
        List<String> removed = new ArrayList<>();
        accepted.forEach((version, kinds) -> {
            if (replaced.contains(Kind.CLASSES + " " + version) && !kinds.containsKey(Kind.API)
                    && contents.remove(version, Kind.API)) {
                removed.add(Kind.API + " " + version);
            }
        });
        return new ImportReport(List.copyOf(added), List.copyOf(replaced), List.copyOf(unchanged),
                List.copyOf(refusals), List.copyOf(warnings), List.copyOf(removed));
    }

    /// Config needs the release's extensions, and config, extensions and classes need its BOM (extensions need no
    /// config), as does the platform BOM, which the dataset keeps as differences from it, and the API, which also needs
    /// the classes, listing each of its classes in the same jar: drops accepted config, extension, classes, platform
    /// BOM or API snapshots whose release would lack what they need, recording why. The API snapshots of a major line
    /// must all come from one reader, or a change of reader would show as changes of the API.
    private void requirePrerequisites(SortedMap<String, Map<Kind, Object>> accepted,
            Map<String, Refusal> refusedExtensions, List<Refusal> refusals) {
        accepted.forEach((version, kinds) -> {
            if (!kinds.containsKey(Kind.BOM) && !contents.has(version, Kind.BOM)) {
                for (Kind kind : List.of(Kind.CONFIG, Kind.EXTENSIONS, Kind.CLASSES, Kind.PLATFORM, Kind.API)) {
                    if (kinds.remove(kind) != null) {
                        refusals.add(new Refusal(version, kind, switch (kind) {
                            case PLATFORM -> "the platform BOM, kept as differences from the release's BOM, needs the "
                                    + "BOM snapshot, which is neither in the dataset nor in this import";
                            case API -> "the API needs the release's BOM and classes snapshots; its BOM snapshot is "
                                    + "neither in the dataset nor in this import";
                            default -> "config, extensions and classes need the release's BOM snapshot, which is "
                                    + "neither in the dataset nor in this import";
                        }, false));
                    }
                }
                return;
            }
            if (kinds.containsKey(Kind.API)) {
                ClassesSnapshot classes = (ClassesSnapshot) (kinds.containsKey(Kind.CLASSES) ? kinds.get(Kind.CLASSES)
                        : contents.get(version, Kind.CLASSES));
                Optional<String> outside = classes == null ? Optional.empty()
                        : SnapshotChecks.apiOutsideClasses((ApiSnapshot) kinds.get(Kind.API), classes);
                if (classes == null) {
                    kinds.remove(Kind.API);
                    refusals.add(new Refusal(version, Kind.API, "the API needs the release's classes snapshot, "
                            + "which is neither in the dataset nor in this import", false));
                } else if (outside.isPresent()) {
                    kinds.remove(Kind.API);
                    refusals.add(new Refusal(version, Kind.API, "inconsistent: " + outside.get(), false));
                }
            }
            if (kinds.containsKey(Kind.CONFIG) && !kinds.containsKey(Kind.EXTENSIONS)
                    && !contents.has(version, Kind.EXTENSIONS) && QuarkusVersion.parse(version).hasExtensionCatalog()) {
                Refusal partner = refusedExtensions.get(version);
                kinds.remove(Kind.CONFIG);
                refusals.add(partner != null
                        ? new Refusal(version, Kind.CONFIG, "its extensions snapshot is refused (" + partner.reason()
                                + ")", partner.unvalidated())
                        : new Refusal(version, Kind.CONFIG, "config needs the release's extensions snapshot, which is "
                                + "neither in the dataset nor in this import", false));
            }
        });
        requireOneApiReaderPerLine(accepted, refusals);
        accepted.values().removeIf(Map::isEmpty);
    }

    /// Drops the accepted API snapshots of a major line whose releases would have API data of more than one reader,
    /// once the import is done, recording why.
    private void requireOneApiReaderPerLine(SortedMap<String, Map<Kind, Object>> accepted, List<Refusal> refusals) {
        Map<Integer, Map<String, String>> readers = new TreeMap<>();
        contents.apis().forEach((version, api) -> readers.computeIfAbsent(DatasetFormat.majorOf(version),
                major -> new TreeMap<>(ReleaseOrder.COMPARATOR)).put(version, String.valueOf(api.reader())));
        accepted.forEach((version, kinds) -> {
            if (kinds.get(Kind.API) instanceof ApiSnapshot api) {
                readers.computeIfAbsent(DatasetFormat.majorOf(version), major -> new TreeMap<>(ReleaseOrder.COMPARATOR))
                        .put(version, String.valueOf(api.reader()));
            }
        });
        accepted.forEach((version, kinds) -> {
            if (kinds.containsKey(Kind.API)) {
                int major = DatasetFormat.majorOf(version);
                Set<String> lineReaders = new TreeSet<>(readers.get(major).values());
                if (lineReaders.size() > 1) {
                    kinds.remove(Kind.API);
                    refusals.add(new Refusal(version, Kind.API, "the " + major + ".x line would have API data of the "
                            + "readers " + String.join(" and ", lineReaders) + "; a change of reader means extracting "
                            + "the whole line again", false));
                }
            }
        });
    }

    /// Removes snapshots; the releases stay known with their dates. Removing a BOM removes the release's config,
    /// extensions, classes, platform BOM and API too, removing extensions removes the config, and removing the classes
    /// the API, which need them; config, the platform BOM and the API go alone. Returns the removed `kind version`
    /// entries.
    public List<String> remove(Collection<String> versions, Set<Kind> kinds) {
        // a BOM takes config, extensions, classes, the platform BOM and the API along (they need it), extensions take
        // the config and classes the API (they need them)
        Set<Kind> effective = EnumSet.copyOf(kinds);
        if (effective.contains(Kind.BOM)) {
            effective.add(Kind.EXTENSIONS);
            effective.add(Kind.CLASSES);
            effective.add(Kind.PLATFORM);
        }
        if (effective.contains(Kind.EXTENSIONS)) {
            effective.add(Kind.CONFIG);
        }
        if (effective.contains(Kind.CLASSES)) {
            effective.add(Kind.API);
        }
        List<String> removed = new ArrayList<>();
        for (String version : ReleaseOrder.sorted(versions)) {
            for (Kind kind : effective) {
                if (contents.remove(version, kind)) {
                    removed.add(kind + " " + version);
                }
            }
        }
        return removed;
    }

    /// Rebuilds the list of known releases: the published versions (from `maven-metadata.xml`) plus every release
    /// that holds data; known releases without data that are no longer published are dropped. A release without a
    /// BOM keeps its recorded date, or gets `dateOf(version)` when that is not null.
    public RefreshReport refreshReleases(Collection<String> published, Function<String, String> dateOf) {
        SortedSet<String> keep = new TreeSet<>(ReleaseOrder.COMPARATOR);
        published.stream().map(String::strip).filter(version -> !version.isEmpty()).forEach(keep::add);
        SortedSet<String> before = contents.releases();
        for (String version : before) {
            if (contents.holdsData(version)) {
                keep.add(version);
            }
        }
        List<String> dropped = new ArrayList<>();
        for (String version : before) {
            if (!keep.contains(version)) {
                contents.forgetRelease(version);
                dropped.add(version);
            }
        }
        List<String> added = new ArrayList<>();
        int dated = 0;
        for (String version : keep) {
            if (!before.contains(version)) {
                added.add(version);
            }
            contents.addRelease(version, null);
            if (!contents.has(version, Kind.BOM) && contents.dates().get(version) == null) {
                String date = dateOf.apply(version);
                if (date != null) {
                    contents.addRelease(version, date);
                    dated++;
                }
            }
        }
        return new RefreshReport(keep.size(), List.copyOf(added), List.copyOf(dropped), dated);
    }

    /// Encodes the dataset, checks that it restores exactly, and writes the files whose bytes changed. The classes or
    /// API file of a major line that no longer has classes or API is deleted (with its folder when it ends up empty).
    ///
    /// The files are replaced one after the other, so [DatasetFormat#WRITE_MARKER] exists from before the first until
    /// after the last: a write that does not finish leaves it behind, and readers then refuse the folder instead of
    /// restoring files that do not belong together. The marker is created only when it does not exist, which also
    /// makes it a lock: an existing marker (another write, or an interrupted one) stops this write before any file
    /// changes. Holding it, the write then checks that no file changed since [#open] read them (another command that
    /// wrote in between would otherwise lose its changes to this one) and writes nothing if one did.
    ///
    /// @return the names of the files written, in writing order (see [DatasetEncoder#encode]), then those deleted
    public List<String> write() throws IOException {
        Map<String, String> files = DatasetEncoder.encode(contents);
        checkRoundTrip(files);
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            if (!file.getValue().equals(onDisk.get(file.getKey()))) {
                changed.add(file.getKey());
            }
        }
        List<String> stale = new ArrayList<>(new TreeSet<>(onDisk.keySet()));
        stale.removeAll(files.keySet());
        changed.addAll(stale);
        if (changed.isEmpty()) {
            return changed;
        }
        Path marker = dir.resolve(DatasetFormat.WRITE_MARKER);
        Files.createDirectories(dir);
        try {
            Files.writeString(marker, DatasetFormat.writeMarker(changed), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException e) {
            throw new IOException("nothing written: " + DatasetFormat.interruptedWrite(marker), e);
        }
        try {
            checkUnchangedOnDisk();
        } catch (IOException e) {
            Files.delete(marker);
            throw e;
        }
        for (String name : changed) {
            if (files.containsKey(name)) {
                AtomicFiles.writeString(dir.resolve(name), files.get(name));
                onDisk.put(name, files.get(name));
            } else {
                Files.deleteIfExists(dir.resolve(name));
                onDisk.remove(name);
            }
        }
        Path classesDir = dir.resolve(DatasetFormat.CLASSES_DIR);
        if (!stale.isEmpty() && Files.isDirectory(classesDir) && DatasetFormat.filesInClassesDir(dir).isEmpty()) {
            Files.delete(classesDir);
        }
        Path apiDir = dir.resolve(DatasetFormat.API_DIR);
        if (!stale.isEmpty() && Files.isDirectory(apiDir) && DatasetFormat.filesInApiDir(dir).isEmpty()) {
            Files.delete(apiDir);
        }
        Files.delete(marker);
        return changed;
    }

    /// Fails when a dataset file is not what [#open] read (or last wrote): another command wrote the dataset since.
    private void checkUnchangedOnDisk() throws IOException {
        Set<String> names = new TreeSet<>(DatasetFormat.FILES);
        names.addAll(onDisk.keySet());
        names.addAll(datasetFiles(dir));
        for (String name : names) {
            Path file = dir.resolve(name);
            String now = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
            if (!Objects.equals(now, onDisk.get(name))) {
                throw new IOException("nothing written: " + file + " changed after this command read the dataset "
                        + "(another command wrote it meanwhile); run this command again");
            }
        }
    }

    /// The files [#write()] would produce (file name → content), without writing them.
    public Map<String, String> encode() {
        return DatasetEncoder.encode(contents);
    }

    /// Decodes the encoded files and compares the result with the contents; then re-encodes it.
    private void checkRoundTrip(Map<String, String> files) throws IOException {
        Contents decoded = Dataset.of(files).contents();
        if (!decoded.equals(contents)) {
            throw new IOException("internal error, nothing written: the encoded dataset does not restore its "
                    + "contents (" + difference(decoded) + ")");
        }
        if (!DatasetEncoder.encode(decoded).equals(files)) {
            throw new IOException("internal error, nothing written: re-encoding the decoded dataset gives other bytes");
        }
    }

    private String difference(Contents decoded) {
        if (!decoded.releases().equals(contents.releases())) {
            return "releases differ";
        }
        for (String version : contents.releases()) {
            if (!Objects.equals(decoded.date(version), contents.date(version))) {
                return "date of " + version;
            }
            for (Kind kind : Kind.values()) {
                Object expected = contents.get(version, kind);
                Object actual = decoded.get(version, kind);
                if (!Objects.equals(expected, actual)) {
                    return kind + " " + version + ": " + (expected == null || actual == null ? "presence"
                            : JsonDiff.of(expected, actual).orElse("records differ, JSON equal"));
                }
            }
        }
        return "?";
    }

    /// The snapshot with each name taken from `names` (added there when new), so that equal names are one string.
    private static ClassesSnapshot shared(ClassesSnapshot snapshot, Map<String, String> names) {
        List<ArtifactClasses> artifacts = new ArrayList<>(snapshot.artifacts().size());
        for (ArtifactClasses artifact : snapshot.artifacts()) {
            List<String> classes = new ArrayList<>(artifact.classes().size());
            for (String name : artifact.classes()) {
                classes.add(names.computeIfAbsent(name, n -> n));
            }
            artifacts.add(new ArtifactClasses(names.computeIfAbsent(artifact.id(), a -> a), List.copyOf(classes)));
        }
        return new ClassesSnapshot(snapshot.version(), List.copyOf(artifacts));
    }

    /// One record for each distinct class and member state of the API snapshots of an import, and one string for each
    /// name: the API of a release is mostly that of the others, and an import of every release would otherwise hold
    /// millions of equal member records.
    private static final class ApiRecords {
        private final Map<String, String> strings = new HashMap<>();
        private final Map<MemberApi, MemberApi> members = new HashMap<>();
        private final Map<ClassApi, ClassApi> classes = new HashMap<>();

        /// The snapshot with shared records; as it is when it lacks a list or a value (the checks then name it).
        ApiSnapshot shared(ApiSnapshot snapshot) {
            if (snapshot.artifacts() == null) {
                return snapshot;
            }
            List<ArtifactApi> artifacts = new ArrayList<>(snapshot.artifacts().size());
            for (ArtifactApi artifact : snapshot.artifacts()) {
                if (artifact == null || artifact.id() == null || artifact.classes() == null) {
                    return snapshot;
                }
                List<ClassApi> types = new ArrayList<>(artifact.classes().size());
                for (ClassApi type : artifact.classes()) {
                    if (type == null || type.name() == null || type.v() == null || type.members() == null) {
                        return snapshot;
                    }
                    List<MemberApi> shared = new ArrayList<>(type.members().size());
                    for (MemberApi member : type.members()) {
                        if (member == null || member.id() == null || member.v() == null) {
                            return snapshot;
                        }
                        shared.add(members.computeIfAbsent(member, m -> new MemberApi(string(m.id()), string(m.v()))));
                    }
                    ClassApi candidate = new ClassApi(string(type.name()), string(type.v()), List.copyOf(shared));
                    types.add(classes.computeIfAbsent(candidate, c -> c));
                }
                artifacts.add(new ArtifactApi(string(artifact.id()), List.copyOf(types)));
            }
            return new ApiSnapshot(snapshot.version(), snapshot.reader(), List.copyOf(artifacts));
        }

        private String string(String text) {
            return strings.computeIfAbsent(text, t -> t);
        }
    }

    /// Reads a work snapshot into its record; fails when the record does not keep everything the file holds.
    private static Object read(Candidate candidate) throws IOException {
        if (!Files.isRegularFile(candidate.file())) {
            throw new IOException("no work snapshot " + candidate.file());
        }
        byte[] bytes = Files.readAllBytes(candidate.file());
        JsonNode tree = Json.readTree(bytes);
        if (tree == null || !tree.isObject()) {
            throw new IOException(candidate.file() + " is not a JSON object");
        }
        Object snapshot = Json.mapper().treeToValue(tree, candidate.kind().recordType());
        JsonNode back = Json.mapper().valueToTree(snapshot);
        if (!tree.equals(back)) {
            throw new IOException("inconsistent: " + candidate.file() + " holds data its "
                    + candidate.kind().recordType().getSimpleName() + " record does not keep ("
                    + JsonDiff.of(tree, back).orElse("?") + ")");
        }
        return snapshot;
    }
}
