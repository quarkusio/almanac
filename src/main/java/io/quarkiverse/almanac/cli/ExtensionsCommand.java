package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.dataset.Dataset;
import io.quarkiverse.almanac.dataset.Kind;
import io.quarkiverse.almanac.extensions.ExtensionCatalogs;
import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.SnapshotStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/// `extensions`: writes `extensions.json` snapshots from the extension catalogs of the releases whose extensions the
/// committed dataset does not have yet. No jar is read and no config snapshot is needed: one catalog per release.
@Command(name = "extensions", description = "Write extensions.json snapshots from the extension catalog published "
        + "with each release (one JSON file per release; no jar is read, no config needed), for the releases whose "
        + "extensions the dataset does not have yet. Catalogs exist from 0.26.0 on; they list no relocations.")
public final class ExtensionsCommand implements Callable<Integer> {

    @Mixin
    CommonOptions common;

    @Option(names = "--versions", defaultValue = "streams", paramLabel = "all|streams|LIST",
            description = "Releases to process (default: ${DEFAULT-VALUE}).")
    String versions;

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
            return write(workspace, target, selected, force) ? 0 : 1;
        }
    }

    /// The releases whose extensions the dataset does not have (all of them with `force`), logging how many it has. A
    /// release never published on Maven Central has no extension catalog to read, even with `force`.
    static List<QuarkusVersion> notInDataset(Workspace workspace, List<QuarkusVersion> versions, boolean force)
            throws IOException {
        versions = Workspace.published(versions, "extensions");
        if (force) {
            return versions;
        }
        Dataset dataset = workspace.dataset();
        List<QuarkusVersion> missing = versions.stream()
                .filter(version -> !dataset.has(version.text(), Kind.EXTENSIONS))
                .toList();
        int known = versions.size() - missing.size();
        if (known > 0) {
            Log.info("extensions: %d releases already in the dataset (of %d selected; --force processes them again)",
                    known, versions.size());
        }
        return missing;
    }

    /// Writes the snapshots of the given releases one after the other (one catalog request each), until requests stop
    /// ([ReleaseRun]); false when any release got no snapshot or was not attempted.
    static boolean write(Workspace workspace, SnapshotStore target, List<QuarkusVersion> versions, boolean force) {
        if (versions.isEmpty()) {
            return true;
        }
        Log.info("extensions: %d releases, from their extension catalogs into %s", versions.size(), target.root());
        ExtensionCatalogs catalogs = new ExtensionCatalogs(workspace.repo(), target);
        ReleaseRun run = new ReleaseRun("extensions", workspace.repo().http(), versions.size());
        List<String> withoutCatalog = new ArrayList<>();
        int failed = 0;
        for (QuarkusVersion version : versions) {
            if (!run.mayStart()) {
                continue;
            }
            try {
                if (!catalogs.write(version.text(), force)) {
                    withoutCatalog.add(version.text());
                }
            } catch (IOException e) {
                Log.error("extensions %s: %s", version, e.getMessage());
                run.failed(e);
                failed++;
            }
        }
        if (!withoutCatalog.isEmpty()) {
            Log.warn("extensions: %d releases have no extension catalog (%s)", withoutCatalog.size(),
                    String.join(", ", withoutCatalog));
        }
        if (failed > 0) {
            Log.error("extensions: %d of %d releases failed", failed, versions.size());
        }
        return run.finish() && failed == 0 && withoutCatalog.isEmpty();
    }
}
