package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.maven.BomResolver;
import io.quarkiverse.almanac.maven.DependencyProfile;
import io.quarkiverse.almanac.maven.DependencyProfile.Root;
import io.quarkiverse.almanac.maven.DependencyProfile.Use;
import io.quarkiverse.almanac.maven.Http;
import io.quarkiverse.almanac.maven.MavenRepo;
import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ExtensionInfo;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import io.quarkiverse.almanac.model.ProfileSnapshot;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.SnapshotStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/// `profile`: writes `profile.json` snapshots, the dependency profile of each release ([DependencyProfile]). A
/// preview: the dataset does not keep them; `assemble` publishes those of the work folder, and keeps those that
/// `site/data/profile.json` has already for the other releases.
@Command(name = "profile", description = "Write profile.json snapshots: of the artifacts that a release's BOM "
        + "manages, those that Quarkus itself depends on, through its extensions (runtime and deployment artifacts), "
        + "its Maven plugin and its test frameworks, each with its strongest use (runtime, build or test). Reads poms "
        + "only, from the repository; the BOM and extensions snapshots come from the work folder or the dataset. A "
        + "preview: the dataset does not keep these snapshots; assemble publishes those of the work folder, and keeps "
        + "those that site/data/profile.json has already for the other releases.")
public final class ProfileCommand implements Callable<Integer> {

    /// The snapshot file of a dependency profile.
    static final String PROFILE = "profile.json";

    @Mixin
    CommonOptions common;

    @Option(names = "--versions", required = true, paramLabel = "LIST",
            description = "Releases to process, e.g. 3.34.0,3.35.0.")
    String versions;

    @Option(names = "--force", description = "Rewrite existing profile snapshots.")
    boolean force;

    @Option(names = "--max-requests", paramLabel = "N",
            description = "Start no further release once this run has sent more than N requests (default: no limit).")
    long maxRequests = Long.MAX_VALUE;

    @Override
    public Integer call() throws IOException {
        try (Workspace workspace = Workspace.open(common)) {
            SnapshotStore work = workspace.workSnapshots();
            List<QuarkusVersion> selected = Workspace.published(workspace.select(versions), "profile");
            Http http = workspace.repo().http();
            ReleaseRun run = new ReleaseRun("profile", http, selected.size());
            long first = http.requestCount();
            int failed = 0;
            int overLimit = 0;
            for (QuarkusVersion version : selected) {
                if (!force && work.exists(version.text(), PROFILE)) {
                    Log.info("profile %s: already in %s (--force rewrites it)", version, work.root());
                    continue;
                }
                if (!run.mayStart()) {
                    continue;
                }
                if (http.requestCount() - first > maxRequests) {
                    if (overLimit++ == 0) {
                        Log.error("profile: more than %d requests sent, so no further release starts", maxRequests);
                    }
                    continue;
                }
                try {
                    work.write(version.text(), PROFILE, profile(workspace, version.text()));
                } catch (IOException e) {
                    Log.error("profile %s: %s", version, e.getMessage());
                    run.failed(e);
                    failed++;
                } catch (RuntimeException e) {
                    // one broken release must not stop the others
                    Log.error("profile %s: %s", version, e);
                    run.failed(e);
                    failed++;
                }
            }
            if (overLimit > 0) {
                Log.error("profile: %d releases not started, over --max-requests", overLimit);
            }
            return run.finish() && failed == 0 && overLimit == 0 ? 0 : 1;
        }
    }

    /// The dependency profile of a release, from its BOM snapshot, its extensions snapshot and the published BOM's
    /// exclusions and scopes.
    static ProfileSnapshot profile(Workspace workspace, String version) throws IOException {
        BomSnapshot bom = workspace.boms().existing(version)
                .orElseThrow(() -> new IOException("no BOM snapshot (bom first)"));
        Map<String, String> managed = new HashMap<>();
        bom.deps().forEach(dep -> managed.put(dep.id(), dep.v()));
        List<String> extensionIds = List.of();
        if (workspace.snapshots().exists(version, SnapshotStore.EXTENSIONS)) {
            ExtensionsSnapshot extensions = workspace.snapshots().read(version, SnapshotStore.EXTENSIONS,
                    ExtensionsSnapshot.class);
            extensionIds = extensions.extensions().stream().filter(extension -> extension.relocatedTo() == null)
                    .map(ExtensionInfo::id).toList();
        }
        if (extensionIds.isEmpty()) {
            Log.info("profile %s: no extension in its extensions snapshot, if any; the artifacts of Quarkus in the "
                    + "BOM stand for the extensions", version);
        }
        MavenRepo repo = workspace.repo();
        byte[] bomPom = repo.artifact("io.quarkus", "quarkus-bom", version, "pom")
                .orElseThrow(() -> new IOException("io.quarkus:quarkus-bom:" + version + " has no pom"))
                .content();
        Map<String, String> scopes = DependencyProfile.scopes(bomPom, version);
        Map<String, Root> roots = roots(version, managed, scopes, extensionIds,
                artifactId -> repo.artifact("io.quarkus", artifactId, version, "pom").isPresent());

        BomResolver.PomSource source = (groupId, artifactId, v) -> repo.artifact(groupId, artifactId, v, "pom")
                .map(MavenRepo.Download::content);
        DependencyProfile.Result result = new DependencyProfile(source, version, managed,
                DependencyProfile.exclusions(bomPom, version), scopes).collect(List.copyOf(roots.values()));

        TreeMap<String, String> used = new TreeMap<>();
        result.used().forEach((id, use) -> used.put(id, use.label()));
        Map<Use, Integer> byUse = new TreeMap<>();
        result.used().values().forEach(use -> byUse.merge(use, 1, Integer::sum));
        Set<String> graph = new HashSet<>(result.parents().keySet());
        result.parents().values().forEach(graph::addAll);
        Log.info("profile %s: %d roots, %d poms read, %d managed artifacts used (%d runtime, %d build, %d test), "
                        + "%d unmanaged artifacts met, %d nodes on the ways up, %d problems", version, roots.size(),
                result.poms(), used.size(), byUse.getOrDefault(Use.RUNTIME, 0), byUse.getOrDefault(Use.BUILD, 0),
                byUse.getOrDefault(Use.TEST, 0), result.unmanaged().size(), graph.size(), result.problems().size());
        result.problems().stream().limit(10).forEach(problem -> Log.warn("profile %s: %s", version, problem));
        TreeMap<String, List<String>> parents = new TreeMap<>();
        result.parents().forEach((node, nodes) -> parents.put(node, List.copyOf(nodes)));
        TreeMap<String, List<String>> starts = new TreeMap<>();
        result.starts().forEach((id, nodes) -> starts.put(id, List.copyOf(nodes)));
        return new ProfileSnapshot(version, roots.size(), result.poms(), result.unmanaged().size(),
                result.problems(), used, parents, starts);
    }

