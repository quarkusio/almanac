package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.classes.ClassesReader;
import io.quarkiverse.almanac.config.JarSource;
import io.quarkiverse.almanac.config.VersionJars;
import io.quarkiverse.almanac.dataset.Dataset;
import io.quarkiverse.almanac.dataset.Kind;
import io.quarkiverse.almanac.maven.Http;
import io.quarkiverse.almanac.maven.MavenRepo;
import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ClassesSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.Parallel;
import io.quarkiverse.almanac.util.SnapshotStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/// `classes`: writes `classes.json` snapshots from the central directories of the `io.quarkus` jars of the releases
/// whose classes the committed dataset does not have yet. `config` writes them too, from the reads it makes anyway;
/// this command serves the releases extracted before it did, from what the caches hold.
@Command(name = "classes", description = "Write classes.json snapshots: the classes, nested ones included, of every "
        + "io.quarkus jar that quarkus-bom manages, from the jars' central directories (config writes them too, from "
        + "the same reads), for the releases whose classes the dataset does not have yet. Offline, a release is "
        + "written only when the caches know every jar of it.")
public final class ClassesCommand implements Callable<Integer> {

    @Mixin
    CommonOptions common;

    @Option(names = "--versions", defaultValue = "all", paramLabel = "all|streams|LIST",
            description = "Releases to process (default: ${DEFAULT-VALUE}).")
    String versions;

    @Option(names = "--cache", paramLabel = "DIR",
            description = "Another HTTP cache to read jars from, such as the cache of another --work folder; may be "
                    + "repeated. What it has is only read; a jar that no cache has is fetched into the cache of this "
                    + "work folder, or, offline, leaves its release without a snapshot.")
    List<Path> caches = new ArrayList<>();

    @Option(names = "--force", description = "Also process releases the dataset has, and rewrite existing snapshots.")
    boolean force;

    @Option(names = "--out", paramLabel = "DIR",
            description = "Folder of the version folders to write (default: the work snapshots folder, "
                    + "<work>/snapshots, where dataset import reads them).")
    Path out;

    @Override
    public Integer call() throws IOException {
        try (Workspace workspace = Workspace.open(common)) {
            List<QuarkusVersion> selected = notInDataset(workspace, workspace.select(versions), force);
            SnapshotStore target = out != null ? new SnapshotStore(out, null) : workspace.workSnapshots();
            return write(workspace, target, caches, selected, force) ? 0 : 1;
        }
    }

    /// The releases whose classes the dataset does not have (all of them with `force`), logging how many it has. A
    /// release never published on Maven Central has no jars to read, even with `force`.
    static List<QuarkusVersion> notInDataset(Workspace workspace, List<QuarkusVersion> versions, boolean force)
            throws IOException {
        versions = Workspace.published(versions, "classes");
        if (force) {
            return versions;
        }
        Dataset dataset = workspace.dataset();
        List<QuarkusVersion> missing = versions.stream()
                .filter(version -> !dataset.has(version.text(), Kind.CLASSES))
                .toList();
        int known = versions.size() - missing.size();
        if (known > 0) {
            Log.info("classes: %d releases already in the dataset (of %d selected; --force processes them again)",
                    known, versions.size());
        }
        return missing;
    }

