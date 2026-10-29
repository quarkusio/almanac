package io.quarkiverse.almanac.dataset;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkiverse.almanac.maven.BomDifferences;
import io.quarkiverse.almanac.model.ApiSnapshot;
import io.quarkiverse.almanac.model.ArtifactApi;
import io.quarkiverse.almanac.model.ArtifactClasses;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ClassApi;
import io.quarkiverse.almanac.model.ClassesSnapshot;
import io.quarkiverse.almanac.model.ConfigProperty;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.model.ExtensionInfo;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import io.quarkiverse.almanac.model.GitSource;
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.model.MemberApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiFunction;

/// Writes [Contents] as the files of format 1. The output depends only on the contents, never on the
/// order in which releases were added, so encoding the same contents always gives the same bytes and adding a release
/// changes only the lines whose history changes, plus the lines of that release. The classes and the API go to one file
/// per major version line each, over the releases of its line, so a release never changes the file of another line.
final class DatasetEncoder {

    /// One line of `config-meta.jsonl`: a config snapshot without its properties.
    record ConfigMeta(String version, String source, String extractor, int roots, List<String> warnings) {
    }

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private DatasetEncoder() {
    }

    /// File name → content, in [DatasetFormat#FILES] order with the classes files, then the API files (by major line)
    /// before `README.md`.
    static Map<String, String> encode(Contents contents) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(DatasetFormat.RELEASES, releases(contents));
        files.put(DatasetFormat.BOM, histories("id", contents.boms(), DatasetEncoder::bomValues));
        files.put(DatasetFormat.PLATFORM, histories("id", contents.platforms(), (version, platform) -> {
            BomSnapshot bom = contents.boms().get(version);
            if (bom == null) {
                throw new IllegalArgumentException("platform BOM " + version + ": the release has no BOM to keep its "
                        + "differences from");
            }
            return BomDifferences.of(version, bom.deps(), platform.deps());
        }));

        StateTable configStates = new StateTable();
        files.put(DatasetFormat.CONFIG, histories("key", contents.configs(), (version, config) -> {
            Map<String, String> values = new HashMap<>();
            for (ConfigProperty property : config.properties()) {
                if (values.put(property.key(), configStates.add(property)) != null) {
                    throw new IllegalArgumentException("config " + version + ": duplicate key " + property.key());
                }
            }
            return values;
        }));
        files.put(DatasetFormat.CONFIG_STATES, configStates.lines());
        files.put(DatasetFormat.CONFIG_META, configMeta(contents.configs()));