    /// Whether the repository has the pom of an artifact of Quarkus at the release.
    @FunctionalInterface
    interface Published {
        boolean has(String artifactId) throws IOException;
    }

    /// The artifacts of Quarkus that a release's profile starts from, by artifactId, each with its use, the first use
    /// given kept: the extensions (`extensionIds`, from the catalog), with their runtime and deployment artifacts, the
    /// Maven plugin, then the test frameworks of the BOM.
    ///
    /// The BOM lists the deployment artifacts since 1.6 only: before, those the repository has count too. Before the
    /// extension catalog (0.26 and older, `extensionIds` empty), the artifacts of Quarkus in the BOM stand for the
    /// extensions. In 0.11 and 0.12, whose BOM manages them in scope provided, those are the deployment modules, and
    /// the runtime modules are named `-runtime`; from 0.13 on they are the runtime modules, and the deployment ones
    /// are named `-deployment`.
    static Map<String, Root> roots(String version, Map<String, String> managed, Map<String, String> scopes,
            List<String> extensionIds, Published published) throws IOException {
        List<String> extensions = extensionIds;
        if (extensions.isEmpty()) {
            extensions = managed.keySet().stream()
                    .filter(id -> id.startsWith("io.quarkus:") && !id.endsWith("-deployment"))
                    .map(id -> id.substring("io.quarkus:".length()))
                    .filter(artifactId -> !artifactId.contains(":"))
                    .sorted().toList();
        }
        boolean deploymentModules = "provided".equals(scopes.get("io.quarkus:quarkus-core"));
        Map<String, Root> roots = new LinkedHashMap<>();
        for (String extension : extensions) {
            boolean tests = forTests(extension);
            if (deploymentModules && !tests) {
                if (published.has(extension + "-runtime")) {
                    roots.putIfAbsent(extension + "-runtime",
                            new Root("io.quarkus", extension + "-runtime", version, Use.RUNTIME));
                }
                if (managed.containsKey("io.quarkus:" + extension)) {
                    roots.putIfAbsent(extension, new Root("io.quarkus", extension, version, Use.BUILD));
                }
                continue;
            }
            if (managed.containsKey("io.quarkus:" + extension)) {
                roots.putIfAbsent(extension, new Root("io.quarkus", extension, version,
                        tests ? Use.TEST : Use.RUNTIME));
            }
            String deployment = extension + "-deployment";
            if (managed.containsKey("io.quarkus:" + deployment) || published.has(deployment)) {
                roots.putIfAbsent(deployment, new Root("io.quarkus", deployment, version,
                        tests ? Use.TEST : Use.BUILD));
            }
        }
        roots.putIfAbsent("quarkus-maven-plugin", new Root("io.quarkus", "quarkus-maven-plugin", version, Use.BUILD));
        for (String id : new TreeSet<>(managed.keySet())) {
            String artifactId = id.startsWith("io.quarkus:") ? id.substring("io.quarkus:".length()) : "";
            if (!artifactId.isEmpty() && !artifactId.contains(":") && forTests(artifactId)) {
                roots.putIfAbsent(artifactId, new Root("io.quarkus", artifactId, version, Use.TEST));
            }
        }
        return roots;
    }

    /// Whether applications use an artifact of Quarkus (or its deployment artifact) in tests only: the test frameworks
    /// (`quarkus-junit*`, `quarkus-test-*`, `quarkus-arquillian`), test helpers (`*-tests`) and the coverage
    /// extension, `quarkus-jacoco`. The extension catalog lists several of them, with no category that says so.
    static boolean forTests(String artifactId) {
        String base = artifactId.endsWith("-deployment")
                ? artifactId.substring(0, artifactId.length() - "-deployment".length())
                : artifactId;
        return base.startsWith("quarkus-junit") || base.startsWith("quarkus-test-") || base.endsWith("-tests")
                || base.equals("quarkus-jacoco") || base.equals("quarkus-arquillian");
    }
}