    /// Writes the snapshots of the given releases one after the other, until requests stop ([ReleaseRun]); false when
    /// any release got no snapshot (offline: one with a jar no cache knows) or was not attempted. A snapshot in
    /// `target` is reused unless `force`.
    ///
    /// @param caches other cache folders, read before the work folder's cache is used for what they lack
    static boolean write(Workspace workspace, SnapshotStore target, List<Path> caches, List<QuarkusVersion> versions,
            boolean force) {
        if (versions.isEmpty()) {
            return true;
        }
        MavenRepo repo = workspace.repo();
        List<MavenRepo> others = caches.stream().map(repo::withCache).toList();
        Log.info("classes: %d releases, from the central directories of their jars%s, into %s", versions.size(),
                caches.isEmpty() ? "" : " (cached here or in " + caches.stream().map(Path::toString)
                        .collect(Collectors.joining(", ")) + ")", target.root());
        ReleaseRun run = new ReleaseRun("classes", repo.http(), versions.size());
        List<String> uncached = new ArrayList<>();
        int failed = 0;
        for (QuarkusVersion version : versions) {
            if (!run.mayStart()) {
                continue;
            }
            String release = version.text();
            try {
                if (!force && target.inWork(release, SnapshotStore.CLASSES)) {
                    Log.info("classes %s: reusing existing snapshot", release);
                    continue;
                }
                if (!writeOne(workspace, target, others, release)) {
                    uncached.add(release);
                }
            } catch (IOException e) {
                Log.error("classes %s: %s", release, e.getMessage());
                run.failed(e);
                failed++;
            } catch (RuntimeException e) {
                // one broken release must not stop the others
                Log.error("classes %s: %s", release, e);
                run.failed(e);
                failed++;
            }
        }
        if (!uncached.isEmpty()) {
            Log.warn("classes: %d releases have jars that no cache knows, so they got no snapshot (%s)",
                    uncached.size(), String.join(", ", uncached));
        }
        if (failed > 0) {
            Log.error("classes: %d of %d releases failed", failed, versions.size());
        }
        return run.finish() && failed == 0 && uncached.isEmpty();
    }

    /// Writes the snapshot of one release; false, writing nothing, when offline and a jar is in no cache.
    private static boolean writeOne(Workspace workspace, SnapshotStore target, List<MavenRepo> others, String release)
            throws IOException {
        long start = System.nanoTime();
        MavenRepo repo = workspace.repo();
        BomSnapshot bom = workspace.boms().load(release, false);
        List<JarSource> jars = new ArrayList<>();
        List<String> notCached = new ArrayList<>();
        for (ManagedDep dep : bom.deps()) {
            if (!VersionJars.isQuarkusJar(dep)) {
                continue;
            }
            String artifactId = dep.id().substring(VersionJars.GROUP_ID.length() + 1);
            MavenRepo from = cacheOf(repo, others, artifactId, dep.v());
            if (from == null) {
                notCached.add(artifactId);
                from = repo;
            }
            jars.add(JarSource.of(from.jar(VersionJars.GROUP_ID, artifactId, dep.v())));
        }
        if (!notCached.isEmpty() && repo.http().offline()) {
            Log.warn("classes %s: %d of %d jars are in no cache (%s%s); offline, no snapshot is written", release,
                    notCached.size(), jars.size(), String.join(", ", notCached.subList(0, Math.min(3,
                            notCached.size()))), notCached.size() > 3 ? ", ..." : "");
            return false;
        }
        // reads (or fetches) every central directory in parallel; the reader then finds them in memory
        Parallel.map(jars, workspace.concurrency(), JarSource::exists);
        ClassesSnapshot snapshot = ClassesReader.read(release, jars);
        if (repo.http().stopped()) {
            throw new Http.StoppedException("requests stopped after " + repo.http().stopReason().orElse("?")
                    + ", so no snapshot of " + release + " is written");
        }
        target.write(release, SnapshotStore.CLASSES, snapshot);
        Log.info("classes %s: %d classes in %d of %d jars, %.1f s", release, ClassesReader.classCount(snapshot),
                snapshot.artifacts().size(), jars.size(), (System.nanoTime() - start) / 1e9);
        return true;
    }

    /// The first repository whose cache knows the jar: the work folder's, then the others in order; null for none.
    private static MavenRepo cacheOf(MavenRepo repo, List<MavenRepo> others, String artifactId, String version) {
        if (repo.hasCachedJar(VersionJars.GROUP_ID, artifactId, version)) {
            return repo;
        }
        for (MavenRepo other : others) {
            if (other.hasCachedJar(VersionJars.GROUP_ID, artifactId, version)) {
                return other;
            }
        }
        return null;
    }
}
