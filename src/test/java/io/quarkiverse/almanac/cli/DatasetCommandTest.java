package io.quarkiverse.almanac.cli;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkiverse.almanac.Main;
import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ConfigProperty;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.model.ExtensionInfo;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.util.AtomicFiles;
import io.quarkiverse.almanac.util.Json;
import io.quarkiverse.almanac.util.SnapshotStore;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The dataset workflow end to end on scratch folders, offline: import, verify, export, the skipping of known releases
/// by `bom` and `config`, the release list of a fresh clone, `assemble` from the dataset (and `--include-work`), `all`.
class DatasetCommandTest {

    private static final List<String> PUBLISHED = List.of("2.16.12.Final", "3.0.0.Final", "3.1.0", "3.2.0");

    /// Exit code, standard output and log (standard error) of one run.
    private record Run(int exitCode, String out, String log) {
    }

    @Test
    void importVerifyAndExportRoundTrip() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path work = dir.resolve("work");
            SnapshotStore snapshots = workSnapshots(work, true);
            Path dataset = dir.resolve("dataset");

            Run imported = run("dataset", "import", "--offline", "--work", work.toString(), "--dataset",
                    dataset.toString(), "--versions", "all");
            assertEquals(1, imported.exitCode(), imported.log());
            // the unvalidated config stays out; its extensions need no config and are imported
            assertTrue(imported.log().contains("dataset import: added 8 (bom 3, config 2, extensions 3); replaced 0; "
                    + "unchanged 0; refused 1"), imported.log());
            assertTrue(imported.log().contains("warning: dataset import: refused config 3.0.0.Final: extractor "
                    + "bytecode/1 is not validated"), imported.log());

            Run verified = run("dataset", "verify", "--offline", "--work", work.toString(), "--dataset",
                    dataset.toString(), "--compare-work");
            assertEquals(0, verified.exitCode(), verified.out());
            assertTrue(verified.out().contains("compared with " + snapshots.root() + ": bom 3 (0 different) config 2 "
                    + "(0 different) extensions 3 (0 different)"), verified.out());
            assertTrue(verified.out().endsWith("dataset verify: OK\n"), verified.out());

            Path exported = dir.resolve("exported");
            Run export = run("dataset", "export", "--offline", "--work", work.toString(), "--dataset",
                    dataset.toString(), "--out", exported.toString());
            assertEquals(0, export.exitCode(), export.log());
            assertTrue(export.log().contains("dataset export: 8 files written, 0 unchanged, 0 kept"), export.log());
            for (String release : List.of("3.0.0.Final", "3.1.0", "3.2.0")) {
                for (String file : List.of(SnapshotStore.BOM, SnapshotStore.CONFIG, SnapshotStore.EXTENSIONS)) {
                    Path written = exported.resolve(release).resolve(file);
                    if (Files.exists(written)) {
                        assertEquals(Files.readString(snapshots.file(release, file)), Files.readString(written),
                                release + "/" + file + " byte for byte");
                    }
                }
            }
            assertFalse(Files.exists(exported.resolve("3.0.0.Final").resolve(SnapshotStore.CONFIG)));

