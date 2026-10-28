package io.quarkiverse.almanac.dataset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkiverse.almanac.model.ArtifactClasses;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ClassesSnapshot;
import io.quarkiverse.almanac.model.ConfigProperty;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.model.ExtensionInfo;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/// Read access to the committed dataset: the known releases, and the BOM, config, extension and classes
/// snapshot of any release that holds them, restored exactly from the change-point histories.
///
/// `releases.jsonl` is read when the dataset is opened; the other files only when a snapshot of their kind is first
/// restored. Decoded files stay in memory, so restoring a snapshot afterwards costs one binary search per id. Instances
/// are safe for concurrent use.
///
/// Reading fails rather than restore data the files do not hold consistently: every line must be one JSON object
/// (nothing after it, no field twice), change points must name releases of their kind in release order, every state
/// must hash to its id and be referenced by its history file (which may refer to no other), the `config-meta.jsonl`
/// line of a release must agree with its `releases.jsonl` line, and a class line must name an artifact and a class
/// with the value [DatasetFormat#CLASS_PRESENT]. A folder that has [DatasetFormat#WRITE_MARKER] is refused: a write
/// was interrupted there. `dataset verify` checks the rest (canonical form, order, encoding).
public final class Dataset {

    /// One line of `releases.jsonl`.
    ///
    /// @param version    the release
    /// @param date       UTC day of the BOM's `Last-Modified`, null when unknown
    /// @param bom        whether the dataset holds the release's BOM snapshot
    /// @param config     what `releases.jsonl` says about the config snapshot, null when the dataset has none
    /// @param extensions whether the dataset holds the release's extensions snapshot
    /// @param classes    whether the dataset holds the release's classes snapshot
    public record Release(String version, String date, boolean bom, ConfigInfo config, boolean extensions,
            boolean classes) {

        /// Whether the dataset holds this release's snapshot of the given kind.
        public boolean has(Kind kind) {
            return switch (kind) {
                case BOM -> bom;
                case CONFIG -> config != null;
                case EXTENSIONS -> extensions;
                case CLASSES -> classes;
            };
        }
    }

    /// The config summary of a `releases.jsonl` line.
    ///
    /// @param source    `model` or `bytecode`
    /// @param extractor the reader and its version, e.g. `model-json/1`
    /// @param roots     number of config roots
    public record ConfigInfo(String source, String extractor, int roots) {
    }

    /// Where the files come from: a folder or, for checks before writing, the encoder's output.
    private interface FileSource {
        /// The file's content, null when it does not exist.
        String read(String name) throws IOException;

        /// How messages name the file.
        String describe(String name);
    }

    /// A value computed on first use.
    private static final class Lazy<T> {
        private final IoSupplier<T> supplier;
        private volatile T value;

        Lazy(IoSupplier<T> supplier) {
            this.supplier = supplier;
        }

