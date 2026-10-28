package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.assemble.HistoryBuilder;
import io.quarkiverse.almanac.dataset.Dataset;
import io.quarkiverse.almanac.dataset.DatasetReleaseSource;
import io.quarkiverse.almanac.dataset.Kind;
import io.quarkiverse.almanac.dataset.ReleaseOrder;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.SnapshotStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/// `assemble`: the committed dataset → `site/data/*.json`, for the releases of the `all` selection. Work snapshots are
/// used only with `--include-work`, for the releases and kinds the dataset lacks.
@Command(name = "assemble", description = "Build the site data (site/data/*.json) from the committed dataset, for the "
        + "releases in the all selection (see --since and --pre-releases). Needs no network.")
public final class AssembleCommand implements Callable<Integer> {

    @Mixin
    CommonOptions common;

    @Option(names = "--site", defaultValue = "site", paramLabel = "DIR",
            description = "Site folder; the data goes to its data/ subfolder (default: ${DEFAULT-VALUE}).")
    Path site;

    @Option(names = "--include-work", description = "Also use the work snapshots of releases (and kinds) the dataset "
            + "does not have. They are not validated: import them with dataset import instead to publish them.")
    boolean includeWork;

    @Override
    public Integer call() throws IOException {
        try (Workspace workspace = Workspace.open(common)) {
            assemble(workspace, site, includeWork);
        }
        return 0;
    }

    /// Writes the site data of the `all` selection: every selected release that has a BOM in the dataset (or, with
    /// `includeWork`, in the work folder).
    ///
    /// @throws IOException when there is nothing to publish (no dataset)
    static void assemble(Workspace workspace, Path site, boolean includeWork) throws IOException {
        // read afresh: an import earlier in the same run (all) may have changed it
        Dataset dataset = Dataset.open(workspace.datasetDir());
        SnapshotStore work = workspace.workSnapshots();
        DatasetReleaseSource source = new DatasetReleaseSource(dataset, includeWork ? work : null);
        Collection<String> withBom = source.bomVersions();
        if (withBom.isEmpty()) {
            throw new IOException("no BOM data to publish: the dataset " + workspace.datasetDir() + " is empty or "
                    + "missing (see --dataset; dataset import adds work snapshots, --include-work uses them as they are)");
        }
        if (!includeWork) {
            reportLeftOut(dataset, work);
        }
        HistoryBuilder.assemble(source, workspace.selectForSite(withBom), site.resolve("data"), Instant.now());
        if (includeWork) {
            Log.info("assemble: --include-work used %d BOM, %d config, %d extensions and %d classes snapshots from %s "
                    + "that the dataset does not have", source.workBoms(), source.workConfigs(),
                    source.workExtensions(), source.workClasses(), work.root());
        }
    }

    /// Logs the work snapshots that the dataset lacks and that this run therefore leaves out.
    private static void reportLeftOut(Dataset dataset, SnapshotStore work) throws IOException {
        for (Kind kind : Kind.values()) {
            List<String> missing = new ArrayList<>();
            for (String version : work.versionsWith(kind.snapshotFile())) {
                if (!dataset.has(version, kind)) {
                    missing.add(version);
                }
            }
            if (!missing.isEmpty()) {
                missing.sort(ReleaseOrder.COMPARATOR);
                Log.info("assemble: %d %s snapshots in %s are not in the dataset and are left out (%s); dataset import "
                        + "adds them, --include-work uses them as they are", missing.size(), kind.snapshotFile(),
                        work.root(), abbreviated(missing));
            }
        }
    }

    private static String abbreviated(List<String> names) {
        return names.stream().limit(5).collect(Collectors.joining(", ")) + (names.size() > 5 ? ", ..." : "");
    }
}
