package io.quarkiverse.almanac.assemble;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkiverse.almanac.config.ConfigProperties;
import io.quarkiverse.almanac.maven.BomDifferences;
import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.maven.VersionSelector;
import io.quarkiverse.almanac.model.ArtifactClasses;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ClassesSnapshot;
import io.quarkiverse.almanac.model.ConfigProperty;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.model.ExtensionInfo;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.util.Json;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.SnapshotStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Turns per-version snapshots into the compact site data: `versions.json`, `bom.json`
/// (change points, `"="` for "the release's own version"), `config.json` (interned states, change points
/// over config versions, latest descriptions), `extensions.json` (change points over extension versions: the
/// releases with an extensions snapshot, flagged `ext` in `versions.json`), `platform.json` (how the platform BOM
/// differs from the BOM, over the releases with a platform BOM snapshot; see [#build]) and, per major version line,
/// `classes-<major>.json` (the jars of every class over the releases of that line with a classes snapshot, flagged
/// `classes`; see [#classesDocument]), so that a page loads the classes of the lines it shows only.
///
/// Releases are [added][#add] in ascending version order, then [#build] produces the documents.
/// Before building config histories, each snapshot is normalised by [unnamed-key folding][#foldUnnamedKeys].
public final class HistoryBuilder {

    /// The snapshots of one release. A release with config also has extensions; one with extensions may have no
    /// config (extensions read from the release's extension catalog, before config was extracted). Classes need
    /// neither. The platform BOM, of the same version, may be missing.
    public record Release(BomSnapshot bom, ConfigSnapshot config, ExtensionsSnapshot extensions,
            ClassesSnapshot classes, BomSnapshot platform) {

        public Release {
            Objects.requireNonNull(bom, "bom");
            if (config != null && extensions == null) {
                throw new IllegalArgumentException("a config snapshot needs the release's extensions snapshot");
            }
        }

        /// A release without classes and platform BOM snapshots.
        public Release(BomSnapshot bom, ConfigSnapshot config, ExtensionsSnapshot extensions) {
            this(bom, config, extensions, null, null);
        }

        /// A release without platform BOM snapshot.
        public Release(BomSnapshot bom, ConfigSnapshot config, ExtensionsSnapshot extensions,
                ClassesSnapshot classes) {
            this(bom, config, extensions, classes, null);
        }

        boolean hasConfig() {
            return config != null;
        }

        boolean hasExtensions() {
            return extensions != null;
        }

        boolean hasClasses() {
            return classes != null;
        }

        boolean hasPlatform() {
            return platform != null;
        }
    }

    /// The site data documents.
    ///
    /// @param classes major version line → its classes document, for the lines with a classes snapshot
    public record SiteData(ObjectNode versions, ObjectNode bom, ObjectNode config, ObjectNode extensions,
            ObjectNode platform, SortedMap<Integer, ObjectNode> classes) {

        /// File name → document, in writing order.
        public Map<String, ObjectNode> files() {
            Map<String, ObjectNode> files = new LinkedHashMap<>();
            files.put("versions.json", versions);
            files.put("bom.json", bom);
            files.put("config.json", config);
            files.put("extensions.json", extensions);
            files.put("platform.json", platform);
            classes.forEach((major, document) -> files.put(classesFile(major), document));
            return files;
        }
    }

    /// The site data file of the classes of a major version line: `classes-3.json` for the 3.x releases.
    public static String classesFile(int major) {
        return "classes-" + major + ".json";
    }

    /// Any classes file name, for the files of lines that a new assembly no longer has.
    private static final Pattern CLASSES_FILE = Pattern.compile("classes-\\d+\\.json");

    /// Value written for a managed version equal to the release's own version.
    public static final String SAME_AS_RELEASE = "=";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Map<String, String> PHASE_CODES = Map.of(
            "BUILD_TIME", "B", "BUILD_AND_RUN_TIME_FIXED", "F", "RUN_TIME", "R", "BOOTSTRAP", "S");