        StateTable extensionStates = new StateTable();
        files.put(DatasetFormat.EXTENSIONS, histories("id", contents.extensions(), (version, extensions) -> {
            Map<String, String> values = new HashMap<>();
            for (ExtensionInfo extension : extensions.extensions()) {
                if (values.put(extension.id(), extensionStates.add(extension)) != null) {
                    throw new IllegalArgumentException("extensions " + version + ": duplicate id " + extension.id());
                }
            }
            return values;
        }));
        files.put(DatasetFormat.EXTENSION_STATES, extensionStates.lines());
        SortedMap<Integer, SortedMap<String, ClassesSnapshot>> classesByLine = new TreeMap<>();
        contents.classes().forEach((version, snapshot) -> classesByLine.computeIfAbsent(DatasetFormat.majorOf(version),
                major -> new TreeMap<>(ReleaseOrder.COMPARATOR)).put(version, snapshot));
        classesByLine.forEach((major, snapshots) -> files.put(DatasetFormat.classesFile(major),
                histories("id", snapshots, DatasetEncoder::classValues)));
        SortedMap<Integer, SortedMap<String, ApiSnapshot>> apiByLine = new TreeMap<>();
        contents.apis().forEach((version, snapshot) -> apiByLine.computeIfAbsent(DatasetFormat.majorOf(version),
                major -> new TreeMap<>(ReleaseOrder.COMPARATOR)).put(version, snapshot));
        apiByLine.forEach((major, snapshots) -> files.put(DatasetFormat.apiFile(major),
                histories("id", snapshots, new ApiValues()::of)));
        files.put(DatasetFormat.README, DatasetFormat.readme());
        return files;
    }

    private static String releases(Contents contents) {
        StringBuilder out = new StringBuilder();
        for (String version : contents.releases()) {
            ObjectNode row = NODES.objectNode().put("version", version);
            String date = contents.date(version);
            if (date != null) {
                row.put("date", date);
            }
            if (contents.has(version, Kind.BOM)) {
                row.put("bom", true);
                GitSource git = contents.boms().get(version).git();
                if (git != null) {
                    row.putObject("git").put("tag", git.tag()).put("commit", git.commit()).put("bom", git.bom());
                }
            }
            ConfigSnapshot config = contents.configs().get(version);
            if (config != null) {
                ObjectNode info = row.putObject("config");
                if (config.source() != null) {
                    info.put("source", config.source());
                }
                if (config.extractor() != null) {
                    info.put("extractor", config.extractor());
                }
                info.put("roots", config.roots());
            }
            if (contents.has(version, Kind.EXTENSIONS)) {
                row.put("extensions", true);
            }
            if (contents.has(version, Kind.CLASSES)) {
                row.put("classes", true);
            }
            BomSnapshot platform = contents.platforms().get(version);
            if (platform != null) {
                ObjectNode info = row.putObject("platform");
                if (platform.date() != null) {
                    info.put("date", platform.date());
                }
            }
            ApiSnapshot api = contents.apis().get(version);
            if (api != null) {
                ObjectNode info = row.putObject("api");
                if (api.reader() != null) {
                    info.put("reader", api.reader());
                }
            }
            appendLine(out, row);
        }
        return out.toString();
    }

    /// BOM history values of one release: the managed version, or `"="` when it is the release's own version.
    private static Map<String, String> bomValues(String version, BomSnapshot bom) {
        Map<String, String> values = new HashMap<>();
        for (ManagedDep dep : bom.deps()) {
            String value = dep.v().equals(version) ? DatasetFormat.SAME_AS_RELEASE : dep.v();
            if (values.put(dep.id(), value) != null) {
                throw new IllegalArgumentException("bom " + version + ": duplicate id " + dep.id());
            }
        }
        return values;
    }

    /// Class history values of one release: [DatasetFormat#CLASS_PRESENT] for every `<artifactId> <class>`.
    private static Map<String, String> classValues(String version, ClassesSnapshot snapshot) {
        Map<String, String> values = new HashMap<>();
        for (ArtifactClasses artifact : snapshot.artifacts()) {
            for (String name : artifact.classes()) {
                if (values.put(artifact.id() + " " + name, DatasetFormat.CLASS_PRESENT) != null) {
                    throw new IllegalArgumentException("classes " + version + ": " + name + " twice in "
                            + artifact.id());
                }
            }
        }
        return values;
    }

    /// API history values of the releases of a line: the class value of every `<artifactId> <class>`, the member value
    /// of every `<artifactId> <class> <member>`. Restored snapshots share a class record across the releases where it
    /// does not change, so the ids of each record are made once.
    private static final class ApiValues {
        /// Per artifactId, the ids of each class record: the class line's, then those of its members in order.
        private final Map<String, Map<ClassApi, String[]>> ids = new HashMap<>();

        Map<String, String> of(String version, ApiSnapshot snapshot) {
            Map<String, String> values = new HashMap<>();
            for (ArtifactApi artifact : snapshot.artifacts()) {
                Map<ClassApi, String[]> known = ids.computeIfAbsent(artifact.id(), a -> new IdentityHashMap<>());
                for (ClassApi type : artifact.classes()) {
                    String[] lineIds = known.computeIfAbsent(type, t -> lineIds(artifact.id(), t));
                    if (values.put(lineIds[0], type.v()) != null) {
                        throw new IllegalArgumentException("api " + version + ": " + type.name() + " twice in "
                                + artifact.id());
                    }
                    for (int m = 0; m < type.members().size(); m++) {
                        MemberApi member = type.members().get(m);
                        if (values.put(lineIds[m + 1], member.v()) != null) {
                            throw new IllegalArgumentException("api " + version + ": " + type.name() + " "
                                    + member.id() + " twice in " + artifact.id());
                        }
                    }
                }
            }
            return values;
        }

        private static String[] lineIds(String artifactId, ClassApi type) {
            String[] lineIds = new String[type.members().size() + 1];
            lineIds[0] = artifactId + " " + type.name();
            for (int m = 0; m < type.members().size(); m++) {
                lineIds[m + 1] = lineIds[0] + " " + type.members().get(m).id();
            }
            return lineIds;
        }
    }

    /// One line per id, sorted: `{"<idField>": id, "h": [[version, value], ...]}` with a point wherever the value
    /// differs from the previous release's (absent = null; nothing before the first appearance). The releases are
    /// taken one at a time, so only the values of one release are in memory besides the points.
    private static <S> String histories(String idField, SortedMap<String, S> snapshots,
            BiFunction<String, S, Map<String, String>> valuesOf) {
        Map<String, List<String[]>> points = new HashMap<>();
        Map<String, String> current = new HashMap<>();
        snapshots.forEach((version, snapshot) -> {
            Map<String, String> released = valuesOf.apply(version, snapshot);
            for (Iterator<Map.Entry<String, String>> it = current.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, String> entry = it.next();
                if (!released.containsKey(entry.getKey())) {
                    points.get(entry.getKey()).add(new String[] {version, null});
                    it.remove();
                }
            }
            released.forEach((id, value) -> {
                if (!Objects.equals(value, current.get(id))) {
                    points.computeIfAbsent(id, k -> new ArrayList<>()).add(new String[] {version, value});
                    current.put(id, value);
                }
            });
        });
        StringBuilder out = new StringBuilder();
        for (String id : new TreeSet<>(points.keySet())) {
            ObjectNode line = NODES.objectNode().put(idField, id);
            ArrayNode history = line.putArray("h");
            for (String[] point : points.get(id)) {
                ArrayNode node = history.addArray().add(point[0]);
                if (point[1] == null) {
                    node.addNull();
                } else {
                    node.add(point[1]);
                }
            }
            appendLine(out, line);
        }
        return out.toString();
    }

    private static String configMeta(SortedMap<String, ConfigSnapshot> configs) {
        StringBuilder out = new StringBuilder();
        configs.forEach((version, config) -> out.append(DatasetFormat.canonicalJson(new ConfigMeta(version,
                config.source(), config.extractor(), config.roots(), config.warnings()))).append('\n'));
        return out.toString();
    }

    private static void appendLine(StringBuilder out, ObjectNode line) {
        out.append(DatasetFormat.canonicalJson(line)).append('\n');
    }

    /// Distinct states by content-addressed id. Decoded snapshots share state instances, so the canonical JSON is
    /// computed once per instance.
    private static final class StateTable {
        private final SortedMap<String, String> json = new TreeMap<>();
        private final Map<Object, String> ids = new IdentityHashMap<>();

        /// The id of a state, recording the state.
        String add(Object state) {
            String known = ids.get(state);
            if (known != null) {
                return known;
            }
            String canonical = DatasetFormat.canonicalJson(state);
            String id = DatasetFormat.stateId(canonical);
            String previous = json.putIfAbsent(id, canonical);
            if (previous != null && !previous.equals(canonical)) {
                throw new IllegalStateException("state id collision: " + id + " for " + previous + " and " + canonical);
            }
            ids.put(state, id);
            return id;
        }

        /// `{"id": id, "state": state}` lines sorted by id; the state JSON is exactly the hashed text.
        String lines() {
            StringBuilder out = new StringBuilder();
            json.forEach((id, state) -> out.append("{\"id\":\"").append(id).append("\",\"state\":").append(state)
                    .append("}\n"));
            return out.toString();
        }
    }
}
