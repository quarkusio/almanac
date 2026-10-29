package io.quarkiverse.almanac.assemble;

import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.model.ApiSnapshot;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ClassesSnapshot;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.SnapshotStore;

import java.io.IOException;
import java.util.Collection;

/// Where [HistoryBuilder#assemble(ReleaseSource, Collection, java.nio.file.Path, java.time.Instant)] reads the
/// snapshots of the releases it publishes: the committed dataset (`io.quarkiverse.almanac.dataset.DatasetReleaseSource`, the
/// default of the `assemble` command) or a snapshot store ([#of(SnapshotStore)]).
public interface ReleaseSource {

    /// The releases that have a BOM snapshot, in any order.
    Collection<String> bomVersions() throws IOException;

    /// Whether the release has a config snapshot; used only to warn about one without a BOM snapshot.
    boolean hasConfig(String version) throws IOException;

    /// The snapshots of a release listed by [#bomVersions()]: its BOM, its extensions and classes when it has them,
    /// its config when it has config and extensions (config without extensions is ignored, except for a 0.x release,
    /// which has no extension catalog), its platform BOM when it has one, and its API when it has the API and the
    /// classes.
    HistoryBuilder.Release load(String version) throws IOException;

    /// The snapshots of a store (its work folder, then its dataset fallback, as [SnapshotStore#read] finds them).
    static ReleaseSource of(SnapshotStore store) {
        return new ReleaseSource() {
            @Override
            public Collection<String> bomVersions() throws IOException {
                return store.versionsWith(SnapshotStore.BOM);
            }

            @Override
            public boolean hasConfig(String version) {
                return store.exists(version, SnapshotStore.CONFIG);
            }

            @Override
            public HistoryBuilder.Release load(String version) throws IOException {
                BomSnapshot bom = store.read(version, SnapshotStore.BOM, BomSnapshot.class);
                ClassesSnapshot classes = store.exists(version, SnapshotStore.CLASSES)
                        ? store.read(version, SnapshotStore.CLASSES, ClassesSnapshot.class) : null;
                BomSnapshot platform = store.exists(version, SnapshotStore.PLATFORM_BOM)
                        ? store.read(version, SnapshotStore.PLATFORM_BOM, BomSnapshot.class) : null;
                ApiSnapshot api = classes != null && store.exists(version, SnapshotStore.API)
                        ? store.read(version, SnapshotStore.API, ApiSnapshot.class) : null;
                boolean hasConfig = store.exists(version, SnapshotStore.CONFIG);
                if (!store.exists(version, SnapshotStore.EXTENSIONS)) {
                    if (hasConfig && !QuarkusVersion.parse(version).hasExtensionCatalog()) {
                        // a 0.x release has no extension catalog: its config comes alone
                        return new HistoryBuilder.Release(bom, store.read(version, SnapshotStore.CONFIG,
                                ConfigSnapshot.class), null, classes, platform, api);
                    }
                    if (hasConfig) {
                        Log.warn("assemble: %s has config.json but no extensions.json; its config is ignored",
                                version);
                    }
                    return new HistoryBuilder.Release(bom, null, null, classes, platform, api);
                }
                return new HistoryBuilder.Release(bom,
                        hasConfig ? store.read(version, SnapshotStore.CONFIG, ConfigSnapshot.class) : null,
                        store.read(version, SnapshotStore.EXTENSIONS, ExtensionsSnapshot.class), classes, platform,
                        api);
            }
        };
    }
}