    private final ArrayNode versionRows = NODES.arrayNode();
    private final ChangePoints bomHistory = new ChangePoints();
    private final ChangePoints configHistory = new ChangePoints();
    private final ChangePoints extensionHistory = new ChangePoints();
    private final ChangePoints platformHistory = new ChangePoints();
    private final ArrayNode platformVersions = NODES.arrayNode();
    /// Per major version line: binary class name → the artifactIds of the jars that have it, joined by spaces.
    private final SortedMap<Integer, ChangePoints> classHistories = new TreeMap<>();
    /// Per major version line: the release indices with a classes snapshot.
    private final SortedMap<Integer, ArrayNode> classVersions = new TreeMap<>();
    private final Interner configStates = new Interner();
    private final Interner extensionStates = new Interner();
    private final ArrayNode configVersions = NODES.arrayNode();
    private int extensionVersions;
    private final Map<String, String> descriptions = new TreeMap<>();
    private QuarkusVersion last;

    /// Reads every selected release that has a `bom.json` snapshot in the store (work folder, then dataset fallback)
    /// and writes the site data into `siteDataDir`; see [#assemble(ReleaseSource, Collection, Path, Instant)].
    ///
    /// @param selected the releases to publish, usually the `all` selection (`--since`, `--pre-releases`)
    public static void assemble(SnapshotStore store, Collection<QuarkusVersion> selected, Path siteDataDir,
            Instant generatedAt) throws IOException {
        assemble(ReleaseSource.of(store), selected, siteDataDir, generatedAt);
    }

    /// Reads every selected release that has a BOM snapshot in the source and writes the site data into
    /// `siteDataDir`; the snapshots of releases outside the selection are left out.
    ///
    /// @param selected the releases to publish, usually the `all` selection (`--since`, `--pre-releases`)
    public static void assemble(ReleaseSource source, Collection<QuarkusVersion> selected, Path siteDataDir,
            Instant generatedAt) throws IOException {
        Set<String> unselected = new TreeSet<>(source.bomVersions());
        List<QuarkusVersion> versions = new ArrayList<>();
        List<String> withoutBom = new ArrayList<>();
        for (QuarkusVersion version : new LinkedHashSet<>(selected)) {
            if (unselected.remove(version.text())) {
                versions.add(version);
            } else {
                withoutBom.add(version.text());
                if (source.hasConfig(version.text())) {
                    Log.warn("assemble: %s has a config snapshot but no bom.json; ignored", version);
                }
            }
        }
        versions.sort(Comparator.naturalOrder());
        if (!withoutBom.isEmpty()) {
            Log.info("assemble: %d selected releases have no BOM snapshot and are left out (%s)", withoutBom.size(),
                    abbreviated(withoutBom));
        }
        if (!unselected.isEmpty()) {
            Log.info("assemble: %d BOM snapshots outside the selection are left out (%s)", unselected.size(),
                    abbreviated(unselected));
        }
        HistoryBuilder builder = new HistoryBuilder();
        for (QuarkusVersion version : versions) {
            builder.add(source.load(version.text()));
        }
        SiteData data = builder.build(generatedAt);
        Files.createDirectories(siteDataDir);
        Map<String, ObjectNode> files = data.files();
        for (Map.Entry<String, ObjectNode> file : files.entrySet()) {
            Path path = siteDataDir.resolve(file.getKey());
            Json.writeCompact(path, file.getValue());
            Log.info("assemble: wrote %s (%,d bytes)", path, Files.size(path));
        }
        // the classes of a line that this assembly no longer has
        try (Stream<Path> entries = Files.list(siteDataDir)) {
            for (Path stale : entries.filter(path -> CLASSES_FILE.matcher(path.getFileName().toString()).matches()
                    && !files.containsKey(path.getFileName().toString())).toList()) {
                Files.delete(stale);
                Log.info("assemble: deleted %s", stale);
            }
        }
        Log.info("assemble: %d releases (%d pre-release, %d with config, %d with extensions, %d with classes, %d with "
                        + "a platform BOM), %d BOM artifacts, %d ids that a platform BOM manages otherwise, %d config "
                        + "keys in %d states, %d extensions in %d states, classes of %d major lines",
                builder.versionRows.size(), versions.stream().filter(version -> !version.isFinal()).count(),
                builder.configVersions.size(), builder.extensionVersions,
                builder.classVersions.values().stream().mapToInt(ArrayNode::size).sum(),
                builder.platformVersions.size(), builder.bomHistory.size(), builder.platformHistory.size(),
                builder.configHistory.size(), builder.configStates.size(), builder.extensionHistory.size(),
                builder.extensionStates.size(), builder.classHistories.size());
    }

