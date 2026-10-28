package io.quarkiverse.almanac.dataset;

import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.model.ArtifactClasses;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ClassesSnapshot;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.util.AtomicFiles;
import io.quarkiverse.almanac.util.SnapshotStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `dataset import`, `remove` and `refresh-releases` on scratch folders: what is imported, what is refused and why,
/// and that the written files never depend on the order of the changes.
class DatasetEditorTest {

    @Test
    void importsWorkSnapshotsAndWritesARestorableDataset() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            SnapshotStore work = work(dir);
            DatasetEditor editor = DatasetEditor.open(dir.resolve("dataset"));
            DatasetEditor.ImportReport report = editor.importSnapshots(all(work), false);
            assertEquals(List.of(), report.refusals());
            assertEquals(8, report.added().size());
            assertEquals(List.copyOf(DatasetFormat.FILES), editor.write());

            Dataset dataset = Dataset.open(dir.resolve("dataset"));
            assertEquals(Samples.BOM_320_CR1, dataset.bom("3.2.0.CR1"));
            assertEquals(Samples.CONFIG_320, dataset.config("3.2.0"));
            assertEquals(Samples.EXTENSIONS_310, dataset.extensions("3.1.0"));

            // importing the same snapshots again changes nothing
            DatasetEditor again = DatasetEditor.open(dir.resolve("dataset"));
            DatasetEditor.ImportReport repeat = again.importSnapshots(all(work), false);
            assertEquals(8, repeat.unchanged().size());
            assertEquals(List.of(), again.write());
        }
    }

    @Test
    void refusesInconsistentSnapshotsAndImportsTheOthers() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            SnapshotStore work = work(dir);
            // wrong version in the file
            work.write("3.0.0.Final", SnapshotStore.BOM, new BomSnapshot("3.0.0", "2023-04-26",
                    Samples.BOM_300.deps()));
            // unsorted managed dependencies
            List<ManagedDep> unsorted = new ArrayList<>(Samples.BOM_310.deps());
            unsorted.add(unsorted.removeFirst());
            work.write("3.1.0", SnapshotStore.BOM, new BomSnapshot("3.1.0", "2023-05-31", unsorted));
            // a field the record does not keep: importing would lose it
            Path cr1 = work.file("3.2.0.CR1", SnapshotStore.BOM);
            AtomicFiles.writeString(cr1, Files.readString(cr1).replaceFirst("\\{", "{\n  \"extra\": 1,"));

            DatasetEditor editor = DatasetEditor.open(dir.resolve("dataset"));
            DatasetEditor.ImportReport report = editor.importSnapshots(all(work), false);
            assertEquals(Map.of("bom 3.0.0.Final", "inconsistent: its version is 3.0.0, not 3.0.0.Final",
                            "bom 3.1.0", "inconsistent: managed dependency io.netty:netty-common is not sorted (after "
                                    + "io.vertx:vertx-core)",
                            "bom 3.2.0.CR1", "inconsistent: " + cr1 + " holds data its BomSnapshot record does not "
                                    + "keep (/extra: 1 vs (absent))",
                            // no BOM for 3.1.0 in the dataset or the import
                            "config 3.1.0", "config, extensions and classes need the release's BOM snapshot, which "
                                    + "is neither in the dataset nor in this import",
                            "extensions 3.1.0", "config, extensions and classes need the release's BOM snapshot, "
                                    + "which is neither in the dataset nor in this import"),
                    reasons(report));
            assertTrue(report.hasInconsistencies());
            assertEquals(List.of("bom 3.2.0", "config 3.2.0", "extensions 3.2.0"), report.added());
            editor.write();
            assertEquals(List.of("3.2.0"), Dataset.open(dir.resolve("dataset")).versionsWith(Kind.BOM));
        }
    }

    @Test
    void refusesUnvalidatedExtractorsButKeepsTheirExtensions() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            SnapshotStore work = work(dir);
            ConfigSnapshot bytecode = new ConfigSnapshot("3.1.0", "bytecode", "bytecode/1", 3, List.of(),
                    Samples.CONFIG_310.properties());
            work.write("3.1.0", SnapshotStore.CONFIG, bytecode);
            DatasetEditor editor = DatasetEditor.open(dir.resolve("dataset"));
            DatasetEditor.ImportReport report = editor.importSnapshots(all(work), false);

            String why = "extractor bytecode/1 is not validated; its snapshots stay out of the dataset and the site";
            assertEquals(Map.of("config 3.1.0", why), reasons(report));
            assertTrue(report.refusals().stream().allMatch(DatasetEditor.Refusal::unvalidated));
            assertFalse(report.hasInconsistencies());
            assertTrue(editor.has("3.1.0", Kind.BOM));
            assertFalse(editor.has("3.1.0", Kind.CONFIG));
            assertTrue(editor.has("3.1.0", Kind.EXTENSIONS), "extensions need no config");
        }
    }

    @Test
    void refusesBytecodeConfigWithoutAllItsDocAnnotations() throws Exception {
        for (String warning : List.of(
                "doc annotations unavailable: the lookup failed for all 646 types that declare config members",
                "doc annotations: no sources jar for io.quarkus:quarkus-agroal:3.1.0; the @ConfigDoc* annotations of "
                        + "its classes are unknown")) {
            try (ScratchDir dir = ScratchDir.create()) {
                SnapshotStore work = work(dir);
                work.write("3.1.0", SnapshotStore.CONFIG, new ConfigSnapshot("3.1.0", "bytecode", "bytecode/2", 3,
                        List.of("duplicate key quarkus.a: A#a and B#a", warning), Samples.CONFIG_310.properties()));
                DatasetEditor editor = DatasetEditor.open(dir.resolve("dataset"));
                DatasetEditor.ImportReport report = editor.importSnapshots(all(work), false);

                String why = reasons(report).get("config 3.1.0");
                assertTrue(why.startsWith("not all @ConfigDoc* annotations were read from the -sources jars ("
                        + warning.substring(0, 40)), why);
                assertTrue(report.refusals().stream().allMatch(DatasetEditor.Refusal::unvalidated));
                assertFalse(editor.has("3.1.0", Kind.CONFIG));
                assertTrue(editor.has("3.1.0", Kind.EXTENSIONS));
            }
        }
    }

    @Test
    void importsTheFinishedBytecodeReader() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            SnapshotStore work = work(dir);
            work.write("3.1.0", SnapshotStore.CONFIG, new ConfigSnapshot("3.1.0", "bytecode", "bytecode/2", 3,
                    List.of("duplicate key quarkus.a: A#a and B#a"), Samples.CONFIG_310.properties()));
            DatasetEditor editor = DatasetEditor.open(dir.resolve("dataset"));
            DatasetEditor.ImportReport report = editor.importSnapshots(all(work), false);

            assertEquals(Map.of(), reasons(report));
            assertTrue(editor.has("3.1.0", Kind.CONFIG));
            assertTrue(editor.has("3.1.0", Kind.EXTENSIONS));
        }
    }

    @Test
    void configNeedsItsExtensionsButExtensionsNeedNoConfig() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            SnapshotStore work = work(dir);
            DatasetEditor editor = DatasetEditor.open(dir.resolve("dataset"));
            DatasetEditor.ImportReport report = editor.importSnapshots(List.of(
                    candidate(work, "3.1.0", Kind.BOM), candidate(work, "3.1.0", Kind.CONFIG),
                    candidate(work, "3.2.0", Kind.BOM), candidate(work, "3.2.0", Kind.EXTENSIONS)), false);
            assertEquals(Map.of("config 3.1.0", "config needs the release's extensions snapshot, which is neither in "
                    + "the dataset nor in this import"), reasons(report));
            assertEquals(List.of("bom 3.1.0", "bom 3.2.0", "extensions 3.2.0"), report.added());

            // the extensions the dataset has are enough for the config of a later import
            DatasetEditor.ImportReport later = editor.importSnapshots(List.of(candidate(work, "3.2.0", Kind.CONFIG)),
                    false);
            assertEquals(List.of("config 3.2.0"), later.added());

            // config whose extensions are refused is refused with them
            work.write("3.1.0", SnapshotStore.EXTENSIONS, Samples.extensions("3.1.1", Samples.RESTEASY_REACTIVE));
            DatasetEditor.ImportReport refused = editor.importSnapshots(List.of(
                    candidate(work, "3.1.0", Kind.CONFIG), candidate(work, "3.1.0", Kind.EXTENSIONS)), false);
            String inconsistent = "inconsistent: its version is 3.1.1, not 3.1.0";
            assertEquals(Map.of("config 3.1.0", "its extensions snapshot is refused (" + inconsistent + ")",
                    "extensions 3.1.0", inconsistent), reasons(refused));

            // extensions need the release's BOM, like config
            work.write("3.0.0.Final", SnapshotStore.EXTENSIONS, Samples.extensions("3.0.0.Final", Samples.REST));
            DatasetEditor.ImportReport noBom = DatasetEditor.open(dir.resolve("other"))
                    .importSnapshots(List.of(candidate(work, "3.0.0.Final", Kind.EXTENSIONS)), false);
            assertEquals(Map.of("extensions 3.0.0.Final", "config, extensions and classes need the release's BOM "
                    + "snapshot, which is neither in the dataset nor in this import"), reasons(noBom));

            // a missing work file is refused too
            DatasetEditor.ImportReport missing = editor.importSnapshots(List.of(
                    candidate(work, "3.0.0.Final", Kind.CONFIG)), false);
            assertTrue(missing.refusals().getFirst().reason().startsWith("no work snapshot "),
                    missing.refusals().toString());
        }
    }

    @Test
    void removingExtensionsTakesTheConfigAlongButNotTheReverse() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            SnapshotStore work = work(dir);
            Path datasetDir = dir.resolve("dataset");
            DatasetEditor editor = DatasetEditor.open(datasetDir);
            editor.importSnapshots(all(work), false);
            editor.write();

            DatasetEditor removing = DatasetEditor.open(datasetDir);
            assertEquals(List.of("config 3.1.0"), removing.remove(List.of("3.1.0"), Set.of(Kind.CONFIG)));
            assertEquals(List.of("config 3.2.0", "extensions 3.2.0"),
                    removing.remove(List.of("3.2.0"), Set.of(Kind.EXTENSIONS)));
            removing.write();

            // 3.1.0 keeps its extensions without config: written, restored and verified like any release
            Dataset dataset = Dataset.open(datasetDir);
            assertEquals(Samples.EXTENSIONS_310, dataset.extensions("3.1.0"));
            assertFalse(dataset.has("3.1.0", Kind.CONFIG));
            assertEquals(List.of("3.1.0"), dataset.versionsWith(Kind.EXTENSIONS));
            assertTrue(Files.readString(datasetDir.resolve(DatasetFormat.RELEASES)).contains(
                    "{\"version\":\"3.1.0\",\"date\":\"2023-05-31\",\"bom\":true,\"extensions\":true}\n"));
            assertEquals(List.of(), DatasetVerifier.verify(datasetDir, null).problems());
        }
    }

    @Test
    void classesNeedTheBomOnlyAndGoWithIt() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            SnapshotStore work = work(dir);
            for (ClassesSnapshot classes : List.of(Samples.CLASSES_300, Samples.CLASSES_310, Samples.CLASSES_320)) {
                work.write(classes.version(), SnapshotStore.CLASSES, classes);
            }
            // a release without BOM, and classes that do not sort
            work.write("3.3.0", SnapshotStore.CLASSES, Samples.classes("3.3.0", "quarkus-core",
                    new String[] {"io.quarkus.runtime.Application"}));
            work.write("3.2.0.CR1", SnapshotStore.CLASSES, new ClassesSnapshot("3.2.0.CR1", List.of(
                    new ArtifactClasses("quarkus-core", List.of("io.quarkus.runtime.B", "io.quarkus.runtime.A")))));
            Path datasetDir = dir.resolve("dataset");
            DatasetEditor editor = DatasetEditor.open(datasetDir);
            DatasetEditor.ImportReport report = editor.importSnapshots(all(work), false);
            assertEquals(Map.of(
                    "classes 3.2.0.CR1", "inconsistent: class of quarkus-core io.quarkus.runtime.A is not sorted "
                            + "(after io.quarkus.runtime.B)",
                    "classes 3.3.0", "config, extensions and classes need the release's BOM snapshot, which is "
                            + "neither in the dataset nor in this import"), reasons(report));
            assertEquals(11, report.added().size());
            editor.write();

            // 3.0.0.Final has a BOM and classes, no config or extensions
            Dataset dataset = Dataset.open(datasetDir);
            assertEquals(List.of("3.0.0.Final", "3.1.0", "3.2.0"), dataset.versionsWith(Kind.CLASSES));
            assertEquals(Samples.CLASSES_300, dataset.classes("3.0.0.Final"));
            assertEquals(List.of(), DatasetVerifier.verify(datasetDir, work).problems());

            DatasetEditor removing = DatasetEditor.open(datasetDir);
            assertEquals(List.of("classes 3.1.0"), removing.remove(List.of("3.1.0"), Set.of(Kind.CLASSES)));
            assertEquals(List.of("bom 3.2.0", "config 3.2.0", "extensions 3.2.0", "classes 3.2.0"),
                    removing.remove(List.of("3.2.0"), Set.of(Kind.BOM)));
            removing.write();
            assertEquals(List.of("3.0.0.Final"), Dataset.open(datasetDir).versionsWith(Kind.CLASSES));
            assertEquals(List.of(), DatasetVerifier.verify(datasetDir, null).problems());

            // the file of a line without classes goes, and the classes folder with it
            Path classes3 = datasetDir.resolve(DatasetFormat.classesFile(3));
            assertTrue(Files.isRegularFile(classes3));
            DatasetEditor last = DatasetEditor.open(datasetDir);
            last.remove(List.of("3.0.0.Final"), Set.of(Kind.CLASSES));
            assertEquals(List.of(DatasetFormat.RELEASES, DatasetFormat.classesFile(3)), last.write());
            assertFalse(Files.exists(classes3));
            assertFalse(Files.exists(datasetDir.resolve(DatasetFormat.CLASSES_DIR)));
            assertEquals(List.of(), DatasetVerifier.verify(datasetDir, null).problems());
        }
    }

    @Test
    void eachMajorLineHasAClassesFileOfItsOwn() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            SnapshotStore work = work(dir);
            work.write("3.0.0.Final", SnapshotStore.CLASSES, Samples.CLASSES_300);
            Path datasetDir = dir.resolve("dataset");
            DatasetEditor editor = DatasetEditor.open(datasetDir);
            assertEquals(List.of(), editor.importSnapshots(all(work), false).refusals());
            editor.write();
            Map<String, String> before = read(datasetDir);
            String classes3 = Files.readString(datasetDir.resolve(DatasetFormat.classesFile(3)));

            // a release of the 4.x line adds a file and leaves the 3.x one as it is
            work.write("4.0.0.Alpha1", SnapshotStore.BOM, Samples.BOM_400_ALPHA1);
            work.write("4.0.0.Alpha1", SnapshotStore.CLASSES, Samples.CLASSES_400_ALPHA1);
            DatasetEditor adding = DatasetEditor.open(datasetDir);
            assertEquals(List.of(), adding.importSnapshots(all(work), false).refusals());
            assertEquals(List.of(DatasetFormat.RELEASES, DatasetFormat.BOM, DatasetFormat.classesFile(4)),
                    adding.write());
            assertEquals(classes3, Files.readString(datasetDir.resolve(DatasetFormat.classesFile(3))));
            assertEquals(Samples.CLASSES_400_ALPHA1, Dataset.open(datasetDir).classes("4.0.0.Alpha1"));
            assertEquals(List.of(), DatasetVerifier.verify(datasetDir, work).problems());

            // removing it again gives back exactly the files before
            DatasetEditor removing = DatasetEditor.open(datasetDir);
            removing.remove(List.of("4.0.0.Alpha1"), Set.of(Kind.BOM));
            removing.refreshReleases(List.of(), version -> null);
            removing.write();
            assertEquals(before, read(datasetDir));
            assertFalse(Files.exists(datasetDir.resolve(DatasetFormat.classesFile(4))));
        }
    }

    @Test
    void importsABomThatKeepsReferencesAsWrittenWithAWarning() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            SnapshotStore work = work(dir);
            // the classifier placeholder of an id is no reference
            BomSnapshot literal = Samples.bom("2.4.1.Final", "2021-11-03",
                    "io.netty:netty-tcnative", "${tcnative.version}",
                    "io.netty:netty-tcnative:linux-x86_64", "${tcnative.version}",
                    "io.quarkus:quarkus-bom-quarkus-platform-descriptor:json:${quarkus.version}", "2.4.1.Final",
                    "io.quarkus:quarkus-core", "2.4.1.Final");
            work.write("2.4.1.Final", SnapshotStore.BOM, literal);
            DatasetEditor editor = DatasetEditor.open(dir.resolve("dataset"));

            DatasetEditor.ImportReport report = editor.importSnapshots(all(work), false);

            assertEquals(List.of(), report.refusals());
            assertTrue(report.added().contains("bom 2.4.1.Final"), report.added().toString());
            assertEquals(List.of("bom 2.4.1.Final: 2 managed dependencies keep a ${...} reference as written (e.g. "
                    + "io.netty:netty-tcnative ${tcnative.version})"), report.warnings());
            editor.write();
            assertEquals(literal, Dataset.open(dir.resolve("dataset")).bom("2.4.1.Final"));

            // the same warning for a snapshot equal to the dataset's
            DatasetEditor.ImportReport again = DatasetEditor.open(dir.resolve("dataset"))
                    .importSnapshots(List.of(candidate(work, "2.4.1.Final", Kind.BOM)), false);
            assertEquals(List.of("bom 2.4.1.Final"), again.unchanged());
            assertEquals(report.warnings(), again.warnings());
        }
    }

    @Test
    void replacingADifferentSnapshotNeedsForce() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            SnapshotStore work = work(dir);
            DatasetEditor editor = DatasetEditor.open(dir.resolve("dataset"));
            editor.importSnapshots(all(work), false);
            editor.write();

            BomSnapshot changed = Samples.bom("3.0.0.Final", "2023-04-26", "io.quarkus:quarkus-core", "3.0.0.Final",
                    "io.vertx:vertx-core", "4.4.2");
            work.write("3.0.0.Final", SnapshotStore.BOM, changed);
            List<DatasetEditor.Candidate> candidate = List.of(candidate(work, "3.0.0.Final", Kind.BOM));

            DatasetEditor.ImportReport refused = DatasetEditor.open(dir.resolve("dataset"))
                    .importSnapshots(candidate, false);
            assertEquals(Map.of("bom 3.0.0.Final", "differs from the dataset's snapshot (/deps/1/v: \"4.4.1\" vs "
                    + "\"4.4.2\"); --force replaces it"), reasons(refused));

            DatasetEditor forced = DatasetEditor.open(dir.resolve("dataset"));
            assertEquals(List.of("bom 3.0.0.Final"), forced.importSnapshots(candidate, true).replaced());
            assertEquals(List.of(DatasetFormat.BOM), forced.write());
            assertEquals(changed, Dataset.open(dir.resolve("dataset")).bom("3.0.0.Final"));
        }
    }

    @Test
    void removingAndReimportingAReleaseGivesTheSameBytes() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            SnapshotStore work = work(dir);
            Path datasetDir = dir.resolve("dataset");
            DatasetEditor editor = DatasetEditor.open(datasetDir);
            editor.importSnapshots(all(work), false);
            editor.write();
            Map<String, String> original = read(datasetDir);

            DatasetEditor removing = DatasetEditor.open(datasetDir);
            // removing the BOM takes config and extensions with it
            assertEquals(List.of("bom 3.1.0", "config 3.1.0", "extensions 3.1.0"),
                    removing.remove(List.of("3.1.0"), Set.of(Kind.BOM)));
            removing.write();
            Map<String, String> without = read(datasetDir);
            assertTrue(without.get(DatasetFormat.RELEASES).contains("{\"version\":\"3.1.0\",\"date\":\"2023-05-31\"}\n"),
                    "the release stays known with its date");

            DatasetEditor adding = DatasetEditor.open(datasetDir);
            adding.importSnapshots(all(work).stream().filter(c -> c.version().equals("3.1.0")).toList(), false);
            adding.write();
            assertEquals(original, read(datasetDir));
            assertEquals(Map.of(DatasetFormat.RELEASES, "+1 -1", DatasetFormat.BOM, "+1 -1",
                    DatasetFormat.CONFIG, "+3 -2", DatasetFormat.CONFIG_STATES, "+2 -0",
                    DatasetFormat.CONFIG_META, "+1 -0", DatasetFormat.EXTENSIONS, "+1 -1",
                    DatasetFormat.EXTENSION_STATES, "+1 -0"), Samples.lineChanges(without, original));
        }
    }

    @Test
    void anInterruptedWriteLeavesAMarkerThatReadersRefuse() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            SnapshotStore work = work(dir);
            Path datasetDir = dir.resolve("dataset");
            Path marker = datasetDir.resolve(DatasetFormat.WRITE_MARKER);
            DatasetEditor editor = DatasetEditor.open(datasetDir);
            editor.importSnapshots(all(work).stream().filter(c -> !c.version().equals("3.1.0")).toList(), false);
            editor.write();
            assertFalse(Files.exists(marker), "a finished write removes its marker");
            Map<String, String> written = read(datasetDir);

            // another write started (or one was interrupted) after this editor opened the folder: nothing is written
            DatasetEditor late = DatasetEditor.open(datasetDir);
            late.importSnapshots(all(work).stream().filter(c -> c.version().equals("3.1.0")).toList(), false);
            AtomicFiles.writeString(marker, DatasetFormat.writeMarker(List.of(DatasetFormat.RELEASES)));
            IOException refused = assertThrows(IOException.class, late::write);
            assertTrue(refused.getMessage().startsWith("nothing written: " + marker + " exists"), refused.getMessage());
            assertEquals(written, read(datasetDir));

            // readers and editors refuse the folder while the marker is there
            IOException reading = assertThrows(IOException.class, () -> Dataset.open(datasetDir));
            assertEquals(DatasetFormat.interruptedWrite(marker), reading.getMessage());
            assertThrows(IOException.class, () -> DatasetEditor.open(datasetDir));
            SnapshotStore store = new SnapshotStore(dir.resolve("other").resolve("snapshots"), datasetDir);
            assertThrows(IOException.class, () -> store.read("3.0.0.Final", SnapshotStore.BOM, BomSnapshot.class));

            Files.delete(marker);
            assertEquals(Samples.BOM_300, Dataset.open(datasetDir).bom("3.0.0.Final"));
        }
    }

    @Test
    void aWriteSinceOpeningIsNeverOverwritten() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            SnapshotStore work = work(dir);
            Path datasetDir = dir.resolve("dataset");
            DatasetEditor first = DatasetEditor.open(datasetDir);
            first.importSnapshots(all(work).stream().filter(c -> c.kind() == Kind.BOM).toList(), false);
            first.write();

            // two commands open the dataset; the one that writes second would drop the first one's import
            DatasetEditor a = DatasetEditor.open(datasetDir);
            DatasetEditor b = DatasetEditor.open(datasetDir);
            a.importSnapshots(all(work).stream().filter(c -> c.version().equals("3.1.0")).toList(), false);
            b.importSnapshots(all(work).stream().filter(c -> c.version().equals("3.2.0")).toList(), false);
            a.write();
            Map<String, String> afterA = read(datasetDir);
            IOException stale = assertThrows(IOException.class, b::write);
            assertTrue(stale.getMessage().startsWith("nothing written: ") && stale.getMessage().contains(
                    "changed after this command read the dataset"), stale.getMessage());
            assertEquals(afterA, read(datasetDir));
            assertFalse(Files.exists(datasetDir.resolve(DatasetFormat.WRITE_MARKER)));
            assertTrue(Dataset.open(datasetDir).has("3.1.0", Kind.CONFIG));

            // run again, it sees both
            DatasetEditor again = DatasetEditor.open(datasetDir);
            again.importSnapshots(all(work).stream().filter(c -> c.version().equals("3.2.0")).toList(), false);
            again.write();
            Dataset both = Dataset.open(datasetDir);
            assertTrue(both.has("3.1.0", Kind.CONFIG) && both.has("3.2.0", Kind.CONFIG));
        }
    }

    @Test
    void refreshingReleasesKeepsReleasesWithDataAndDatesTheOthers() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            SnapshotStore work = work(dir);
            DatasetEditor editor = DatasetEditor.open(dir.resolve("dataset"));
            editor.importSnapshots(all(work), false);
            editor.refreshReleases(List.of("2.16.11.Final"), version -> null);
            DatasetEditor.RefreshReport report = editor.refreshReleases(
                    List.of("3.0.0.Final", "3.3.0", "3.2.0", "3.0.1.Final"),
                    version -> version.equals("3.3.0") ? "2023-08-23" : null);
            assertEquals(List.of("3.0.1.Final", "3.3.0"), report.added());
            assertEquals(List.of("2.16.11.Final"), report.dropped());
            assertEquals(1, report.dated());
            assertEquals(6, report.known());
            editor.write();
            assertEquals(List.of("3.0.0.Final", "3.0.1.Final", "3.1.0", "3.2.0.CR1", "3.2.0", "3.3.0"),
                    Dataset.open(dir.resolve("dataset")).releases().stream().map(Dataset.Release::version).toList());
            assertTrue(Files.readString(dir.resolve("dataset").resolve(DatasetFormat.RELEASES))
                    .contains("{\"version\":\"3.3.0\",\"date\":\"2023-08-23\"}\n"));
        }
    }

    /// A scratch work folder with the sample snapshots.
    private static SnapshotStore work(ScratchDir dir) throws IOException {
        SnapshotStore work = new SnapshotStore(dir.resolve("work").resolve("snapshots"), null);
        Samples.writeWork(work);
        return work;
    }

    private static List<DatasetEditor.Candidate> all(SnapshotStore work) throws IOException {
        List<DatasetEditor.Candidate> candidates = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            for (String version : work.versionsWith(kind.snapshotFile())) {
                candidates.add(candidate(work, version, kind));
            }
        }
        return candidates;
    }

    private static DatasetEditor.Candidate candidate(SnapshotStore work, String version, Kind kind) {
        return new DatasetEditor.Candidate(version, kind, work.file(version, kind.snapshotFile()));
    }

    private static Map<String, String> reasons(DatasetEditor.ImportReport report) {
        Map<String, String> reasons = new TreeMap<>();
        report.refusals().forEach(refusal -> reasons.put(refusal.kind() + " " + refusal.version(), refusal.reason()));
        return reasons;
    }

    private static Map<String, String> read(Path datasetDir) throws IOException {
        Map<String, String> files = new TreeMap<>();
        for (String name : DatasetFormat.FILES) {
            files.put(name, Files.readString(datasetDir.resolve(name)));
        }
        return files;
    }
}
