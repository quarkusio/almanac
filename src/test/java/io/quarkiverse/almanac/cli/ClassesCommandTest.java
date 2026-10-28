package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.Main;
import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.model.ArtifactClasses;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ClassesSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.util.AtomicFiles;
import io.quarkiverse.almanac.util.SnapshotStore;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `classes` offline over the cache of its work folder and another, read-only one, as it runs over the caches of the
/// workers of a long download; then `dataset import` and `dataset verify` of what it wrote.
class ClassesCommandTest {

    /// Exit code, standard output and log (standard error) of one run.
    private record Run(int exitCode, String out, String log) {
    }

    @Test
    void writesTheClassesOfReleasesWhoseJarsSomeCacheKnows() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path work = dir.resolve("work");
            Path other = dir.resolve("other-work/cache");
            writeMetadata(work, "3.30.0", "3.31.0", "3.32.0");
            SnapshotStore snapshots = new SnapshotStore(work.resolve("snapshots"), null);
            for (String release : List.of("3.30.0", "3.31.0", "3.32.0")) {
                // a subgroup, a classifier and a third-party jar are not the io.quarkus jars that config reads
                snapshots.write(release, SnapshotStore.BOM, new BomSnapshot(release, "2026-01-01", List.of(
                        new ManagedDep("io.quarkus.arc:arc", release),
                        new ManagedDep("io.quarkus:quarkus-core", release),
                        new ManagedDep("io.quarkus:quarkus-core:jar:sources", release),
                        new ManagedDep("io.quarkus:quarkus-junit-internal", release),
                        new ManagedDep("io.quarkus:quarkus-junit5-internal", release),
                        new ManagedDep("io.vertx:vertx-core", "4.5.1"))));
            }
            // 3.30.0: everything in the other cache, quarkus-junit-internal does not exist yet
            cacheJar(other, "quarkus-core", "3.30.0", "io/quarkus/runtime/Application.class");
            cacheCentralDirectory(other, "quarkus-junit5-internal", "3.30.0", "io/quarkus/test/QuarkusUnitTest.class",
                    "io/quarkus/test/QuarkusUnitTest$1.class", "io/quarkus/test/package-info.class");
            cacheMissing(other, "quarkus-junit-internal", "3.30.0");
            // 3.31.0: the classes move; split between this work folder's cache and the other one
            cacheJar(work.resolve("cache"), "quarkus-core", "3.31.0", "io/quarkus/runtime/Application.class");
            cacheCentralDirectory(other, "quarkus-junit-internal", "3.31.0", "io/quarkus/test/QuarkusUnitTest.class",
                    "io/quarkus/test/QuarkusUnitTest$1.class");
            cacheJar(other, "quarkus-junit5-internal", "3.31.0", "META-INF/MANIFEST.MF");
            // 3.32.0: quarkus-junit5-internal was never fetched
            cacheJar(other, "quarkus-core", "3.32.0", "io/quarkus/runtime/Application.class");
            cacheJar(other, "quarkus-junit-internal", "3.32.0", "io/quarkus/test/QuarkusUnitTest.class");
            Map<String, Long> otherBefore = listing(other);
            String[] common = {"--offline", "--work", work.toString(), "--dataset", dir.resolve("dataset").toString()};

            Run classes = run(concat(common, "classes", "--cache", other.toString()));
            assertEquals(1, classes.exitCode(), classes.log());
            assertTrue(classes.log().contains("classes 3.30.0: 3 classes in 2 of 3 jars"), classes.log());
            assertTrue(classes.log().contains("classes 3.31.0: 3 classes in 2 of 3 jars"), classes.log());
            assertTrue(classes.log().contains("warning: classes 3.32.0: 1 of 3 jars are in no cache "
                    + "(quarkus-junit5-internal); offline, no snapshot is written"), classes.log());
            assertTrue(classes.log().contains("warning: classes: 1 releases have jars that no cache knows, so they got "
                    + "no snapshot (3.32.0)"), classes.log());
            assertEquals(new ClassesSnapshot("3.31.0", List.of(
                    new ArtifactClasses("quarkus-core", List.of("io.quarkus.runtime.Application")),
                    new ArtifactClasses("quarkus-junit-internal", List.of("io.quarkus.test.QuarkusUnitTest",
                            "io.quarkus.test.QuarkusUnitTest$1")))),
                    snapshots.read("3.31.0", SnapshotStore.CLASSES, ClassesSnapshot.class));
            ClassesSnapshot first = snapshots.read("3.30.0", SnapshotStore.CLASSES, ClassesSnapshot.class);
            assertEquals(List.of("io.quarkus.test.QuarkusUnitTest", "io.quarkus.test.QuarkusUnitTest$1"),
                    first.artifacts().get(1).classes());
            assertFalse(snapshots.inWork("3.32.0", SnapshotStore.CLASSES));
            assertEquals(otherBefore, listing(other), "the other cache is only read");