    /// The first five names, then `...` when there are more.
    private static String abbreviated(Collection<String> names) {
        return names.stream().limit(5).collect(Collectors.joining(", ")) + (names.size() > 5 ? ", ..." : "");
    }

    /// Adds the next release; releases must come in ascending version order.
    public void add(Release release) {
        BomSnapshot bom = release.bom();
        QuarkusVersion version = QuarkusVersion.parse(bom.version());
        if (last != null && version.compareTo(last) <= 0) {
            throw new IllegalArgumentException(bom.version() + " added after " + last);
        }
        last = version;
        int vi = versionRows.size();

        ObjectNode row = versionRows.addObject().put("v", bom.version());
        putText(row, "date", bom.date());
        row.put("stream", version.stream())
                .put("pre", !version.isFinal())
                .put("lts", VersionSelector.isLts(version))
                .put("config", release.hasConfig());
        if (release.hasConfig()) {
            row.put("configSource", release.config().source());
        }
        row.put("ext", release.hasExtensions());
        row.put("classes", release.hasClasses());

        Map<String, JsonNode> managed = new HashMap<>();
        for (ManagedDep dep : bom.deps()) {
            managed.put(dep.id(), TextNode.valueOf(dep.v().equals(bom.version()) ? SAME_AS_RELEASE : dep.v()));
        }
        bomHistory.observe(vi, managed);

        if (release.hasConfig()) {
            configVersions.add(vi);
            Map<String, JsonNode> properties = new HashMap<>();
            for (ConfigProperty property : foldUnnamedKeys(release.config().properties())) {
                properties.put(property.key(), IntNode.valueOf(configStates.intern(configState(property))));
                if (property.desc() != null) {
                    descriptions.put(property.key(), property.desc());
                }
            }
            configHistory.observe(vi, properties);
        }
        if (release.hasExtensions()) {
            extensionVersions++;
            Map<String, JsonNode> extensions = new HashMap<>();
            for (ExtensionInfo extension : release.extensions().extensions()) {
                extensions.put(extension.id(), IntNode.valueOf(extensionStates.intern(extensionState(extension))));
            }
            extensionHistory.observe(vi, extensions);
        }
        if (release.hasPlatform()) {
            platformVersions.add(vi);
            Map<String, JsonNode> differences = new HashMap<>();
            BomDifferences.of(bom.version(), bom.deps(), release.platform().deps())
                    .forEach((id, value) -> differences.put(id, TextNode.valueOf(value)));
            platformHistory.observe(vi, differences);
        }
        if (release.hasClasses()) {
            classVersions.computeIfAbsent(version.major(), major -> NODES.arrayNode()).add(vi);
            Map<String, List<String>> jarsOfClass = new HashMap<>();
            for (ArtifactClasses artifact : release.classes().artifacts()) {
                for (String name : artifact.classes()) {
                    jarsOfClass.computeIfAbsent(name, n -> new ArrayList<>(1)).add(artifact.id());
                }
            }
            Map<String, JsonNode> classes = new HashMap<>();
            // the artifacts of a snapshot are sorted, so each list is
            jarsOfClass.forEach((name, jars) -> classes.put(name, TextNode.valueOf(String.join(" ", jars))));
            classHistories.computeIfAbsent(version.major(), major -> new ChangePoints()).observe(vi, classes);
        }
    }

    /// The site documents for the releases added so far. `platform.json` has `versions`, the release indices with a
    /// platform BOM snapshot, and `artifacts`, the ids that a platform BOM manages otherwise than the BOM of the same
    /// release, with change points over those releases alone: the platform BOM's version (`"="` for the release's
    /// own), `"-"` ([BomDifferences#NOT_MANAGED]) when only the BOM manages the id, `null` when both manage it alike or
    /// neither does.
    public SiteData build(Instant generatedAt) {
        ObjectNode versions = NODES.objectNode().put("generatedAt", generatedAt.truncatedTo(ChronoUnit.SECONDS)
                .toString());
        ArrayNode lts = versions.putArray("lts");
        VersionSelector.LTS_STREAMS.forEach(lts::add);
        versions.set("versions", versionRows.deepCopy());

        ObjectNode bom = NODES.objectNode();
        bom.set("artifacts", bomHistory.toJson("id"));

        ObjectNode config = NODES.objectNode();
        config.set("versions", configVersions.deepCopy());
        config.set("states", configStates.states().deepCopy());
        config.set("props", configHistory.toJson("k"));
        ObjectNode desc = config.putObject("desc");
        descriptions.forEach(desc::put);

        ObjectNode extensions = NODES.objectNode();
        extensions.set("states", extensionStates.states().deepCopy());
        extensions.set("exts", extensionHistory.toJson("id"));

        ObjectNode platform = NODES.objectNode();
        platform.set("versions", platformVersions.deepCopy());
        platform.set("artifacts", platformHistory.toJson("id"));
                SortedMap<Integer, ObjectNode> classes = new TreeMap<>();
        classHistories.forEach((major, history) -> classes.put(major,
                classesDocument(history, classVersions.get(major))));
        return new SiteData(versions, bom, config, extensions, platform, classes);
    }