        T get() throws IOException {
            T result = value;
            if (result == null) {
                synchronized (this) {
                    result = value;
                    if (result == null) {
                        result = supplier.get();
                        value = result;
                    }
                }
            }
            return result;
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    /// The change points of one id: release ordinals (ascending) and the value from each one on.
    private record Points(int[] ordinals, String[] values) {

        /// The value at a release ordinal, null when absent.
        String at(int ordinal) {
            int index = Arrays.binarySearch(ordinals, ordinal);
            if (index < 0) {
                index = -index - 2;
            }
            return index < 0 ? null : values[index];
        }
    }

    /// The decoded histories of one kind.
    ///
    /// @param ordinals the ordinal of each release that holds the kind, in release order
    /// @param rows     id → change points, sorted by id
    private record Table(Map<String, Integer> ordinals, SortedMap<String, Points> rows) {

        /// Every value of every change point (the state ids of config and extensions), nulls left out.
        Set<String> values() {
            Set<String> values = new HashSet<>();
            for (Points points : rows.values()) {
                for (String value : points.values()) {
                    if (value != null) {
                        values.add(value);
                    }
                }
            }
            return values;
        }
    }

    /// A classes file decoded: its histories, and the artifactId and class of each of its lines, in line (id) order.
    /// Restored snapshots share these strings, so every release does not hold copies of its class names.
    private record ClassTable(Table table, String[] artifacts, String[] classes) {
    }

    private final FileSource files;
    private final List<Release> releases;
    private final Map<String, Release> byVersion;
    private final Lazy<Table> bomTable;
    private final Lazy<Table> configTable;
    private final Lazy<Table> extensionTable;
    /// Major version line → its classes file, decoded on first use.
    private final Map<Integer, Lazy<ClassTable>> classTables;
    private final Lazy<Map<String, ConfigProperty>> configStates;
    private final Lazy<Map<String, ExtensionInfo>> extensionStates;
    private final Lazy<Map<String, DatasetEncoder.ConfigMeta>> configMeta;

    private Dataset(FileSource files) throws IOException {
        this.files = files;
        this.releases = readReleases();
        Map<String, Release> index = new HashMap<>();
        releases.forEach(release -> index.put(release.version(), release));
        this.byVersion = index;
        this.bomTable = new Lazy<>(() -> readTable(DatasetFormat.BOM, "id", Kind.BOM));
        this.configTable = new Lazy<>(() -> readTable(DatasetFormat.CONFIG, "key", Kind.CONFIG));
        this.extensionTable = new Lazy<>(() -> readTable(DatasetFormat.EXTENSIONS, "id", Kind.EXTENSIONS));
        Map<Integer, Lazy<ClassTable>> tables = new HashMap<>();
        for (String version : versionsWith(Kind.CLASSES)) {
            tables.computeIfAbsent(DatasetFormat.majorOf(version), major -> new Lazy<>(() -> readClassTable(major)));
        }
        this.classTables = tables;
        this.configStates = new Lazy<>(() -> readStates(DatasetFormat.CONFIG_STATES, ConfigProperty.class,
                DatasetFormat.CONFIG, configTable.get()));
        this.extensionStates = new Lazy<>(() -> readStates(DatasetFormat.EXTENSION_STATES, ExtensionInfo.class,
                DatasetFormat.EXTENSIONS, extensionTable.get()));
        this.configMeta = new Lazy<>(this::readConfigMeta);
    }

    /// Opens the dataset in a folder; a folder without `releases.jsonl` (or no folder) is an empty dataset.
    ///
    /// @throws IOException when `README.md` names another format, `releases.jsonl` cannot be read, or the folder has
    ///                     [DatasetFormat#WRITE_MARKER] (a write was interrupted, so the files may not belong together)
    public static Dataset open(Path dir) throws IOException {
        Path marker = dir.resolve(DatasetFormat.WRITE_MARKER);
        if (Files.exists(marker)) {
            throw new IOException(DatasetFormat.interruptedWrite(marker));
        }
        return openFiles(dir);
    }

    /// Opens the files of a folder whether or not it has [DatasetFormat#WRITE_MARKER] (`dataset verify` reports the
    /// marker itself and still checks the files).
    static Dataset openFiles(Path dir) throws IOException {
        return new Dataset(new FileSource() {
            @Override
            public String read(String name) throws IOException {
                Path file = dir.resolve(name);
                return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
            }

            @Override
            public String describe(String name) {
                return dir.resolve(name).toString();
            }
        });
    }

    /// A dataset without releases.
    public static Dataset empty() {
        try {
            return of(Map.of());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /// A dataset over file contents held in memory (file name → content).
    static Dataset of(Map<String, String> contents) throws IOException {
        return new Dataset(new FileSource() {
            @Override
            public String read(String name) {
                return contents.get(name);
            }

            @Override
            public String describe(String name) {
                return name;
            }
        });
    }

    /// Whether the dataset knows no release.
    public boolean isEmpty() {
        return releases.isEmpty();
    }

    /// Every known release, in [ReleaseOrder].
    public List<Release> releases() {
        return releases;
    }

    /// The `releases.jsonl` line of a release.
    public Optional<Release> release(String version) {
        return Optional.ofNullable(byVersion.get(version));
    }

    /// Whether the dataset holds the release's snapshot of the given kind.
    public boolean has(String version, Kind kind) {
        Release release = byVersion.get(version);
        return release != null && release.has(kind);
    }

    /// The releases that hold the given kind, in [ReleaseOrder].
    public List<String> versionsWith(Kind kind) {
        return releases.stream().filter(release -> release.has(kind)).map(Release::version).toList();
    }

    /// Restores a snapshot of the given kind: a [BomSnapshot], [ConfigSnapshot], [ExtensionsSnapshot] or
    /// [ClassesSnapshot].
    ///
    /// @throws IOException when the dataset does not hold it or is inconsistent
    public Object snapshot(String version, Kind kind) throws IOException {
        return switch (kind) {
            case BOM -> bom(version);
            case CONFIG -> config(version);
            case EXTENSIONS -> extensions(version);
            case CLASSES -> classes(version);
        };
    }

    /// Restores the BOM snapshot of a release: the managed dependencies sorted by id, `"="` read as the release.
    public BomSnapshot bom(String version) throws IOException {
        Release release = require(version, Kind.BOM);
        Table table = bomTable.get();
        int ordinal = table.ordinals().get(version);
        List<ManagedDep> deps = new ArrayList<>();
        for (Map.Entry<String, Points> row : table.rows().entrySet()) {
            String value = row.getValue().at(ordinal);
            if (value != null) {
                deps.add(new ManagedDep(row.getKey(), value.equals(DatasetFormat.SAME_AS_RELEASE) ? version : value));
            }
        }
        return new BomSnapshot(version, release.date(), Collections.unmodifiableList(deps));
    }

    /// Restores the config snapshot of a release: its `config-meta.jsonl` line and the state of every key at the
    /// release, sorted by key.
    public ConfigSnapshot config(String version) throws IOException {
        Release release = require(version, Kind.CONFIG);
        DatasetEncoder.ConfigMeta meta = configMeta.get().get(version);
        if (meta == null) {
            throw new IOException(files.describe(DatasetFormat.CONFIG_META) + ": no line for " + version
                    + ", which has config in " + DatasetFormat.RELEASES);
        }
        ConfigInfo info = new ConfigInfo(meta.source(), meta.extractor(), meta.roots());
        if (!info.equals(release.config())) {
            throw new IOException(files.describe(DatasetFormat.CONFIG_META) + ": the line of " + version + " says "
                    + info + " but " + DatasetFormat.RELEASES + " says " + release.config());
        }
        Table table = configTable.get();
        Map<String, ConfigProperty> states = configStates.get();
        int ordinal = table.ordinals().get(version);
        List<ConfigProperty> properties = new ArrayList<>();
        for (Map.Entry<String, Points> row : table.rows().entrySet()) {
            String id = row.getValue().at(ordinal);
            if (id != null) {
                ConfigProperty property = state(states, id, row.getKey(), version, DatasetFormat.CONFIG_STATES);
                if (!row.getKey().equals(property.key())) {
                    throw new IOException(files.describe(DatasetFormat.CONFIG_STATES) + ": state " + id + " has key "
                            + property.key() + " but " + DatasetFormat.CONFIG + " uses it for " + row.getKey());
                }
                properties.add(property);
            }
        }
        return new ConfigSnapshot(version, meta.source(), meta.extractor(), meta.roots(), meta.warnings(),
                Collections.unmodifiableList(properties));
    }

    /// Restores the extensions snapshot of a release: the state of every extension at the release, sorted by id.
    public ExtensionsSnapshot extensions(String version) throws IOException {
        require(version, Kind.EXTENSIONS);
        Table table = extensionTable.get();
        Map<String, ExtensionInfo> states = extensionStates.get();
        int ordinal = table.ordinals().get(version);
        List<ExtensionInfo> extensions = new ArrayList<>();
        for (Map.Entry<String, Points> row : table.rows().entrySet()) {
            String id = row.getValue().at(ordinal);
            if (id != null) {
                ExtensionInfo extension = state(states, id, row.getKey(), version, DatasetFormat.EXTENSION_STATES);
                if (!row.getKey().equals(extension.id())) {
                    throw new IOException(files.describe(DatasetFormat.EXTENSION_STATES) + ": state " + id
                            + " has id " + extension.id() + " but " + DatasetFormat.EXTENSIONS + " uses it for "
                            + row.getKey());
                }
                extensions.add(extension);
            }
        }
        return new ExtensionsSnapshot(version, Collections.unmodifiableList(extensions));
    }

    /// Restores the classes snapshot of a release from the classes file of its major line: per artifact, every class
    /// present at the release, both sorted. The lines are sorted by `<artifactId> <class>`, which sorts by artifactId
    /// first (no artifactId has a space), so the lines of an artifact follow each other.
    public ClassesSnapshot classes(String version) throws IOException {
        require(version, Kind.CLASSES);
        ClassTable decoded = classTables.get(DatasetFormat.majorOf(version)).get();
        int ordinal = decoded.table().ordinals().get(version);
        List<ArtifactClasses> artifacts = new ArrayList<>();
        String artifact = null;
        List<String> classes = new ArrayList<>();
        int i = 0;
        for (Points points : decoded.table().rows().values()) {
            if (points.at(ordinal) != null) {
                if (!decoded.artifacts()[i].equals(artifact)) {
                    if (artifact != null) {
                        artifacts.add(new ArtifactClasses(artifact, List.copyOf(classes)));
                    }
                    artifact = decoded.artifacts()[i];
                    classes.clear();
                }
                classes.add(decoded.classes()[i]);
            }
            i++;
        }
        if (artifact != null) {
            artifacts.add(new ArtifactClasses(artifact, List.copyOf(classes)));
        }
        return new ClassesSnapshot(version, Collections.unmodifiableList(artifacts));
    }

    /// Restores everything: every known release with its date and every snapshot.
    Contents contents() throws IOException {
        Contents contents = new Contents();
        for (Release release : releases) {
            contents.addRelease(release.version(), release.date());
            for (Kind kind : Kind.values()) {
                if (release.has(kind)) {
                    contents.put(release.version(), kind, snapshot(release.version(), kind));
                }
            }
        }
        return contents;
    }

    private Release require(String version, Kind kind) throws IOException {
        Release release = byVersion.get(version);
        if (release == null || !release.has(kind)) {
            throw new IOException("the dataset has no " + kind + " snapshot of " + version);
        }
        return release;
    }

    private <S> S state(Map<String, S> states, String id, String key, String version, String file)
            throws IOException {
        S state = states.get(id);
        if (state == null) {
            throw new IOException(files.describe(file) + ": no state " + id + " (used by " + key + " at " + version
                    + ")");
        }
        return state;
    }

    private List<Release> readReleases() throws IOException {
        String content = files.read(DatasetFormat.RELEASES);
        if (content == null) {
            return List.of();
        }
        checkFormat();
        SortedMap<String, Release> releases = new TreeMap<>(ReleaseOrder.COMPARATOR);
        List<String> lines = DatasetFormat.lines(content);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            JsonNode row = parse(DatasetFormat.RELEASES, i + 1, lines.get(i));
            String version = requiredText(row, "version", DatasetFormat.RELEASES, i + 1);
            JsonNode config = row.get("config");
            ConfigInfo info = null;
            if (config != null && !config.isNull()) {
                if (!config.isObject()) {
                    throw error(DatasetFormat.RELEASES, i + 1, "config is not an object");
                }
                info = new ConfigInfo(optionalText(config, "source"), optionalText(config, "extractor"),
                        config.path("roots").asInt());
            }
            Release release = new Release(version, optionalText(row, "date"), row.path("bom").asBoolean(false), info,
                    row.path("extensions").asBoolean(false), row.path("classes").asBoolean(false));
            if (releases.put(version, release) != null) {
                throw error(DatasetFormat.RELEASES, i + 1, version + " is listed twice");
            }
        }
        return List.copyOf(releases.values());
    }

    /// Fails when `README.md` names another format; a missing README is accepted (verify reports it).
    private void checkFormat() throws IOException {
        String readme = files.read(DatasetFormat.README);
        if (readme == null) {
            return;
        }
        List<String> lines = DatasetFormat.lines(readme);
        String first = lines.isEmpty() ? "" : lines.getFirst();
        if (!first.equals(DatasetFormat.README_TITLE)) {
            throw new IOException(files.describe(DatasetFormat.README) + ": not a format " + DatasetFormat.VERSION
                    + " dataset (its first line is '" + first + "', expected '" + DatasetFormat.README_TITLE + "')");
        }
    }

    private Table readTable(String file, String idField, Kind kind) throws IOException {
        return readTable(file, idField, versionsWith(kind), kind.toString());
    }

    /// Reads a history file whose change points may name the given releases only.
    ///
    /// @param versions the releases, in release order
    /// @param what     what they hold, for messages: `bom`, or `classes of the 3.x line`
    private Table readTable(String file, String idField, List<String> versions, String what) throws IOException {
        Map<String, Integer> ordinals = new HashMap<>();
        for (String version : versions) {
            ordinals.put(version, ordinals.size());
        }
        SortedMap<String, Points> rows = new TreeMap<>();
        String content = files.read(file);
        List<String> lines = content == null ? List.of() : DatasetFormat.lines(content);
        if (content == null && !ordinals.isEmpty()) {
            throw new IOException(files.describe(file) + " is missing, but " + DatasetFormat.RELEASES + " lists "
                    + ordinals.size() + " releases with " + what);
        }
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            int lineNumber = i + 1;
            JsonNode line = parse(file, lineNumber, lines.get(i));
            String id = requiredText(line, idField, file, lineNumber);
            JsonNode history = line.get("h");
            if (history == null || !history.isArray() || history.isEmpty()) {
                throw error(file, lineNumber, id + " has no change points");
            }
            int[] pointOrdinals = new int[history.size()];
            String[] values = new String[history.size()];
            for (int p = 0; p < history.size(); p++) {
                JsonNode point = history.get(p);
                if (!point.isArray() || point.size() != 2 || !point.get(0).isTextual()
                        || !(point.get(1).isTextual() || point.get(1).isNull())) {
                    throw error(file, lineNumber, id + ": change point " + point + " is not [\"<version>\", value]");
                }
                String version = point.get(0).textValue();
                Integer ordinal = ordinals.get(version);
                if (ordinal == null) {
                    throw error(file, lineNumber, id + " has a change point at " + version + ", which has no " + what
                            + " in " + DatasetFormat.RELEASES);
                }
                if (p > 0 && ordinal <= pointOrdinals[p - 1]) {
                    throw error(file, lineNumber, id + ": change point " + version + " is out of release order");
                }
                pointOrdinals[p] = ordinal;
                values[p] = point.get(1).isNull() ? null : point.get(1).textValue();
            }
            if (rows.put(id, new Points(pointOrdinals, values)) != null) {
                throw error(file, lineNumber, id + " has two lines");
            }
        }
        return new Table(ordinals, rows);
    }

    /// Reads the classes file of a major line: a history file over the releases of that line with classes, whose ids
    /// are `<artifactId> <class>` and whose only value is [DatasetFormat#CLASS_PRESENT].
    private ClassTable readClassTable(int major) throws IOException {
        String file = DatasetFormat.classesFile(major);
        List<String> versions = versionsWith(Kind.CLASSES).stream()
                .filter(version -> DatasetFormat.majorOf(version) == major).toList();
        Table table = readTable(file, "id", versions, "classes of the " + major + ".x line");
        String[] artifacts = new String[table.rows().size()];
        String[] classes = new String[artifacts.length];
        Map<String, String> sharedArtifacts = new HashMap<>();
        int i = 0;
        for (Map.Entry<String, Points> row : table.rows().entrySet()) {
            String id = row.getKey();
            int space = id.indexOf(' ');
            if (space <= 0 || space == id.length() - 1 || id.indexOf(' ', space + 1) >= 0) {
                throw new IOException(files.describe(file) + ": " + id + " is not <artifactId> <class>");
            }
            for (String value : row.getValue().values()) {
                if (value != null && !value.equals(DatasetFormat.CLASS_PRESENT)) {
                    throw new IOException(files.describe(file) + ": " + id + " has the value " + value + ", not \""
                            + DatasetFormat.CLASS_PRESENT + "\"");
                }
            }
            artifacts[i] = sharedArtifacts.computeIfAbsent(id.substring(0, space), a -> a);
            classes[i] = id.substring(space + 1);
            i++;
        }
        return new ClassTable(table, artifacts, classes);
    }

    /// Reads a state file: each state must hash to its id, and the states must be exactly those `history` refers to
    /// (a state nothing refers to means a line of the history file is missing or was changed).
    private <S> Map<String, S> readStates(String file, Class<S> type, String historyFile, Table history)
            throws IOException {
        Map<String, S> states = new HashMap<>();
        String content = files.read(file);
        List<String> lines = content == null ? List.of() : DatasetFormat.lines(content);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            JsonNode line = parse(file, i + 1, lines.get(i));
            String id = requiredText(line, "id", file, i + 1);
            JsonNode state = line.get("state");
            if (state == null || !state.isObject()) {
                throw error(file, i + 1, "state " + id + " is not an object");
            }
            S value;
            try {
                value = Json.mapper().treeToValue(state, type);
            } catch (JsonProcessingException e) {
                throw error(file, i + 1, "state " + id + " is not a " + type.getSimpleName() + ": "
                        + e.getOriginalMessage());
            }
            String hash = DatasetFormat.stateId(DatasetFormat.canonicalJson(value));
            if (!hash.equals(id)) {
                throw error(file, i + 1, "state " + id + " does not hash to its id (its " + type.getSimpleName()
                        + " hashes to " + hash + "): the line was changed");
            }
            if (states.put(id, value) != null) {
                throw error(file, i + 1, "state " + id + " has two lines");
            }
        }
        Set<String> referenced = history.values();
        for (String id : new TreeSet<>(referenced)) {
            if (!states.containsKey(id)) {
                throw new IOException(files.describe(file) + ": no state " + id + ", which " + historyFile
                        + " refers to");
            }
        }
        for (String id : new TreeSet<>(states.keySet())) {
            if (!referenced.contains(id)) {
                throw new IOException(files.describe(file) + ": state " + id + " is not referenced by " + historyFile
                        + " (a line of it is missing or was changed)");
            }
        }
        return states;
    }

