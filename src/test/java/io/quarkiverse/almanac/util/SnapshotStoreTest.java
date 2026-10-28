package io.quarkiverse.almanac.util;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.dataset.DatasetEditor;
import io.quarkiverse.almanac.dataset.Kind;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ConfigProperty;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.model.ExtensionInfo;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;
import org.junit.jupiter.api.Test;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Snapshot reads fall back to the committed dataset; writes stay in the work folder.
class SnapshotStoreTest {

    private static final BomSnapshot BOM = new BomSnapshot("3.40.1", "2026-09-29", List.of(
            new ManagedDep("io.quarkus:quarkus-core", "3.40.1"), new ManagedDep("io.vertx:vertx-core", "4.5.22")));
    private static final ConfigSnapshot CONFIG = new ConfigSnapshot("3.40.1", "model", "model-json/1", 1, List.of(),
            List.of(new ConfigProperty("quarkus.http.port", "quarkus.http.port", List.of(), "quarkus-vertx-http", "int",
                    false, false, false, "8080", "RUN_TIME", null, null, "The port.", "HttpConfig#port")));
    private static final ExtensionsSnapshot EXTENSIONS = new ExtensionsSnapshot("3.40.1", List.of(
            new ExtensionInfo("quarkus-vertx-http", "Vert.x HTTP", null, "stable", List.of("web"), null, false,
                    List.of("quarkus.http"), null)));

    @Test
    void readsFallBackToTheDatasetNextToTheWorkFolder() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            importIntoDataset(dir);
            // the default layout: <project>/work/snapshots reads <project>/dataset
            SnapshotStore store = new SnapshotStore(dir.resolve("work").resolve("snapshots"));
            assertEquals(dir.resolve("dataset"), store.datasetDir().orElseThrow());
            assertTrue(store.exists("3.40.1", SnapshotStore.BOM));
            assertTrue(store.inDataset("3.40.1", SnapshotStore.CONFIG));
            assertFalse(store.inWork("3.40.1", SnapshotStore.CONFIG));
            assertFalse(store.exists("3.40.1", SnapshotStore.CONFIG_MODEL), "the dataset keeps no config-model.json");
            assertEquals(BOM, store.read("3.40.1", SnapshotStore.BOM, BomSnapshot.class));
            assertEquals(CONFIG, store.read("3.40.1", SnapshotStore.CONFIG, ConfigSnapshot.class));
            assertEquals(EXTENSIONS, store.read("3.40.1", SnapshotStore.EXTENSIONS, ExtensionsSnapshot.class));
            assertEquals("8080", store.read("3.40.1", SnapshotStore.CONFIG, JsonNode.class)
                    .at("/properties/0/default").asText());
            assertEquals(List.of("3.40.1"), store.versionsWith(SnapshotStore.BOM));
            assertEquals(List.of("3.40.1"), store.knownReleases());
            assertThrows(NoSuchFileException.class, () -> store.read("3.40.0", SnapshotStore.BOM, BomSnapshot.class));

            // writes go to the work folder, and the work folder wins
            BomSnapshot local = new BomSnapshot("3.40.1", "2026-09-30", BOM.deps());
            store.write("3.40.1", SnapshotStore.BOM, local);
            assertTrue(store.inWork("3.40.1", SnapshotStore.BOM));
            assertEquals(local, store.read("3.40.1", SnapshotStore.BOM, BomSnapshot.class));
            store.write("3.40.0", SnapshotStore.BOM, new BomSnapshot("3.40.0", null, BOM.deps()));
            assertEquals(List.of("3.40.0", "3.40.1"), store.versionsWith(SnapshotStore.BOM));
        }
    }

    @Test
    void otherFoldersHaveNoFallbackUnlessGivenOne() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            importIntoDataset(dir);
            SnapshotStore scratch = new SnapshotStore(dir.resolve("elsewhere").resolve("snapshots"));
            assertTrue(scratch.datasetDir().isEmpty());
            assertFalse(scratch.exists("3.40.1", SnapshotStore.BOM));
            assertEquals(List.of(), scratch.versionsWith(SnapshotStore.BOM));
            assertEquals(List.of(), scratch.knownReleases());

            SnapshotStore explicit = new SnapshotStore(dir.resolve("elsewhere").resolve("snapshots"),
                    dir.resolve("dataset"));
            assertEquals(BOM, explicit.read("3.40.1", SnapshotStore.BOM, BomSnapshot.class));
            assertTrue(explicit.dataset().has("3.40.1", Kind.EXTENSIONS));
        }
    }

    /// Imports the snapshots into `<dir>/dataset` through a separate source folder.
    private static void importIntoDataset(ScratchDir dir) throws Exception {
        Path source = dir.resolve("source");
        SnapshotStore sourceStore = new SnapshotStore(source, null);
        sourceStore.write("3.40.1", SnapshotStore.BOM, BOM);
        sourceStore.write("3.40.1", SnapshotStore.CONFIG, CONFIG);
        sourceStore.write("3.40.1", SnapshotStore.EXTENSIONS, EXTENSIONS);
        List<DatasetEditor.Candidate> candidates = new ArrayList<>();
        for (Kind kind : List.of(Kind.BOM, Kind.CONFIG, Kind.EXTENSIONS)) {
            candidates.add(new DatasetEditor.Candidate("3.40.1", kind, sourceStore.file("3.40.1", kind.snapshotFile())));
        }
        DatasetEditor editor = DatasetEditor.open(dir.resolve("dataset"));
        assertEquals(List.of(), editor.importSnapshots(candidates, false).refusals());
        editor.write();
    }
}
