package io.quarkiverse.almanac.dataset;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkiverse.almanac.maven.BomDifferences;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ConfigProperty;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.model.ExtensionInfo;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
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
/// changes only the lines whose history changes, plus the lines of that release.
final class DatasetEncoder {

    /// One line of `config-meta.jsonl`: a config snapshot without its properties.
    record ConfigMeta(String version, String source, String extractor, int roots, List<String> warnings) {
    }

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private DatasetEncoder() {
    }

    /// File name → content, in [DatasetFormat#FILES] order.
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
            BomSnapshot platform = contents.platforms().get(version);
            if (platform != null) {
                ObjectNode info = row.putObject("platform");
                if (platform.date() != null) {
                    info.put("date", platform.date());
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

    /// One line per id, sorted: `{"<idField>": id, "h": [[version, value], ...]}` with a point wherever the value
    /// differs from the previous release's (absent = null; nothing before the first appearance).
    private static <S> String histories(String idField, SortedMap<String, S> snapshots,
            BiFunction<String, S, Map<String, String>> valuesOf) {
        List<String> releases = new ArrayList<>(snapshots.size());
        List<Map<String, String>> values = new ArrayList<>(snapshots.size());
        TreeSet<String> ids = new TreeSet<>();
        snapshots.forEach((version, snapshot) -> {
            Map<String, String> released = valuesOf.apply(version, snapshot);
            releases.add(version);
            values.add(released);
            ids.addAll(released.keySet());
        });
        StringBuilder out = new StringBuilder();
        for (String id : ids) {
            ObjectNode line = NODES.objectNode().put(idField, id);
            ArrayNode points = line.putArray("h");
            String previous = null;
            for (int i = 0; i < releases.size(); i++) {
                String value = values.get(i).get(id);
                if (!Objects.equals(value, previous)) {
                    ArrayNode point = points.addArray().add(releases.get(i));
                    if (value == null) {
                        point.addNull();
                    } else {
                        point.add(value);
                    }
                    previous = value;
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
