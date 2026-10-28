package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.Main;
import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.model.BomSnapshot;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `extensions` offline on a scratch cache: snapshots from the catalogs of both formats, a release without a catalog,
/// reuse of existing snapshots, `--out`, and the releases the dataset has, which are skipped. Then the snapshot of a
/// release without config goes through `dataset import` and `assemble`.
class ExtensionsCommandTest {

    private static final String PLATFORM = "cache/io/quarkus/quarkus-bom-quarkus-platform-descriptor/";
    private static final String DESCRIPTOR = "cache/io/quarkus/quarkus-bom-descriptor-json/";

    /// Exit code and log (standard error) of one run.
    private record Run(int exitCode, String log) {
    }

    @Test
    void writesSnapshotsFromTheCatalogsAndSkipsWhatTheDatasetHas() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path work = dir.resolve("work");
            Path dataset = dir.resolve("dataset");
            writeCache(work);
            String[] common = {"--offline", "--work", work.toString(), "--dataset", dataset.toString()};
            SnapshotStore snapshots = new SnapshotStore(work.resolve("snapshots"), null);

            Run first = run(concat(common, "extensions", "--versions", "0.25.0,1.9.2.Final,2.16.12.Final"));
            assertEquals(1, first.exitCode(), first.log());
            assertTrue(first.log().contains("extensions 2.16.12.Final: 1 extensions (0 unlisted) from "
                    + "io.quarkus:quarkus-bom-quarkus-platform-descriptor:2.16.12.Final:json:2.16.12.Final"),
                    first.log());
            assertTrue(first.log().contains("extensions 1.9.2.Final: 1 extensions (1 unlisted) from "
                    + "io.quarkus:quarkus-bom-descriptor-json:1.9.2.Final:json"), first.log());
            assertTrue(first.log().contains("extensions: 1 releases have no extension catalog (0.25.0)"), first.log());
            assertEquals(List.of("quarkus-arc"), ids(snapshots.read("2.16.12.Final", SnapshotStore.EXTENSIONS,
                    ExtensionsSnapshot.class)));
            assertFalse(snapshots.inWork("0.25.0", SnapshotStore.EXTENSIONS));

            Run again = run(concat(common, "extensions", "--versions", "2.16.12.Final"));
            assertEquals(0, again.exitCode(), again.log());
            assertTrue(again.log().contains("extensions 2.16.12.Final: reusing the existing snapshot"), again.log());

            Path out = dir.resolve("out");
            Run elsewhere = run(concat(common, "extensions", "--versions", "2.16.12.Final", "--out", out.toString()));
            assertEquals(0, elsewhere.exitCode(), elsewhere.log());
            assertEquals(Files.readString(snapshots.file("2.16.12.Final", SnapshotStore.EXTENSIONS)),
                    Files.readString(out.resolve("2.16.12.Final").resolve(SnapshotStore.EXTENSIONS)));

            // with its BOM, the release imports without config, and the site lists its extensions
            snapshots.write("2.16.12.Final", SnapshotStore.BOM, new BomSnapshot("2.16.12.Final", "2023-10-16",
                    List.of(new ManagedDep("io.quarkus:quarkus-arc", "2.16.12.Final"))));
            Run imported = run(concat(common, "dataset", "import", "--versions", "2.16.12.Final", "--kinds",
                    "bom,extensions"));
            assertEquals(0, imported.exitCode(), imported.log());
            assertTrue(imported.log().contains("dataset import: added 2 (bom 1, extensions 1)"), imported.log());
            assertTrue(Files.readString(dataset.resolve("releases.jsonl")).contains(
                    "{\"version\":\"2.16.12.Final\",\"date\":\"2023-10-16\",\"bom\":true,\"extensions\":true}\n"));

            Run skipped = run(concat(common, "extensions", "--versions", "2.16.12.Final", "--out", out.toString()));
            assertEquals(0, skipped.exitCode(), skipped.log());
            assertTrue(skipped.log().contains("extensions: 1 releases already in the dataset (of 1 selected"),
                    skipped.log());

            Path site = dir.resolve("site");
            Run assembled = run(concat(common, "assemble", "--since", "2.16", "--site", site.toString()));
            assertEquals(0, assembled.exitCode(), assembled.log());
            assertEquals("{\"v\":\"2.16.12.Final\",\"date\":\"2023-10-16\",\"stream\":\"2.16\",\"pre\":false,"
                    + "\"lts\":false,\"config\":false,\"ext\":true,\"classes\":false}",
                    Json.readTree(Files.readAllBytes(site.resolve("data/versions.json"))).at("/versions/0").toString());
        }
    }

    /// A cached release list and both catalog artifacts: 2.16.12.Final (registry catalog), 1.9.2.Final (descriptor),
    /// and 0.25.0, which has none.
    private static void writeCache(Path work) throws IOException {
        AtomicFiles.writeString(work.resolve("cache/io/quarkus/quarkus-bom/maven-metadata.xml"),
                metadata("0.25.0", "1.9.2.Final", "2.16.12.Final"));
        AtomicFiles.writeString(work.resolve(PLATFORM + "maven-metadata.xml"), metadata("2.16.12.Final"));
        AtomicFiles.writeString(work.resolve(DESCRIPTOR + "maven-metadata.xml"), metadata("1.9.2.Final"));
        AtomicFiles.writeString(work.resolve(PLATFORM + "2.16.12.Final/"
                + "quarkus-bom-quarkus-platform-descriptor-2.16.12.Final-2.16.12.Final.json"), """
                {"extensions": [{"name": "ArC", "metadata": {"status": "stable"},
                                 "artifact": "io.quarkus:quarkus-arc::jar:2.16.12.Final"}]}
                """);
        AtomicFiles.writeString(work.resolve(DESCRIPTOR + "1.9.2.Final/quarkus-bom-descriptor-json-1.9.2.Final.json"),
                """
                {"extensions": [{"name": "Quarkus - Core", "metadata": {"unlisted": true}, "group-id": "io.quarkus",
                                 "artifact-id": "quarkus-core", "version": "1.9.2.Final"}]}
                """);
    }

    private static String metadata(String... versions) {
        StringBuilder xml = new StringBuilder("<metadata><versioning><versions>");
        for (String version : versions) {
            xml.append("<version>").append(version).append("</version>");
        }
        return xml.append("</versions></versioning></metadata>\n").toString();
    }

    private static List<String> ids(ExtensionsSnapshot snapshot) {
        return snapshot.extensions().stream().map(ExtensionInfo::id).toList();
    }

    /// The command line `command... common...`.
    private static String[] concat(String[] common, String... command) {
        String[] args = new String[command.length + common.length];
        System.arraycopy(command, 0, args, 0, command.length);
        System.arraycopy(common, 0, args, command.length, common.length);
        return args;
    }

    private static Run run(String... args) {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
        try {
            CommandLine commandLine = new CommandLine(new Main()).setCaseInsensitiveEnumValuesAllowed(true);
            commandLine.setOut(new PrintWriter(new StringWriter()));
            commandLine.setErr(new PrintWriter(new StringWriter()));
            int exitCode = commandLine.execute(args);
            return new Run(exitCode, log.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
        } finally {
            System.setErr(originalErr);
        }
    }
}
