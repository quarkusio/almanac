package io.quarkiverse.almanac.assemble;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkiverse.almanac.api.ApiGrammar;
import io.quarkiverse.almanac.config.ConfigProperties;
import io.quarkiverse.almanac.maven.BomDifferences;
import io.quarkiverse.almanac.maven.GitReleases;
import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.maven.VersionSelector;
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
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.model.MemberApi;
import io.quarkiverse.almanac.util.Json;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.SnapshotStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Turns per-version snapshots into the compact site data: `versions.json`, `bom.json` (change points, `"="` for "the
/// release's own version" of Quarkus's own artifacts), `config.json` (interned states, change points
/// over config versions, latest descriptions), `extensions.json` (change points over extension versions: the
/// releases with an extensions snapshot, flagged `ext` in `versions.json`), `platform.json` (how the platform BOM
/// differs from the BOM, over the releases with a platform BOM snapshot; see [#build]) and, per major version line,
/// `classes-<major>.json` (the jars of every class over the releases of that line with a classes snapshot, flagged
/// `classes`, with the deprecated API and the revision of each class's API over the releases with an API snapshot,
/// flagged `api`; see [#classesDocument]) and the API shards `api-<major>-<k>.json` (the API of the classes, a few
/// classes per file; see [#apiShards]), so that a page loads the classes of the lines it shows only, and the members of
/// the classes it shows.
///
/// A release whose git tag has no GitHub release has `ghRelease: false` in its `versions.json` row
/// ([#WITHOUT_GITHUB_RELEASE]). A release never published on Maven Central has `git` (`tag`, `commit`, `bom`): its BOM
/// comes from the poms at its git tag ([GitReleases]). `versions.json` then also has `reference`, the pre-launch
/// releases with nothing for the dataset (`v`, `date`, `stream`, `commit`, `why`: `no-bom` or `canceled`) whose next
/// release from a git tag was added, which the Releases page lists for reference.
///
/// Releases are [added][#add] in ascending version order, then [#build] produces the documents.
/// Before building config histories, each snapshot is normalised by [unnamed-key folding][#foldUnnamedKeys].
public final class HistoryBuilder {

    /// The snapshots of one release. A release with config also has extensions, unless it has no extension catalog
    /// (0.x); one with extensions may have no config (extensions read from the release's extension catalog, before
    /// config was extracted). Classes need neither. The platform BOM, of the same version, may be missing. The API
    /// needs the classes.
    public record Release(BomSnapshot bom, ConfigSnapshot config, ExtensionsSnapshot extensions,
            ClassesSnapshot classes, BomSnapshot platform, ApiSnapshot api) {

        public Release {
            Objects.requireNonNull(bom, "bom");
            if (config != null && extensions == null && QuarkusVersion.parse(bom.version()).hasExtensionCatalog()) {
                throw new IllegalArgumentException("a config snapshot needs the release's extensions snapshot");
            }
            if (api != null && classes == null) {
                throw new IllegalArgumentException("an API snapshot needs the release's classes snapshot");
            }
        }

        /// A release without API snapshot.
        public Release(BomSnapshot bom, ConfigSnapshot config, ExtensionsSnapshot extensions,
                ClassesSnapshot classes, BomSnapshot platform) {
            this(bom, config, extensions, classes, platform, null);
        }

        /// A release without classes and platform BOM snapshots.
        public Release(BomSnapshot bom, ConfigSnapshot config, ExtensionsSnapshot extensions) {
            this(bom, config, extensions, null, null);
        }

        /// A release without platform BOM and API snapshots.
        public Release(BomSnapshot bom, ConfigSnapshot config, ExtensionsSnapshot extensions,
                ClassesSnapshot classes) {
            this(bom, config, extensions, classes, null, null);
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

        boolean hasApi() {
            return api != null;
        }
    }

    /// The site data documents.
    ///
    /// @param classes   major version line → its classes document, for the lines with a classes snapshot
    /// @param apiShards major version line → its API shards ([#apiShards]), for the lines with an API snapshot
    public record SiteData(ObjectNode versions, ObjectNode bom, ObjectNode config, ObjectNode extensions,
            ObjectNode platform, SortedMap<Integer, ObjectNode> classes,
            SortedMap<Integer, List<ObjectNode>> apiShards) {

        /// File name → document, in writing order.
        public Map<String, ObjectNode> files() {
            Map<String, ObjectNode> files = new LinkedHashMap<>();
            files.put("versions.json", versions);
            files.put("bom.json", bom);
            files.put("config.json", config);
            files.put("extensions.json", extensions);
            files.put("platform.json", platform);
            classes.forEach((major, document) -> files.put(classesFile(major), document));
            apiShards.forEach((major, shards) -> {
                for (int k = 0; k < shards.size(); k++) {
                    files.put(apiShardFile(major, k), shards.get(k));
                }
            });
            return files;
        }
    }

    /// The site data file of the classes of a major version line: `classes-3.json` for the 3.x releases.
    public static String classesFile(int major) {
        return "classes-" + major + ".json";
    }

    /// The site data file of an API shard of a major version line: `api-3-17.json` for shard 17 of the 3.x releases.
    public static String apiShardFile(int major, int shard) {
        return "api-" + major + "-" + shard + ".json";
    }

    /// The number of API shards of a major version line: 32 for 3.x, 16 for 4.x, 8 for the others, so that a shard
    /// stays around 20-30 KB gzip as a line's API grows.
    public static int apiShardCount(int major) {
        return switch (major) {
            case 3 -> 32;
            case 4 -> 16;
            default -> 8;
        };
    }

    /// The API shard of a class: FNV-1a 32 (offset basis 2166136261, prime 16777619) of the UTF-8 bytes of its
    /// top-level binary name (up to the first `$`), unsigned, modulo `shards`, so that a nested class is in the shard
    /// of its outer class.
    public static int apiShardOf(String binaryName, int shards) {
        int dollar = binaryName.indexOf('$');
        return (int) (fnv1a32(dollar < 0 ? binaryName : binaryName.substring(0, dollar)) % shards);
    }

    /// FNV-1a 32 of the UTF-8 bytes of a text, unsigned.
    static long fnv1a32(String text) {
        int hash = 0x811C9DC5;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xFF;
            hash *= 0x01000193;
        }
        return Integer.toUnsignedLong(hash);
    }

    /// Any classes file name, for the files of lines that a new assembly no longer has.
    private static final Pattern CLASSES_FILE = Pattern.compile("classes-\\d+\\.json");
    /// Any API shard file name, for the shards that a new assembly no longer has.
    private static final Pattern API_SHARD_FILE = Pattern.compile("api-(\\d+)-\\d+\\.json");

    /// Value written for a managed version equal to the release's own version, of Quarkus's own artifacts only
    /// ([#isQuarkusOwn]).
    public static final String SAME_AS_RELEASE = "=";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Map<String, String> PHASE_CODES = Map.of(
            "BUILD_TIME", "B", "BUILD_AND_RUN_TIME_FIXED", "F", "RUN_TIME", "R", "BOOTSTRAP", "S");

    /// The releases whose git tag has no GitHub release, flagged `ghRelease: false` in `versions.json`. Every other
    /// release has one: the new-releases workflow adds only releases published on GitHub. Update it if a release is
    /// ever added by hand without one.
    static final Set<String> WITHOUT_GITHUB_RELEASE = Set.of("3.17.1", "3.34.4");

    private final ArrayNode versionRows = NODES.arrayNode();
    /// The releases never published on Maven Central that were added: the pre-launch releases next to them are listed
    /// for reference.
    private final Set<String> preLaunch = new HashSet<>();
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
    /// Per major version line: the API of its releases with an API snapshot.
    private final SortedMap<Integer, ApiLine> apiLines = new TreeMap<>();
    /// Per binary class name: each distinct API state of the class → its revision, numbered in order of first
    /// appearance over every line in release order.
    private final Map<String, Map<ClassApi, Integer>> apiRevisions = new HashMap<>();
    /// What is deprecated in some jar at the last release added with an API snapshot (a class by binary name, a member
    /// as `<class> <member key>`) → the release index where its deprecation began: the first of the unbroken run of
    /// releases with an API snapshot, in release order over every line, at which it is deprecated.
    private final Map<String, Integer> deprecatedSince = new HashMap<>();
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
        SortedMap<Integer, long[]> shardBytes = new TreeMap<>();
        for (Map.Entry<String, ObjectNode> file : files.entrySet()) {
            Path path = siteDataDir.resolve(file.getKey());
            Json.writeCompact(path, file.getValue());
            Matcher shard = API_SHARD_FILE.matcher(file.getKey());
            if (shard.matches()) {
                long[] count = shardBytes.computeIfAbsent(Integer.parseInt(shard.group(1)), major -> new long[2]);
                count[0]++;
                count[1] += Files.size(path);
            } else {
                Log.info("assemble: wrote %s (%,d bytes)", path, Files.size(path));
            }
        }
        shardBytes.forEach((major, count) -> Log.info("assemble: wrote %d API shards of the %d.x line, %s (%,d bytes)",
                count[0], major, siteDataDir.resolve(apiShardFile(major, 0)), count[1]));
        // the classes and API shards of a line that this assembly no longer has
        try (Stream<Path> entries = Files.list(siteDataDir)) {
            for (Path stale : entries.filter(path -> (CLASSES_FILE.matcher(path.getFileName().toString()).matches()
                    || API_SHARD_FILE.matcher(path.getFileName().toString()).matches())
                    && !files.containsKey(path.getFileName().toString())).toList()) {
                Files.delete(stale);
                Log.info("assemble: deleted %s", stale);
            }
        }
        Log.info("assemble: %d releases (%d pre-release, %d with config, %d with extensions, %d with classes, %d with "
                        + "a platform BOM, %d with API), %d BOM artifacts, %d ids that a platform BOM manages "
                        + "otherwise, %d config keys in %d states, %d extensions in %d states, classes of %d major "
                        + "lines",
                builder.versionRows.size(), versions.stream().filter(version -> !version.isFinal()).count(),
                builder.configVersions.size(), builder.extensionVersions,
                builder.classVersions.values().stream().mapToInt(ArrayNode::size).sum(),
                builder.platformVersions.size(),
                builder.apiLines.values().stream().mapToInt(line -> line.versions.size()).sum(),
                builder.bomHistory.size(), builder.platformHistory.size(), builder.configHistory.size(),
                builder.configStates.size(), builder.extensionHistory.size(), builder.extensionStates.size(),
                builder.classHistories.size());
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
        if (release.hasApi()) {
            row.put("api", true);
        }
        if (WITHOUT_GITHUB_RELEASE.contains(bom.version())) {
            row.put("ghRelease", false);
        }
        if (bom.git() != null) {
            // never published on Maven Central: the BOM comes from the poms at the release's git tag
            row.putObject("git").put("tag", bom.git().tag()).put("commit", bom.git().commit()).put("bom", bom.git().bom());
            preLaunch.add(bom.version());
        }

        Map<String, JsonNode> managed = new HashMap<>();
        for (ManagedDep dep : bom.deps()) {
            managed.put(dep.id(), TextNode.valueOf(
                    dep.v().equals(bom.version()) && isQuarkusOwn(dep.id()) ? SAME_AS_RELEASE : dep.v()));
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
            // "=" for Quarkus's own artifacts only, as in bom.json
            BomDifferences.of(bom.version(), bom.deps(), release.platform().deps())
                    .forEach((id, value) -> differences.put(id, TextNode.valueOf(
                            value.equals(BomDifferences.SAME_AS_RELEASE) && !isQuarkusOwn(id) ? bom.version() : value)));
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
        if (release.hasApi()) {
            addApi(vi, version.major(), release.api());
        }
    }

    /// Records the API of a release: the class and member values of each jar, the revision of each class's API (the
    /// state of its first jar by artifactId), and where the deprecation of what is deprecated began; at the first
    /// release of a line, what is deprecated since a release of an earlier line is the line's [ApiLine#from].
    private void addApi(int vi, int major, ApiSnapshot api) {
        ApiLine line = apiLines.computeIfAbsent(major, m -> new ApiLine());
        boolean firstOfLine = line.versions.isEmpty();
        line.versions.add(vi);
        Map<String, JsonNode> values = new HashMap<>();
        Map<String, ClassApi> firstJar = new HashMap<>();
        Map<String, Integer> since = new HashMap<>();
        for (ArtifactApi artifact : api.artifacts()) {
            for (ClassApi type : artifact.classes()) {
                String id = artifact.id() + " " + type.name();
                values.put(id, TextNode.valueOf(type.v()));
                if (ApiGrammar.isDeprecated(type.v())) {
                    since.put(type.name(), deprecatedSince.getOrDefault(type.name(), vi));
                }
                for (MemberApi member : type.members()) {
                    values.put(id + " " + member.id(), TextNode.valueOf(member.v()));
                    if (ApiGrammar.isDeprecated(member.v())) {
                        String item = type.name() + " " + member.id();
                        since.put(item, deprecatedSince.getOrDefault(item, vi));
                    }
                }
                firstJar.putIfAbsent(type.name(), type);
            }
        }
        // deprecated at the previous release with an API snapshot too, which is of an earlier line
        if (firstOfLine) {
            since.forEach((item, start) -> {
                if (start < vi) {
                    line.from.put(item, start);
                }
            });
        }
        deprecatedSince.clear();
        deprecatedSince.putAll(since);
        line.values.observe(vi, values);
        Map<String, JsonNode> revisions = new HashMap<>();
        firstJar.forEach((name, type) -> {
            Map<ClassApi, Integer> states = apiRevisions.computeIfAbsent(name, n -> new HashMap<>());
            Integer revision = states.get(type);
            if (revision == null) {
                revision = states.size();
                states.put(type, revision);
            }
            revisions.put(name, IntNode.valueOf(revision));
        });
        line.revisions.observe(vi, revisions);
    }

    /// The site documents for the releases added so far. `platform.json` has `versions`, the release indices with a
    /// platform BOM snapshot, and `artifacts`, the ids that a platform BOM manages otherwise than the BOM of the same
    /// release, with change points over those releases alone: the platform BOM's version (`"="` for the release's
    /// own, of Quarkus's own artifacts), `"-"` ([BomDifferences#NOT_MANAGED]) when only the BOM manages the id, `null`
    /// when both manage it alike or neither does.
    public SiteData build(Instant generatedAt) {
        ObjectNode versions = NODES.objectNode().put("generatedAt", generatedAt.truncatedTo(ChronoUnit.SECONDS)
                .toString());
        ArrayNode lts = versions.putArray("lts");
        VersionSelector.LTS_STREAMS.forEach(lts::add);
        versions.set("versions", versionRows.deepCopy());
        // the pre-launch releases with nothing for the dataset, which the Releases page lists for reference next to the
        // release from its git tag that came after each
        List<GitReleases.Reference> references = GitReleases.references().stream()
                .filter(r -> preLaunch.contains(r.follower()))
                .toList();
        if (!references.isEmpty()) {
            ArrayNode reference = versions.putArray("reference");
            for (GitReleases.Reference r : references) {
                reference.addObject().put("v", r.version()).put("date", r.date())
                        .put("stream", QuarkusVersion.parse(r.version()).stream()).put("commit", r.commit())
                        .put("why", r.why());
            }
        }

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
        // per line, the histories of the class and member values of each jar; what is deprecated in any of them
        SortedMap<Integer, ArrayNode> apiValues = new TreeMap<>();
        apiLines.forEach((major, line) -> apiValues.put(major, line.values.toJson("id")));
        Set<String> deprecated = deprecatedAnywhere(apiValues.values());
        SortedMap<Integer, ObjectNode> classes = new TreeMap<>();
        classHistories.forEach((major, history) -> classes.put(major, classesDocument(history,
                classVersions.get(major), apiLines.get(major), apiValues.get(major), deprecated,
                apiShardCount(major))));
        SortedMap<Integer, List<ObjectNode>> shards = new TreeMap<>();
        apiValues.forEach((major, values) -> shards.put(major, apiShards(values, apiShardCount(major))));
        return new SiteData(versions, bom, config, extensions, platform, classes, shards);
    }

    /// The classes (`<class>`) and members (`<class> <member key>`) deprecated in some jar at some release of any
    /// line, by binary name.
    ///
    /// @param lines per line, the value histories of [ApiLine#values] as JSON
    private static Set<String> deprecatedAnywhere(Collection<ArrayNode> lines) {
        Set<String> deprecated = new HashSet<>();
        for (ArrayNode values : lines) {
            for (JsonNode item : values) {
                for (JsonNode point : item.get("h")) {
                    if (point.get(1).isTextual() && ApiGrammar.isDeprecated(point.get(1).textValue())) {
                        deprecated.add(withoutArtifact(item.get("id").textValue()));
                        break;
                    }
                }
            }
        }
        return deprecated;
    }

    /// `<artifactId> <class>[ <member key>]` → `<class>[ <member key>]`.
    private static String withoutArtifact(String id) {
        return id.substring(id.indexOf(' ') + 1);
    }

    /// The classes document of a major version line: `versions` (the release indices of the line with a classes
    /// snapshot), `artifacts` and `packages` (sorted, referred to by index), and `classes`: per binary class name,
    /// sorted by package and then by name, `[package index, name in the package, change points]`, over the releases of
    /// the line alone (a history starts at the first presence in the line). A change point's value is the index of the
    /// jar that has the class, an array of indices (ascending) when several jars have it, or `null`.
    ///
    /// When releases of the line have an API snapshot, a class row that is API at one of them has a fourth element,
    /// its revisions `[[vi, rev|null], ...]` over those releases: `rev` numbers the distinct API states of the class
    /// (its value and the keys and values of its members, in the first jar by artifactId that has it as API), per
    /// class in order of first appearance over every line in release order, so that the same `rev` at two releases,
    /// even of two lines, means the same API; `null` means not API (absent). The document then also has `api`:
    ///
    /// - `versions`: the release indices of the line with an API snapshot, ascending;
    /// - `shards`: how many API shards the line has ([#apiShards]);
    /// - `dep`: `[class row index, artifact index, member key or null, [[vi, value|null], ...], from]` for every class
    ///   (member key null) and every member that is API in a jar at one of those releases at least, when the class, or
    ///   that member of the class (by binary name and member key, in any jar), is deprecated (`D` or `R`) at a release
    ///   of any line: a class deprecated in 3.x and no longer in 4.x has its row in both files. A row holds the whole
    ///   history of the value in that jar, in the API grammar (`io.quarkiverse.almanac.api.ApiGrammar`), over the
    ///   line's releases with an API snapshot; rows are sorted by class row, artifact and member key (the class
    ///   first). The fifth element `from`, optional, dates a deprecation that began in an earlier line: the release
    ///   index of the first of the unbroken run of releases with an API snapshot, in release order over every line, at
    ///   which the item (the class, or that member of the class, in any jar) is deprecated, when that run began in an
    ///   earlier line and still holds at this line's first release with an API snapshot (so the item is deprecated at
    ///   that release and at the release with an API snapshot just before it, of an earlier line). Every row of the
    ///   item in the document has the same `from`; without such a run, rows have four elements.
    ///
    /// @param apiValues  the value histories of `api` as JSON, null without API snapshot
    /// @param deprecated what is deprecated in any line ([#deprecatedAnywhere])
    private static ObjectNode classesDocument(ChangePoints history, ArrayNode lineVersions, ApiLine api,
            ArrayNode apiValues, Set<String> deprecated, int shards) {
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
        if (api != null) {
            Map<String, Integer> rowOf = new HashMap<>();
            for (int i = 0; i < items.size(); i++) {
                rowOf.put(items.get(i).get("n").textValue(), i);
            }
            for (JsonNode revisions : api.revisions.toJson("n")) {
                Integer row = rowOf.get(revisions.get("n").textValue());
                if (row != null) {
                    ((ArrayNode) rows.get(row)).add(revisions.get("h"));
                }
            }
            ObjectNode apiData = classes.putObject("api");
            apiData.set("versions", api.versions.deepCopy());
            apiData.put("shards", shards);
            apiData.set("dep", deprecations(apiValues, deprecated, api.from, rowOf, artifactIndex));
        }
        return classes;
    }

    /// The `dep` rows of a classes document (see [#classesDocument]).
    ///
    /// @param from [ApiLine#from] of the line
    private static ArrayNode deprecations(ArrayNode apiValues, Set<String> deprecated, Map<String, Integer> from,
            Map<String, Integer> rowOf, Map<String, Integer> artifactIndex) {
        List<ArrayNode> rows = new ArrayList<>();
        for (JsonNode item : apiValues) {
            String id = item.get("id").textValue();
            String key = withoutArtifact(id);
            if (!deprecated.contains(key)) {
                continue;
            }
            String[] parts = id.split(" ", 3);
            Integer row = rowOf.get(parts[1]);
            Integer artifact = artifactIndex.get(parts[0]);
            if (row == null || artifact == null) {
                Log.warn("assemble: the API of %s %s is not among the classes; left out", parts[0], parts[1]);
                continue;
            }
            ArrayNode dep = NODES.arrayNode().add(row).add(artifact);
            if (parts.length == 3) {
                dep.add(parts[2]);
            } else {
                dep.addNull();
            }
            dep.add(item.get("h"));
            Integer since = from.get(key);
            if (since != null) {
                dep.add(since);
            }
            rows.add(dep);
        }
        rows.sort(Comparator.comparingInt((ArrayNode dep) -> dep.get(0).intValue())
                .thenComparingInt(dep -> dep.get(1).intValue())
                .thenComparing(dep -> dep.get(2).isNull() ? "" : " " + dep.get(2).textValue()));
        ArrayNode dep = NODES.arrayNode();
        rows.forEach(dep::add);
        return dep;
    }

    /// The API shards of a major version line, `api-<major>-<k>.json` for `k` from 0 to `shards - 1`, each
    /// `{"rows": [[artifactId, binary name, [[version, value|null], ...], [[member key, [[version, value|null], ...]],
    /// ...]], ...]}`: per jar and class that is API at one of the line's releases with an API snapshot, the history of
    /// its value and of each member's value over those releases, values in the API grammar
    /// (`io.quarkiverse.almanac.api.ApiGrammar`). Change points name releases by version, not by release index, so that
    /// a release changes only the shards of the classes whose API it changes (a release inserted between others moves
    /// every later index). A class is in shard [#apiShardOf], with its nested classes; rows are sorted by binary name,
    /// then artifactId, members by key.
    ///
    /// @param apiValues the value histories of the line's [ApiLine#values] as JSON
    private List<ObjectNode> apiShards(ArrayNode apiValues, int shards) {
        List<List<ArrayNode>> rowsOf = new ArrayList<>();
        for (int k = 0; k < shards; k++) {
            rowsOf.add(new ArrayList<>());
        }
        ArrayNode members = null;
        for (JsonNode item : apiValues) {
            String[] parts = item.get("id").textValue().split(" ", 3);
            ArrayNode history = byVersion(item.get("h"));
            if (parts.length == 2) {
                ArrayNode row = NODES.arrayNode().add(parts[0]).add(parts[1]).add(history);
                members = row.addArray();
                rowsOf.get(apiShardOf(parts[1], shards)).add(row);
            } else if (members != null) {
                members.addArray().add(parts[2]).add(history);
            }
        }
        List<ObjectNode> documents = new ArrayList<>();
        for (List<ArrayNode> rows : rowsOf) {
            rows.sort(Comparator.comparing((ArrayNode row) -> row.get(1).textValue())
                    .thenComparing(row -> row.get(0).textValue()));
            ObjectNode document = NODES.objectNode();
            ArrayNode out = document.putArray("rows");
            rows.forEach(out::add);
            documents.add(document);
        }
        return documents;
    }

    /// Change points by release index → the same by version.
    private ArrayNode byVersion(JsonNode history) {
        ArrayNode points = NODES.arrayNode();
        for (JsonNode point : history) {
            points.addArray().add(versionRows.get(point.get(0).intValue()).get("v").textValue()).add(point.get(1));
        }
        return points;
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

    /// Whether an artifact (`groupId:artifactId…`) is Quarkus's own, by its groupId: `io.quarkus` and its subgroups
    /// (`io.quarkus.arc`, `io.quarkus.platform`, …), and `org.jboss.shamrock` before 0.10.0. Only these are written
    /// [#SAME_AS_RELEASE]: a third-party artifact whose version happens to be the release's keeps its version (the
    /// Mutiny Vert.x bindings 3.14.0 of Quarkus 3.14.0), so that the site does not take it for a Quarkus module.
    static boolean isQuarkusOwn(String id) {
        int colon = id.indexOf(':');
        String group = colon < 0 ? id : id.substring(0, colon);
        return group.equals("io.quarkus") || group.startsWith("io.quarkus.") || group.equals("org.jboss.shamrock");
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

    /// The API of the releases of a major version line that have an API snapshot.
    private static final class ApiLine {
        /// The release indices with an API snapshot.
        private final ArrayNode versions = NODES.arrayNode();
        /// `<artifactId> <class>` → class value, `<artifactId> <class> <member>` → member value.
        private final ChangePoints values = new ChangePoints();
        /// Binary class name → revision of its API.
        private final ChangePoints revisions = new ChangePoints();
        /// What is deprecated at the line's first release with an API snapshot and was at the one before it, of an
        /// earlier line (a class by binary name, a member as `<class> <member key>`) → the release index where that
        /// deprecation began.
        private final Map<String, Integer> from = new HashMap<>();
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
