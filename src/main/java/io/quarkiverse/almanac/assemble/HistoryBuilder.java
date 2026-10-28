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
import io.quarkiverse.almanac.model.BomSnapshot;
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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/// Turns per-version snapshots into the compact site data: `versions.json`, `bom.json`
/// (change points, `"="` for "the release's own version"), `config.json` (interned states, change points
/// over config versions, latest descriptions), `extensions.json` (change points over extension versions: the
/// releases with an extensions snapshot, flagged `ext` in `versions.json`) and `platform.json` (how the platform BOM
/// differs from the BOM, over the releases with a platform BOM snapshot; see [#build]).
///
/// Releases are [added][#add] in ascending version order, then [#build] produces the documents.
/// Before building config histories, each snapshot is normalised by [unnamed-key folding][#foldUnnamedKeys].
public final class HistoryBuilder {

    /// The snapshots of one release. A release with config also has extensions; one with extensions may have no
    /// config (extensions read from the release's extension catalog, before config was extracted). The platform BOM,
    /// of the same version, may be missing.
    public record Release(BomSnapshot bom, ConfigSnapshot config, ExtensionsSnapshot extensions,
            BomSnapshot platform) {

        public Release {
            Objects.requireNonNull(bom, "bom");
            if (config != null && extensions == null) {
                throw new IllegalArgumentException("a config snapshot needs the release's extensions snapshot");
            }
        }

        /// A release without platform BOM snapshot.
        public Release(BomSnapshot bom, ConfigSnapshot config, ExtensionsSnapshot extensions) {
            this(bom, config, extensions, null);
        }

        boolean hasConfig() {
            return config != null;
        }

        boolean hasExtensions() {
            return extensions != null;
        }

        boolean hasPlatform() {
            return platform != null;
        }
    }

    /// The site data documents.
    public record SiteData(ObjectNode versions, ObjectNode bom, ObjectNode config, ObjectNode extensions,
            ObjectNode platform) {

        /// File name → document, in writing order.
        public Map<String, ObjectNode> files() {
            Map<String, ObjectNode> files = new LinkedHashMap<>();
            files.put("versions.json", versions);
            files.put("bom.json", bom);
            files.put("config.json", config);
            files.put("extensions.json", extensions);
            files.put("platform.json", platform);
            return files;
        }
    }

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
        for (Map.Entry<String, ObjectNode> file : data.files().entrySet()) {
            Path path = siteDataDir.resolve(file.getKey());
            Json.writeCompact(path, file.getValue());
            Log.info("assemble: wrote %s (%,d bytes)", path, Files.size(path));
        }
        Log.info("assemble: %d releases (%d pre-release, %d with config, %d with extensions, %d with a platform BOM), "
                        + "%d BOM artifacts, %d ids that a platform BOM manages otherwise, %d config keys in %d "
                        + "states, %d extensions in %d states", builder.versionRows.size(),
                versions.stream().filter(version -> !version.isFinal()).count(), builder.configVersions.size(),
                builder.extensionVersions, builder.platformVersions.size(), builder.bomHistory.size(),
                builder.platformHistory.size(), builder.configHistory.size(), builder.configStates.size(),
                builder.extensionHistory.size(), builder.extensionStates.size());
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
        return new SiteData(versions, bom, config, extensions, platform);
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
