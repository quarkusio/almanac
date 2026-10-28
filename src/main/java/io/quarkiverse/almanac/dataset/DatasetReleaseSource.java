package io.quarkiverse.almanac.dataset;

import io.quarkiverse.almanac.assemble.HistoryBuilder;
import io.quarkiverse.almanac.assemble.ReleaseSource;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.SnapshotStore;

import java.io.IOException;
import java.util.Collection;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/// What `assemble` publishes: the releases of the committed dataset and, only when asked for
/// (`--include-work`), the work snapshots of the releases and kinds the dataset lacks. Work snapshots are not validated,
/// so by default none reaches the site.
public final class DatasetReleaseSource implements ReleaseSource {

    private final Dataset dataset;
    private final SnapshotStore work;
    private final AtomicInteger workBoms = new AtomicInteger();
    private final AtomicInteger workConfigs = new AtomicInteger();
    private final AtomicInteger workExtensions = new AtomicInteger();
    private final AtomicInteger workPlatforms = new AtomicInteger();

    /// @param dataset the committed dataset
    /// @param work    work snapshots to add where the dataset has none (a store without dataset fallback), or null
    ///                for the dataset alone
    public DatasetReleaseSource(Dataset dataset, SnapshotStore work) {
        this.dataset = dataset;
        this.work = work;
    }

    @Override
    public Collection<String> bomVersions() throws IOException {
        SortedSet<String> versions = new TreeSet<>(ReleaseOrder.COMPARATOR);
        versions.addAll(dataset.versionsWith(Kind.BOM));
        if (work != null) {
            versions.addAll(work.versionsWith(SnapshotStore.BOM));
        }
        return versions;
    }

    @Override
    public boolean hasConfig(String version) {
        return dataset.has(version, Kind.CONFIG) || (work != null && work.inWork(version, SnapshotStore.CONFIG));
    }

    @Override
    public HistoryBuilder.Release load(String version) throws IOException {
        BomSnapshot bom;
        if (dataset.has(version, Kind.BOM)) {
            bom = dataset.bom(version);
        } else {
            bom = work.read(version, SnapshotStore.BOM, BomSnapshot.class);
            workBoms.incrementAndGet();
        }
        BomSnapshot platform = null;
        if (dataset.has(version, Kind.PLATFORM)) {
            platform = dataset.platform(version);
        } else if (work != null && work.inWork(version, SnapshotStore.PLATFORM_BOM)) {
            platform = work.read(version, SnapshotStore.PLATFORM_BOM, BomSnapshot.class);
            workPlatforms.incrementAndGet();
        }
        ExtensionsSnapshot extensions = null;
        if (dataset.has(version, Kind.EXTENSIONS)) {
            extensions = dataset.extensions(version);
        } else if (work != null && work.inWork(version, SnapshotStore.EXTENSIONS)) {
            extensions = work.read(version, SnapshotStore.EXTENSIONS, ExtensionsSnapshot.class);
            workExtensions.incrementAndGet();
        }
        boolean datasetConfig = dataset.has(version, Kind.CONFIG);
        boolean workConfig = !datasetConfig && work != null && work.inWork(version, SnapshotStore.CONFIG);
        if (!datasetConfig && !workConfig) {
            return new HistoryBuilder.Release(bom, null, extensions, platform);
        }
        if (extensions == null) {
            Log.warn("assemble: %s has config but no extensions snapshot (%s); its config is ignored", version,
                    datasetConfig ? "dataset verify reports it" : "in " + work.root());
            return new HistoryBuilder.Release(bom, null, null, platform);
        }
        if (datasetConfig) {
            return new HistoryBuilder.Release(bom, dataset.config(version), extensions, platform);
        }
        workConfigs.incrementAndGet();
        return new HistoryBuilder.Release(bom, work.read(version, SnapshotStore.CONFIG, ConfigSnapshot.class),
                extensions, platform);
    }

    /// How many BOM snapshots came from the work folder.
    public int workBoms() {
        return workBoms.get();
    }

    /// How many config snapshots came from the work folder.
    public int workConfigs() {
        return workConfigs.get();
    }

    /// How many extensions snapshots came from the work folder.
    public int workExtensions() {
        return workExtensions.get();
    }

    /// How many platform BOM snapshots came from the work folder.
    public int workPlatforms() {
        return workPlatforms.get();
    }
}