    /// The classes document of a major version line: `versions` (the release indices of the line with a classes
    /// snapshot), `artifacts` and `packages` (sorted, referred to by index), and `classes`: per binary class name,
    /// sorted by package and then by name, `[package index, name in the package, change points]`, over the releases of
    /// the line alone (a history starts at the first presence in the line). A change point's value is the index of the
    /// jar that has the class, an array of indices (ascending) when several jars have it, or `null`.
    private static ObjectNode classesDocument(ChangePoints history, ArrayNode lineVersions) {
        ArrayNode histories = history.toJson("n");
        TreeSet<String> artifactNames = new TreeSet<>();
        TreeSet<String> packageNames = new TreeSet<>();
        for (JsonNode item : histories) {
            packageNames.add(packageOf(item.get("n").textValue()));
            for (JsonNode point : item.get("h")) {
                if (!point.get(1).isNull()) {
                    artifactNames.addAll(List.of(point.get(1).textValue().split(" ")));
                }
            }
        }
        Map<String, Integer> artifactIndex = indexOf(artifactNames);
        Map<String, Integer> packageIndex = indexOf(packageNames);
        List<JsonNode> items = new ArrayList<>();
        histories.forEach(items::add);
        items.sort(Comparator.comparing((JsonNode item) -> packageOf(item.get("n").textValue()))
                .thenComparing(item -> simpleNameOf(item.get("n").textValue())));
        ArrayNode rows = NODES.arrayNode();
        for (JsonNode item : items) {
            String name = item.get("n").textValue();
            ArrayNode row = rows.addArray().add(packageIndex.get(packageOf(name))).add(simpleNameOf(name));
            ArrayNode points = row.addArray();
            for (JsonNode point : item.get("h")) {
                ArrayNode out = points.addArray().add(point.get(0).intValue());
                if (point.get(1).isNull()) {
                    out.addNull();
                    continue;
                }
                String[] jars = point.get(1).textValue().split(" ");
                if (jars.length == 1) {
                    out.add(artifactIndex.get(jars[0]));
                } else {
                    ArrayNode several = out.addArray();
                    for (String jar : jars) {
                        several.add(artifactIndex.get(jar));
                    }
                }
            }
        }
        ObjectNode classes = NODES.objectNode();
        classes.set("versions", lineVersions.deepCopy());
        artifactNames.forEach(classes.putArray("artifacts")::add);
        packageNames.forEach(classes.putArray("packages")::add);
        classes.set("classes", rows);
        return classes;
    }

    /// The package of a binary class name (`io.quarkus.test` for `io.quarkus.test.QuarkusUnitTest$Builder`), empty for
    /// the unnamed package.
    static String packageOf(String binaryName) {
        int dot = binaryName.lastIndexOf('.');
        return dot < 0 ? "" : binaryName.substring(0, dot);
    }

    /// A binary class name without its package (`QuarkusUnitTest$Builder`).
    static String simpleNameOf(String binaryName) {
        return binaryName.substring(binaryName.lastIndexOf('.') + 1);
    }

    /// Each name → its position in the (sorted) set.
    private static Map<String, Integer> indexOf(Set<String> names) {
        Map<String, Integer> index = new HashMap<>();
        for (String name : names) {
            index.put(name, index.size());
        }
        return index;
    }