    private Map<String, DatasetEncoder.ConfigMeta> readConfigMeta() throws IOException {
        Map<String, DatasetEncoder.ConfigMeta> metas = new LinkedHashMap<>();
        String file = DatasetFormat.CONFIG_META;
        String content = files.read(file);
        List<String> lines = content == null ? List.of() : DatasetFormat.lines(content);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            JsonNode line = parse(file, i + 1, lines.get(i));
            String version = requiredText(line, "version", file, i + 1);
            DatasetEncoder.ConfigMeta meta;
            try {
                meta = Json.mapper().treeToValue(line, DatasetEncoder.ConfigMeta.class);
            } catch (JsonProcessingException e) {
                throw error(file, i + 1, "not a config meta line: " + e.getOriginalMessage());
            }
            if (!has(version, Kind.CONFIG)) {
                throw error(file, i + 1, version + " has no config in " + DatasetFormat.RELEASES);
            }
            if (metas.put(version, meta) != null) {
                throw error(file, i + 1, version + " has two lines");
            }
        }
        return metas;
    }

    private JsonNode parse(String file, int lineNumber, String line) throws IOException {
        JsonNode node;
        try {
            node = DatasetFormat.parseLine(line);
        } catch (JsonProcessingException e) {
            throw error(file, lineNumber, "not JSON: " + e.getOriginalMessage());
        }
        if (node == null || !node.isObject()) {
            throw error(file, lineNumber, "not a JSON object");
        }
        return node;
    }

    private IOException error(String file, int lineNumber, String message) {
        return new IOException(files.describe(file) + ":" + lineNumber + ": " + message);
    }

    private String requiredText(JsonNode node, String field, String file, int lineNumber) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw error(file, lineNumber, "no " + field);
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
