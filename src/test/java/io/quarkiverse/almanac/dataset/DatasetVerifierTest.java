package io.quarkiverse.almanac.dataset;

import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.util.AtomicFiles;
import io.quarkiverse.almanac.util.SnapshotStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `dataset verify`: a sound dataset passes, and each kind of damage is named.
class DatasetVerifierTest {

    @Test
    void aWrittenDatasetIsSoundAndMatchesItsWorkSnapshots() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path datasetDir = write(dir);
            SnapshotStore work = new SnapshotStore(dir.resolve("work/snapshots"), null);
            Samples.writeWork(work);
            DatasetVerifier.Report report = DatasetVerifier.verify(datasetDir, work);
            assertEquals(List.of(), report.problems());
            assertTrue(report.summary().contains("5 known releases: 4 with bom, 2 with config, 2 with extensions, "
                    + "0 with classes"),
                    report.summary().toString());
            assertTrue(report.summary().contains("compared with " + work.root() + ": bom 4 (0 different) config 2 "
                    + "(0 different) extensions 2 (0 different) classes 0 (0 different)"), report.summary().toString());

            // a work snapshot that differs from the dataset's is reported
            work.write("3.0.0.Final", SnapshotStore.BOM, new BomSnapshot("3.0.0.Final", "2023-04-27",
                    Samples.BOM_300.deps()));
            DatasetVerifier.Report different = DatasetVerifier.verify(datasetDir, work);
            assertEquals(List.of("work: bom 3.0.0.Final differs from " + work.file("3.0.0.Final", SnapshotStore.BOM)
                    + ": /date: \"2023-04-26\" vs \"2023-04-27\""), different.problems());
        }
    }

    @Test
    void namesMissingAndUnreferencedStates() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path datasetDir = write(dir);
            Path states = datasetDir.resolve(DatasetFormat.EXTENSION_STATES);
            List<String> lines = DatasetFormat.lines(Files.readString(states));
            String removed = lines.getFirst();
            String removedId = removed.substring(7, 23);
            String extra = "{\"id\":\"ffffffffffffffff\",\"state\":{\"id\":\"quarkus-x\",\"unlisted\":false}}";
            AtomicFiles.writeString(states, String.join("\n", lines.subList(1, lines.size())) + "\n" + extra + "\n");

            List<String> problems = DatasetVerifier.verify(datasetDir, null).problems();
            assertTrue(problems.contains(DatasetFormat.EXTENSION_STATES + ": state " + removedId
                    + " is referenced but missing from " + DatasetFormat.EXTENSION_STATES), problems.toString());
            assertTrue(problems.contains(DatasetFormat.EXTENSION_STATES + ": state ffffffffffffffff in "
                    + DatasetFormat.EXTENSION_STATES + " is never referenced"), problems.toString());
            assertTrue(problems.stream().anyMatch(p -> p.startsWith(DatasetFormat.EXTENSION_STATES + ": "
                    + DatasetFormat.EXTENSION_STATES + ":3: id ffffffffffffffff is not the hash of its state")),
                    problems.toString());
            // decoding stops at the first problem: the added line, whose state does not hash to its id
            assertTrue(problems.stream().anyMatch(p -> p.startsWith("decode: ")
                    && p.contains("state ffffffffffffffff does not hash to its id")), problems.toString());

            // without the added line, decoding names the missing state
            AtomicFiles.writeString(states, String.join("\n", lines.subList(1, lines.size())) + "\n");
            assertTrue(DatasetVerifier.verify(datasetDir, null).problems().stream().anyMatch(p -> p.startsWith(
                    "decode: ") && p.contains("no state " + removedId + ", which " + DatasetFormat.EXTENSIONS
                    + " refers to")), DatasetVerifier.verify(datasetDir, null).problems().toString());
        }
    }

    @Test
    void namesLinesThatAreNotExactlyOneJsonObject() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path datasetDir = write(dir);
            Path bom = datasetDir.resolve(DatasetFormat.BOM);
            List<String> lines = DatasetFormat.lines(Files.readString(bom));
            // a lost newline joins lines 1 and 2; line 3 repeats its history field (a lenient parser keeps the last)
            String repeated = lines.get(2).substring(0, lines.get(2).length() - 1)
                    + ",\"h\":[[\"3.0.0.Final\",\"1\"]]}";
            AtomicFiles.writeString(bom, lines.get(0) + lines.get(1) + "\n" + repeated + "\n" + lines.get(3) + "\n");

            List<String> problems = DatasetVerifier.verify(datasetDir, null).problems();
            assertTrue(problems.stream().anyMatch(p -> p.startsWith(DatasetFormat.BOM + ": " + DatasetFormat.BOM
                    + ":1: not JSON: Trailing token")), problems.toString());
            assertTrue(problems.stream().anyMatch(p -> p.startsWith(DatasetFormat.BOM + ": " + DatasetFormat.BOM
                    + ":2: not JSON: Duplicate field 'h'")), problems.toString());
            assertTrue(problems.stream().anyMatch(p -> p.startsWith("decode: ")), problems.toString());
        }
    }

    @Test
    void namesAnInterruptedWrite() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path datasetDir = write(dir);
            Path marker = datasetDir.resolve(DatasetFormat.WRITE_MARKER);
            AtomicFiles.writeString(marker, DatasetFormat.writeMarker(List.of(DatasetFormat.RELEASES)));

            DatasetVerifier.Report report = DatasetVerifier.verify(datasetDir, null);
            assertEquals(List.of("files: " + DatasetFormat.interruptedWrite(marker)), report.problems());
            // the files themselves are still checked: here they are sound
            assertTrue(report.summary().stream().anyMatch(line -> line.startsWith("round trip: 8 of 8 files")),
                    report.summary().toString());
        }
    }

    @Test
    void namesNonCanonicalHistoriesAndOrder() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path datasetDir = write(dir);
            Path bom = datasetDir.resolve(DatasetFormat.BOM);
            // a repeated value: valid to decode, but not the canonical (minimal) encoding
            AtomicFiles.writeString(bom, Files.readString(bom).replace("[[\"3.0.0.Final\",\"=\"]]",
                    "[[\"3.0.0.Final\",\"=\"],[\"3.2.0\",\"=\"]]"));
            Path releases = datasetDir.resolve(DatasetFormat.RELEASES);
            List<String> rows = DatasetFormat.lines(Files.readString(releases));
            AtomicFiles.writeString(releases, String.join("\n", rows.get(1), rows.get(0), rows.get(2), rows.get(3),
                    rows.get(4)) + "\n");

            List<String> problems = DatasetVerifier.verify(datasetDir, null).problems();
            assertTrue(problems.contains(DatasetFormat.BOM + ": " + DatasetFormat.BOM + ":2: io.quarkus:quarkus-core "
                    + "repeats its value at 3.2.0 (only changes are recorded)"), problems.toString());
            assertTrue(problems.contains("releases: " + DatasetFormat.RELEASES + ":2: 2.16.12.Final is not after "
                    + "3.0.0.Final (lines are in release order, each release once)"), problems.toString());
            assertTrue(problems.stream().anyMatch(p -> p.startsWith("round trip: " + DatasetFormat.BOM + " is not "
                    + "what encoding its restored contents gives: line 2")), problems.toString());
        }
    }

    @Test
    void namesCrlfAndReleaseLinesThatDisagreeWithConfigMeta() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path datasetDir = write(dir);
            Path meta = datasetDir.resolve(DatasetFormat.CONFIG_META);
            AtomicFiles.writeString(meta, Files.readString(meta).replace("\n", "\r\n"));
            Path releases = datasetDir.resolve(DatasetFormat.RELEASES);
            AtomicFiles.writeString(releases, Files.readString(releases).replace(
                    "\"roots\":3},\"extensions\":true}\n{\"version\":\"3.2.0.CR1\"",
                    "\"roots\":4},\"extensions\":true}\n{\"version\":\"3.2.0.CR1\""));

            List<String> problems = DatasetVerifier.verify(datasetDir, null).problems();
            assertTrue(problems.contains("files: " + DatasetFormat.CONFIG_META + " has CRLF line ends (a checkout with "
                    + "core.autocrlf?): the dataset is LF only, see .gitattributes"), problems.toString());
            assertTrue(problems.contains("releases: 3.1.0: releases.jsonl says config ConfigInfo[source=model, "
                    + "extractor=model-json/1, roots=4], config-meta.jsonl says source=model, extractor=model-json/1, "
                    + "roots=3"), problems.toString());
        }
    }

    @Test
    void namesConfigWithoutExtensionsOrBom() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Contents contents = Samples.contents();
            contents.remove("3.1.0", Kind.EXTENSIONS);
            contents.remove("3.2.0", Kind.BOM);
            Path datasetDir = dir.resolve("dataset");
            writeFiles(datasetDir, contents);
            assertEquals(List.of("releases: 3.1.0 has config without extensions", "releases: 3.2.0 has config but no "
                    + "BOM"), DatasetVerifier.verify(datasetDir, null).problems());
        }
    }

    @Test
    void extensionsNeedNoConfigButTheBom() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Contents contents = Samples.contents();
            // extensions without config, as read from an extension catalog
            contents.put("3.0.0.Final", Kind.EXTENSIONS, Samples.extensions("3.0.0.Final", Samples.RESTEASY_REACTIVE));
            Path datasetDir = dir.resolve("dataset");
            writeFiles(datasetDir, contents);
            DatasetVerifier.Report report = DatasetVerifier.verify(datasetDir, null);
            assertEquals(List.of(), report.problems());
            assertTrue(report.summary().contains("5 known releases: 4 with bom, 2 with config, 3 with extensions, "
                    + "0 with classes"),
                    report.summary().toString());
            assertTrue(report.summary().stream().anyMatch(line -> line.startsWith("round trip: 8 of 8 files")),
                    report.summary().toString());

            contents.remove("3.0.0.Final", Kind.BOM);
            writeFiles(datasetDir, contents);
            assertEquals(List.of("releases: 3.0.0.Final has extensions but no BOM"),
                    DatasetVerifier.verify(datasetDir, null).problems());
        }
    }

    @Test
    void anEmptyFolderIsNoDataset() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            DatasetVerifier.Report report = DatasetVerifier.verify(dir.path(), null);
            assertFalse(report.ok());
            assertEquals(1, report.problems().size());
        }
    }

    @Test
    void namesClassesFilesOfLinesWithoutClassesAndMissingOnes() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path datasetDir = dir.resolve("dataset");
            writeFiles(datasetDir, Samples.contentsWithClasses());
            DatasetVerifier.Report sound = DatasetVerifier.verify(datasetDir, null);
            assertEquals(List.of(), sound.problems());
            assertTrue(sound.summary().contains("6 known releases: 5 with bom, 2 with config, 2 with extensions, "
                    + "4 with classes"), sound.summary().toString());
            assertTrue(sound.summary().stream().anyMatch(line -> line.startsWith("classes/4.x.jsonl")),
                    sound.summary().toString());

            Files.move(datasetDir.resolve(DatasetFormat.classesFile(4)),
                    datasetDir.resolve(DatasetFormat.classesFile(2)));
            AtomicFiles.writeString(datasetDir.resolve("classes/notes.txt"), "not a classes file\n");
            DatasetVerifier.Report report = DatasetVerifier.verify(datasetDir, null);
            assertTrue(report.problems().contains("files: classes/2.x.jsonl: no release of the 2.x line has classes in "
                    + "releases.jsonl"), report.problems().toString());
            assertTrue(report.problems().contains("files: classes/4.x.jsonl is missing, but releases.jsonl has classes "
                    + "of the 4.x line"), report.problems().toString());
            assertTrue(report.summary().contains("(not part of the dataset: classes/notes.txt)"),
                    report.summary().toString());
        }
    }

    private static Path write(ScratchDir dir) throws IOException {
        Path datasetDir = dir.resolve("dataset");
        writeFiles(datasetDir, Samples.contents());
        return datasetDir;
    }

    private static void writeFiles(Path datasetDir, Contents contents) throws IOException {
        for (Map.Entry<String, String> file : DatasetEncoder.encode(contents).entrySet()) {
            AtomicFiles.writeString(datasetDir.resolve(file.getKey()), file.getValue());
        }
    }
}
