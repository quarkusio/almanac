package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.dataset.Dataset;
import io.quarkiverse.almanac.maven.BomSnapshots;
import io.quarkiverse.almanac.maven.GitReleases;
import io.quarkiverse.almanac.maven.Http;
import io.quarkiverse.almanac.maven.MavenRepo;
import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.maven.RemoteJar;
import io.quarkiverse.almanac.maven.VersionSelector;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.SnapshotStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/// What a command works with: the repository (cached under `work/cache`), `work/snapshots` and the committed dataset
/// (`--dataset`), which snapshot reads fall back to.
final class Workspace implements AutoCloseable {

    private final Http http;
    private final MavenRepo repo;
    private final Path work;
    private final Path datasetDir;
    private final SnapshotStore snapshots;
    private final BomSnapshots boms;
    private final BomSnapshots platformBoms;
    private final QuarkusVersion since;
    private final VersionSelector.PreReleases preReleases;
    private final long startNanos = System.nanoTime();

    private Workspace(CommonOptions options, RemoteJar.Options jarOptions) {
        Log.setVerbose(options.verbose);
        this.since = QuarkusVersion.parse(options.since);
        this.preReleases = options.preReleases;
        this.work = options.work;
        this.datasetDir = options.dataset;
        this.http = new Http(options.concurrency, options.offline, options.pacer(cacheDir()));
        this.repo = new MavenRepo(options.repo, cacheDir(), http, jarOptions);
        this.snapshots = new SnapshotStore(work.resolve("snapshots"), datasetDir);
        this.boms = new BomSnapshots(repo, snapshots);
        this.platformBoms = BomSnapshots.platform(repo, snapshots);
    }

    static Workspace open(CommonOptions options) {
        return new Workspace(options, RemoteJar.Options.DEFAULT);
    }

    static Workspace open(CommonOptions options, RemoteJar.Options jarOptions) {
        return new Workspace(options, jarOptions);
    }

    MavenRepo repo() {
        return repo;
    }

    /// `work/snapshots`, falling back to the dataset for reads.
    SnapshotStore snapshots() {
        return snapshots;
    }

    /// `work/snapshots` alone, without the dataset fallback.
    SnapshotStore workSnapshots() {
        return new SnapshotStore(work.resolve("snapshots"), null);
    }

    /// The HTTP cache folder, `work/cache`.
    Path cacheDir() {
        return work.resolve("cache");
    }

    /// The committed dataset folder (`--dataset`).
    Path datasetDir() {
        return datasetDir;
    }

    /// The committed dataset as this command found it when first used (shared with the snapshot reads).
    Dataset dataset() throws IOException {
        return snapshots.dataset();
    }

    BomSnapshots boms() {
        return boms;
    }

    /// The `platform-bom.json` snapshots, of `io.quarkus.platform:quarkus-bom`.
    BomSnapshots platformBoms() {
        return platformBoms;
    }

    int concurrency() {
        return http.concurrency();
    }

    /// Resolves `all`, `streams` or a comma-separated version list against the published BOMs (and the dataset's
    /// release list), with the `--since` and `--pre-releases` options.
    List<QuarkusVersion> select(String selection) throws IOException {
        return boms.selector().select(selection, since, preReleases);
    }

    /// The releases that `assemble` publishes: the `all` selection; when no release list can be read, the same
    /// selection among the given versions.
    List<QuarkusVersion> selectForSite(Collection<String> withBom) throws IOException {
        try {
            List<QuarkusVersion> all = select("all");
            // a release from its git tag is selected by name only until the dataset lists it: with a BOM in the work
            // folder alone (--include-work), it is part of the site too
            List<String> git = withBom.stream().filter(version -> GitReleases.source(version).isPresent()).toList();
            if (git.isEmpty()) {
                return all;
            }
            TreeSet<QuarkusVersion> union = new TreeSet<>(all);
            union.addAll(new VersionSelector(git).all(since, preReleases));
            return List.copyOf(union);
        } catch (IOException e) {
            Log.warn("assemble: cannot read the release list (%s); selecting among the releases with BOM data",
                    e.getMessage());
            return new VersionSelector(withBom).all(since, preReleases);
        }
    }

    /// The releases among `versions` that were published on Maven Central. A release whose BOM comes from its git tag
    /// ([GitReleases]) has no jar and no extension catalog to read, so `label` (config, classes, extensions) leaves it
    /// out, saying so, instead of asking the repository for artifacts that never were in it.
    static List<QuarkusVersion> published(List<QuarkusVersion> versions, String label) {
        List<QuarkusVersion> published = versions.stream()
                .filter(version -> GitReleases.source(version.text()).isEmpty())
                .toList();
        int left = versions.size() - published.size();
        if (left > 0) {
            Log.info("%s: %d of %d selected releases never published on Maven Central left out (the dataset keeps "
                    + "their BOM alone, from their git tags)", label, left, versions.size());
        }
        return published;
    }

    @Override
    public void close() {
        if (http.requestCount() > 0) {
            Log.info("HTTP: %d requests, %.1f MB received, %.1f s elapsed (%.1f s of it pausing)",
                    http.requestCount(), http.bytesReceived() / 1e6, (System.nanoTime() - startNanos) / 1e9,
                    http.pacer().paused().toMillis() / 1e3);
        }
        http.close();
    }
}