            // exporting into the work folder keeps what is there
            Run again = run("dataset", "export", "--offline", "--work", work.toString(), "--dataset",
                    dataset.toString());
            assertEquals(0, again.exitCode(), again.log());
            assertTrue(again.log().contains("0 files written, 8 unchanged, 0 kept"), again.log());
        }
    }

    @Test
    void releasesInTheDatasetAreNeverFetchedAgain() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path dataset = importAll(dir);
            Path fresh = dir.resolve("fresh-work");
            String[] common = {"--offline", "--work", fresh.toString(), "--dataset", dataset.toString()};

            // no maven-metadata.xml in this work folder: the release list comes from releases.jsonl
            Run versions = run(concat(new String[]{"versions", "--select", "all"}, common));
            assertEquals(0, versions.exitCode(), versions.log());
            assertEquals("3.0.0.Final    final 3.0\n3.1.0          final 3.1\n3.2.0          final 3.2   LTS\n"
                    + "3 releases (3 final, 0 pre-release)\n", versions.out());
            assertTrue(versions.log().contains("using the 4 releases of the dataset"), versions.log());

            Run bom = run(concat(new String[]{"bom", "--versions", "all"}, common));
            assertEquals(0, bom.exitCode(), bom.log());
            assertTrue(bom.log().contains("bom: 3 releases already in the dataset (of 3 selected"), bom.log());
            assertFalse(bom.log().contains("HTTP:"), bom.log());

            Run config = run(concat(new String[]{"config", "--versions", "3.1.0,3.2.0"}, common));
            assertEquals(0, config.exitCode(), config.log());
            assertTrue(config.log().contains("config: 2 releases already in the dataset (of 2 selected"), config.log());
            assertFalse(Files.exists(fresh.resolve("snapshots")), "nothing extracted, nothing written");

            // --force really processes them: offline, that fails for want of the BOM pom
            Run forced = run(concat(new String[]{"bom", "--versions", "3.1.0", "--force"}, common));
            assertEquals(1, forced.exitCode(), forced.log());
            assertTrue(forced.log().contains("offline mode: not fetching"), forced.log());
        }
    }

    @Test
    void assembleReadsTheDatasetAndWorkSnapshotsOnlyWhenAskedTo() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path work = dir.resolve("work");
            SnapshotStore snapshots = workSnapshots(work, true);
            Path dataset = dir.resolve("dataset");
            // the dataset gets 3.0.0.Final and 3.1.0; 3.2.0 stays in the work folder only
            assertEquals(0, run("dataset", "import", "--offline", "--work", work.toString(), "--dataset",
                    dataset.toString(), "--versions", "3.0.0.Final,3.1.0", "--kinds", "bom").exitCode());
            assertEquals(0, run("dataset", "import", "--offline", "--work", work.toString(), "--dataset",
                    dataset.toString(), "--versions", "3.1.0", "--kinds", "config,extensions").exitCode());
            writeMetadata(work);
            Path site = dir.resolve("site");

            Run fromDataset = run("assemble", "--offline", "--work", work.toString(), "--dataset", dataset.toString(),
                    "--site", site.toString());
            assertEquals(0, fromDataset.exitCode(), fromDataset.log());
            assertEquals(List.of("3.0.0.Final", "3.1.0"), published(site));
            assertTrue(fromDataset.log().contains("1 bom.json snapshots in " + snapshots.root() + " are not in the "
                    + "dataset and are left out (3.2.0)"), fromDataset.log());
            assertTrue(fromDataset.log().contains("2 config.json snapshots in " + snapshots.root() + " are not in "
                    + "the dataset and are left out (3.0.0.Final, 3.2.0)"), fromDataset.log());
            JsonNode versions = Json.readTree(Files.readAllBytes(site.resolve("data/versions.json")));
            assertTrue(versions.at("/versions/1/config").asBoolean());

            Run withWork = run("assemble", "--offline", "--work", work.toString(), "--dataset", dataset.toString(),
                    "--site", site.toString(), "--include-work");
            assertEquals(0, withWork.exitCode(), withWork.log());
            assertEquals(List.of("3.0.0.Final", "3.1.0", "3.2.0"), published(site));
            assertTrue(withWork.log().contains("--include-work used 1 BOM, 2 config, 2 extensions and 0 classes "
                    + "snapshots"),
                    withWork.log());

            Run noDataset = run("assemble", "--offline", "--work", work.toString(), "--dataset",
                    dir.resolve("missing").toString(), "--site", site.toString());
            assertEquals(1, noDataset.exitCode());
        }
    }

    @Test
    void allImportsWhatItExtractedAndAssemblesFromTheDataset() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path work = dir.resolve("work");
            workSnapshots(work, true);
            writeMetadata(work);
            Path dataset = dir.resolve("dataset");
            Path site = dir.resolve("site");
            // every snapshot is in the work folder already, so nothing is downloaded
            Run all = run("all", "--offline", "--work", work.toString(), "--dataset", dataset.toString(), "--site",
                    site.toString());
            assertEquals(0, all.exitCode(), all.log());
            assertTrue(all.log().contains("dataset import: added 8 (bom 3, config 2, extensions 3)"), all.log());
            assertTrue(all.log().contains("dataset: 4 known releases (1 new: 2.16.12.Final, 0 dropped, 0 dated from "
                    + "the HTTP cache)"), all.log());
            assertEquals(List.of("3.0.0.Final", "3.1.0", "3.2.0"), published(site));
            JsonNode versions = Json.readTree(Files.readAllBytes(site.resolve("data/versions.json")));
            assertFalse(versions.at("/versions/0/config").asBoolean(), "the bytecode/1 config stays out");
            assertTrue(versions.at("/versions/0/ext").asBoolean(), "its extensions are published without it");
            assertEquals(0, run("dataset", "verify", "--offline", "--dataset", dataset.toString()).exitCode());

            // a second run finds everything in the dataset
            Run again = run("all", "--offline", "--work", work.toString(), "--dataset", dataset.toString(), "--site",
                    site.toString());
            assertEquals(0, again.exitCode(), again.log());
            assertTrue(again.log().contains("bom: 3 releases already in the dataset"), again.log());
            assertTrue(again.log().contains("config: 2 releases already in the dataset"), again.log());
            assertTrue(again.log().contains("dataset: no file changed"), again.log());
        }
    }

    @Test
    void refreshReleasesReadsTheCachedMetadata() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path dataset = importAll(dir);
            Path work = dir.resolve("work");
            writeMetadata(work);
            AtomicFiles.writeString(work.resolve("cache/io/quarkus/quarkus-bom/2.16.12.Final/"
                    + "quarkus-bom-2.16.12.Final.pom.last-modified"), "2023-02-01T10:00:00Z\n");
            Run refresh = run("dataset", "refresh-releases", "--offline", "--work", work.toString(), "--dataset",
                    dataset.toString());
            assertEquals(0, refresh.exitCode(), refresh.log());
            assertTrue(Files.readString(dataset.resolve("releases.jsonl")).startsWith(
                    "{\"version\":\"2.16.12.Final\",\"date\":\"2023-02-01\"}\n"));

            Run noMetadata = run("dataset", "refresh-releases", "--offline", "--work", dir.resolve("none").toString(),
                    "--dataset", dataset.toString());
            assertEquals(1, noMetadata.exitCode());
        }
    }

    @Test
    void aBomThatKeepsAReferenceIsWrittenAndImportedWithAWarningEach() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path work = dir.resolve("work");
            Path dataset = dir.resolve("dataset");
            String pom = "cache/io/quarkus/quarkus-bom/2.4.1.Final/quarkus-bom-2.4.1.Final.pom";
            AtomicFiles.writeString(work.resolve("cache/io/quarkus/quarkus-bom/maven-metadata.xml"),
                    "<metadata><versioning><versions><version>2.4.1.Final</version></versions></versioning>"
                            + "</metadata>\n");
            AtomicFiles.writeString(work.resolve(pom), """
                    <project>
                      <groupId>io.quarkus</groupId><artifactId>quarkus-bom</artifactId><version>2.4.1.Final</version>
                      <dependencyManagement><dependencies>
                        <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-core</artifactId>
                          <version>2.4.1.Final</version></dependency>
                        <dependency><groupId>io.netty</groupId><artifactId>netty-tcnative</artifactId>
                          <version>${tcnative.version}</version></dependency>
                      </dependencies></dependencyManagement>
                    </project>
                    """);
            AtomicFiles.writeString(work.resolve(pom + ".last-modified"), "2021-11-03T09:00:00Z\n");
            String[] common = {"--offline", "--work", work.toString(), "--dataset", dataset.toString()};

            Run bom = run(concat(new String[]{"bom", "--versions", "2.4.1.Final"}, common));
            assertEquals(0, bom.exitCode(), bom.log());
            assertEquals(1, bom.log().lines().filter(line -> line.startsWith("warning: ")).count(), bom.log());
            assertTrue(bom.log().contains("warning: bom 2.4.1.Final: 1 managed dependency keeps unresolved "
                    + "references: ${tcnative.version} (declared in io.quarkus:quarkus-bom:2.4.1.Final): "
                    + "io.netty:netty-tcnative;"), bom.log());
            Path written = work.resolve("snapshots/2.4.1.Final/bom.json");
            assertTrue(Files.readString(written).contains("\"v\": \"${tcnative.version}\""), Files.readString(written));

            Run imported = run(concat(new String[]{"dataset", "import", "--versions", "2.4.1.Final", "--kinds",
                    "bom"}, common));
            assertEquals(0, imported.exitCode(), imported.log());
            assertTrue(imported.log().contains("dataset import: added 1 (bom 1); replaced 0; unchanged 0; refused 0\n"
                    + "warning: dataset import: bom 2.4.1.Final: 1 managed dependency keeps a ${...} reference as "
                    + "written (e.g. io.netty:netty-tcnative ${tcnative.version})\n"), imported.log());
            assertEquals(0, run("dataset", "verify", "--offline", "--dataset", dataset.toString()).exitCode());

            Path exported = dir.resolve("exported");
            Run export = run(concat(new String[]{"dataset", "export", "--kinds", "bom", "--out", exported.toString()},
                    common));
            assertEquals(0, export.exitCode(), export.log());
            assertEquals(Files.readString(written), Files.readString(exported.resolve("2.4.1.Final/bom.json")));
        }
    }

    @Test
    void usageErrors() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            String[] common = {"--offline", "--work", dir.resolve("work").toString(), "--dataset",
                    dir.resolve("dataset").toString()};
            assertEquals(2, run("dataset").exitCode());
            assertEquals(2, run(concat(new String[]{"dataset", "import"}, common)).exitCode());
            assertEquals(2, run(concat(new String[]{"dataset", "import", "--versions", "all", "--kinds", "pom"},
                    common)).exitCode());
            assertEquals(2, run(concat(new String[]{"dataset", "import", "--versions", "3.9.9"}, common)).exitCode());
        }
    }

    /// Imports every importable sample snapshot into `<dir>/dataset` and returns it; `releases.jsonl` then also knows
    /// 2.16.12.Final, from the release list.
    private static Path importAll(ScratchDir dir) throws IOException {
        Path work = dir.resolve("work");
        workSnapshots(work, false);
        writeMetadata(work);
        Path dataset = dir.resolve("dataset");
        Run imported = run("dataset", "import", "--offline", "--work", work.toString(), "--dataset",
                dataset.toString(), "--versions", "all");
        assertEquals(0, imported.exitCode(), imported.log());
        assertEquals(0, run("dataset", "refresh-releases", "--offline", "--work", work.toString(), "--dataset",
                dataset.toString()).exitCode());
        // the work folder is only the source: a fresh work folder is used afterwards
        return dataset;
    }

    /// Work snapshots: BOMs of 3.0.0.Final, 3.1.0 and 3.2.0, config and extensions of 3.1.0 and 3.2.0 and, with
    /// `unvalidated`, a `bytecode/1` config of 3.0.0.Final.
    private static SnapshotStore workSnapshots(Path work, boolean unvalidated) throws IOException {
        SnapshotStore store = new SnapshotStore(work.resolve("snapshots"), null);
        for (String release : List.of("3.0.0.Final", "3.1.0", "3.2.0")) {
            store.write(release, SnapshotStore.BOM, new BomSnapshot(release, "2023-0" + (release.charAt(2) - '0' + 4)
                    + "-01", List.of(new ManagedDep("io.quarkus:quarkus-core", release),
                    new ManagedDep("io.vertx:vertx-core", release.equals("3.2.0") ? "4.4.4" : "4.4.1"))));
        }
        for (String release : List.of("3.1.0", "3.2.0")) {
            store.write(release, SnapshotStore.CONFIG, config(release, "model", "model-json/1"));
            store.write(release, SnapshotStore.EXTENSIONS, extensions(release));
        }
        if (unvalidated) {
            store.write("3.0.0.Final", SnapshotStore.CONFIG, config("3.0.0.Final", "bytecode", "bytecode/1"));
            store.write("3.0.0.Final", SnapshotStore.EXTENSIONS, extensions("3.0.0.Final"));
        }
        return store;
    }

    private static ConfigSnapshot config(String release, String source, String extractor) {
        return new ConfigSnapshot(release, source, extractor, 1, List.of(), List.of(new ConfigProperty(
                "quarkus.http.port", "quarkus.http.port", List.of(), "quarkus-vertx-http", "int", false, false, false,
                release.equals("3.2.0") ? "8081" : "8080", "RUN_TIME", null, null, "The port.", "HttpConfig#port")));
    }

    private static ExtensionsSnapshot extensions(String release) {
        return new ExtensionsSnapshot(release, List.of(new ExtensionInfo("quarkus-vertx-http", "Vert.x HTTP", null,
                "stable", List.of("web"), null, false, List.of("quarkus.http"), null)));
    }

    /// A cached `maven-metadata.xml` of the BOM listing [#PUBLISHED].
    private static void writeMetadata(Path work) throws IOException {
        StringBuilder xml = new StringBuilder("<metadata><groupId>io.quarkus</groupId><artifactId>quarkus-bom"
                + "</artifactId><versioning><versions>");
        PUBLISHED.forEach(version -> xml.append("<version>").append(version).append("</version>"));
        xml.append("</versions></versioning></metadata>\n");
        AtomicFiles.writeString(work.resolve("cache/io/quarkus/quarkus-bom/maven-metadata.xml"), xml.toString());
    }

    private static List<String> published(Path site) throws IOException {
        List<String> versions = new ArrayList<>();
        Json.readTree(Files.readAllBytes(site.resolve("data/versions.json"))).get("versions")
                .forEach(row -> versions.add(row.get("v").asText()));
        return versions;
    }

    private static String[] concat(String[] first, String[] second) {
        return Stream.concat(Stream.of(first), Stream.of(second)).toArray(String[]::new);
    }

    private static Run run(String... args) {
        StringWriter out = new StringWriter();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
        try {
            CommandLine commandLine = new CommandLine(new Main()).setCaseInsensitiveEnumValuesAllowed(true);
            commandLine.setOut(new PrintWriter(out));
            commandLine.setErr(new PrintWriter(new StringWriter()));
            int exitCode = commandLine.execute(args);
            return new Run(exitCode, out.toString().replace("\r\n", "\n"),
                    log.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
        } finally {
            System.setErr(originalErr);
        }
    }
}