    /// Unnamed-key folding: a property whose key has an inner `*` segment is dropped when removing
    /// one such segment (tried left to right) gives a key of the same snapshot with the same ext, type and list flag;
    /// its name and aliases become aliases of that property. This makes legacy releases (default + named map as two
    /// properties) match the modern model (`@WithUnnamedKey`: one property with additional paths).
    public static List<ConfigProperty> foldUnnamedKeys(List<ConfigProperty> properties) {
        Map<String, ConfigProperty> byKey = new LinkedHashMap<>();
        properties.forEach(property -> byKey.put(property.key(), property));
        Map<String, String> foldedInto = new HashMap<>();
        for (ConfigProperty property : properties) {
            unnamedVariantOf(property, byKey).ifPresent(target -> foldedInto.put(property.key(), target));
        }
        if (foldedInto.isEmpty()) {
            return properties;
        }
        Map<String, ConfigProperty> result = new LinkedHashMap<>();
        for (ConfigProperty property : properties) {
            if (!foldedInto.containsKey(property.key())) {
                result.put(property.key(), property);
            }
        }
        for (ConfigProperty property : properties) {
            String target = foldedInto.get(property.key());
            if (target != null) {
                while (foldedInto.containsKey(target)) {
                    target = foldedInto.get(target);
                }
                result.put(target, ConfigProperties.withExtraAliases(result.get(target), property));
            }
        }
        return List.copyOf(result.values());
    }

    private static Optional<String> unnamedVariantOf(ConfigProperty property, Map<String, ConfigProperty> byKey) {
        String[] segments = property.key().split("\\.");
        for (int i = 1; i < segments.length - 1; i++) {
            if (!segments[i].equals("*")) {
                continue;
            }
            List<String> remaining = new ArrayList<>(List.of(segments));
            remaining.remove(i);
            String candidateKey = String.join(".", remaining);
            ConfigProperty candidate = byKey.get(candidateKey);
            if (candidate != null && Objects.equals(candidate.ext(), property.ext())
                    && Objects.equals(candidate.type(), property.type()) && candidate.list() == property.list()) {
                return Optional.of(candidateKey);
            }
        }
        return Optional.empty();
    }

    private static ObjectNode configState(ConfigProperty property) {
        ObjectNode state = NODES.objectNode();
        putText(state, "n", property.name());
        putList(state, "a", property.aliases());
        putText(state, "e", property.ext());
        putText(state, "t", property.type());
        putFlag(state, "l", property.list());
        putFlag(state, "o", property.optional());
        putFlag(state, "m", property.map());
        putText(state, "d", property.defaultValue());
        putText(state, "p", phaseCode(property.phase()));
        if (property.deprecated() != null) {
            state.put("x", property.deprecated()); // "" (deprecated, no version) is meaningful
        }
        if (property.enumValues() != null) {
            property.enumValues().forEach(state.putArray("en")::add);
        }
        return state;
    }

    private static ObjectNode extensionState(ExtensionInfo extension) {
        ObjectNode state = NODES.objectNode();
        putText(state, "n", extension.name());
        putText(state, "s", extension.status());
        putList(state, "c", extension.categories());
        putText(state, "g", extension.guide());
        putText(state, "d", extension.description());
        putFlag(state, "u", extension.unlisted());
        putText(state, "r", extension.relocatedTo());
        putList(state, "p", extension.configPrefixes());
        return state;
    }

    private static String phaseCode(String phase) {
        if (phase == null) {
            return null;
        }
        String code = PHASE_CODES.get(phase);
        if (code == null) {
            Log.warn("assemble: unknown config phase %s", phase);
        }
        return code;
    }

    /// Falsy values (null, empty) are omitted.
    private static void putText(ObjectNode node, String field, String value) {
        if (value != null && !value.isEmpty()) {
            node.put(field, value);
        }
    }

    private static void putList(ObjectNode node, String field, List<String> values) {
        if (values != null && !values.isEmpty()) {
            values.forEach(node.putArray(field)::add);
        }
    }

    private static void putFlag(ObjectNode node, String field, boolean value) {
        if (value) {
            node.put(field, 1);
        }
    }

    /// Assigns each distinct state an index, in order of first appearance.
    private static final class Interner {
        private final Map<JsonNode, Integer> indices = new HashMap<>();
        private final ArrayNode states = NODES.arrayNode();

        int intern(ObjectNode state) {
            return indices.computeIfAbsent(state, s -> {
                states.add(s);
                return states.size() - 1;
            });
        }

        ArrayNode states() {
            return states;
        }

        int size() {
            return states.size();
        }
    }
}