            // the existing snapshots are kept; the dataset takes them with their BOMs
            Run again = run(concat(common, "classes", "--versions", "3.31.0"));
            assertEquals(0, again.exitCode(), again.log());
            assertTrue(again.log().contains("classes 3.31.0: reusing existing snapshot"), again.log());
            Run imported = run(concat(common, "dataset", "import", "--versions", "all", "--kinds", "bom,classes"));
            assertEquals(0, imported.exitCode(), imported.log());
            assertTrue(imported.log().contains("dataset import: added 5 (bom 3, classes 2)"), imported.log());
            Run verified = run(concat(common, "dataset", "verify", "--compare-work"));
            assertEquals(0, verified.exitCode(), verified.out());
            assertTrue(verified.out().contains("3 known releases: 3 with bom, 0 with config, 0 with extensions, 2 with "
                    + "classes"), verified.out());
            assertTrue(verified.out().contains("classes 2 (0 different)"), verified.out());
            String lines = Files.readString(dir.resolve("dataset/classes/3.x.jsonl"));
            assertTrue(lines.contains("{\"id\":\"quarkus-junit5-internal io.quarkus.test.QuarkusUnitTest\","
                    + "\"h\":[[\"3.30.0\",\"+\"],[\"3.31.0\",null]]}\n"), lines);

            // releases the dataset has are not read again
            Run known = run(concat(common, "classes", "--versions", "3.30.0,3.31.0"));
            assertEquals(0, known.exitCode(), known.log());
            assertTrue(known.log().contains("classes: 2 releases already in the dataset (of 2 selected"), known.log());
        }
    }

    private static Path jarFile(Path cache, String artifactId, String version) {
        return cache.resolve("io/quarkus").resolve(artifactId).resolve(version)
                .resolve(artifactId + "-" + version + ".jar");
    }

    /// Caches a whole (small) jar, as reading it does.
    private static void cacheJar(Path cache, String artifactId, String version, String... entries) throws IOException {
        AtomicFiles.write(jarFile(cache, artifactId, version), zip(entries));
    }

    /// Caches only the central directory of a jar (`ACD1`, the jar's size, the directory), as indexing a large jar
    /// does.
    private static void cacheCentralDirectory(Path cache, String artifactId, String version, String... entries)
            throws IOException {
        byte[] zip = zip(entries);
        // the end-of-central-directory record is the last 22 bytes of a zip without comment
        ByteBuffer littleEndian = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);
        int size = littleEndian.getInt(zip.length - 22 + 12);
        int offset = littleEndian.getInt(zip.length - 22 + 16);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x41434431);
            out.writeLong(zip.length);
            out.write(Arrays.copyOfRange(zip, offset, offset + size));
        }
        Path jar = jarFile(cache, artifactId, version);
        AtomicFiles.write(jar.resolveSibling(jar.getFileName() + ".cd"), bytes.toByteArray());
    }

    /// Caches the 404 answer for a jar.
    private static void cacheMissing(Path cache, String artifactId, String version) throws IOException {
        Path jar = jarFile(cache, artifactId, version);
        AtomicFiles.write(jar.resolveSibling(jar.getFileName() + ".404"), new byte[0]);
    }

    private static byte[] zip(String... entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(entry.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /// Every file under a folder with its size and modification time.
    private static Map<String, Long> listing(Path root) throws IOException {
        Map<String, Long> files = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(path) + " " + Files.size(path), Files.getLastModifiedTime(path).toMillis());
            }
        }
        return files;
    }

    /// A cached `maven-metadata.xml` of the BOM.
    private static void writeMetadata(Path work, String... versions) throws IOException {
        StringBuilder xml = new StringBuilder("<metadata><groupId>io.quarkus</groupId><artifactId>quarkus-bom"
                + "</artifactId><versioning><versions>");
        for (String version : versions) {
            xml.append("<version>").append(version).append("</version>");
        }
        xml.append("</versions></versioning></metadata>\n");
        AtomicFiles.writeString(work.resolve("cache/io/quarkus/quarkus-bom/maven-metadata.xml"), xml.toString());
    }

    private static String[] concat(String[] common, String... command) {
        return Stream.concat(Stream.of(command), Stream.of(common)).toArray(String[]::new);
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
