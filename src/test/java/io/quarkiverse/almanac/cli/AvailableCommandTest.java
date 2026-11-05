package io.quarkiverse.almanac.cli;

import com.sun.net.httpserver.HttpServer;
import io.quarkiverse.almanac.Main;
import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.util.AtomicFiles;
import io.quarkiverse.almanac.util.SnapshotStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `available` against a local repository: a release is read once it is all there, what is on its way fails the run
/// and is asked for again by the next one, and what never comes stops failing some hours after the release.
class AvailableCommandTest {

    private static final String VERSION = "3.90.0";
    private static final String CORE_JAR = path("io.quarkus", "quarkus-core", VERSION, "jar");
    private static final String CORE_POM = path("io.quarkus", "quarkus-core", VERSION, "pom");
    private static final String ARC_POM = path("io.quarkus.arc", "arc", VERSION, "pom");
    private static final String PLUGIN_POM = path("io.quarkus", "quarkus-maven-plugin", VERSION, "pom");
    private static final String PARENT_POM = path("io.quarkus", "quarkus-build-parent", VERSION, "pom");
    private static final String BOM_POM = path("io.quarkus", "quarkus-bom", VERSION, "pom");

    /// Exit code, log (standard error) and output of one run.
    private record Run(int exitCode, String log, String out) {
    }

    private ScratchDir dir;
    private HttpServer repository;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Set<String> refused = ConcurrentHashMap.newKeySet();
    private final List<String> requested = new CopyOnWriteArrayList<>();
    private final List<ManagedDep> deps = new ArrayList<>(List.of(new ManagedDep("io.quarkus:quarkus-core", VERSION),
            new ManagedDep("io.quarkus.arc:arc", VERSION), new ManagedDep("org.example:library", "1.0")));
    private volatile String bomLastModified;
    private String versions = VERSION;

    private static String path(String groupId, String artifactId, String version, String extension) {
        return "/maven2/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-"
                + version + "." + extension;
    }

    @BeforeEach
    void start() throws IOException {
        dir = ScratchDir.create();
        repository = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        repository.createContext("/maven2/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requested.add(path);
            byte[] content = files.get(path);
            if (refused.contains(path)) {
                exchange.sendResponseHeaders(403, -1);
            } else if (content == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                if (path.equals(BOM_POM) && bomLastModified != null) {
                    exchange.getResponseHeaders().add("Last-Modified", bomLastModified);
                }
                exchange.sendResponseHeaders(200, content.length);
                exchange.getResponseBody().write(content);
            }
            exchange.close();
        });
        repository.start();

        // a whole release, whose BOM came an hour ago
        cameAgo(Duration.ofHours(1));
        files.put(BOM_POM, pom("io.quarkus", "quarkus-bom", ""));
        files.put(CORE_JAR, zip());
        files.put(CORE_POM, pom("io.quarkus", "quarkus-core", parent("io.quarkus", "quarkus-build-parent", VERSION)));
        files.put(ARC_POM, pom("io.quarkus.arc", "arc", ""));
        files.put(PLUGIN_POM, pom("io.quarkus", "quarkus-maven-plugin",
                parent("io.quarkus", "quarkus-build-parent", VERSION)));
        files.put(PARENT_POM, pom("io.quarkus", "quarkus-build-parent", ""));
    }

    @AfterEach
    void stop() throws InterruptedException {
        repository.stop(0);
        dir.close();
    }

    private void cameAgo(Duration age) {
        bomLastModified = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(Instant.now().minus(age).atOffset(ZoneOffset.UTC));
    }

    @Test
    void aReleaseThatIsAllThereIsAvailable() throws IOException {
        Run run = run("work");

        assertEquals(0, run.exitCode(), run.log());
        assertTrue(run.log().contains("available 3.90.0: no jar or pom is missing\n"), run.log());
        assertEquals("", run.out());
    }

    @Test
    void aJarOnItsWayFailsTheRunAndTheNextRunAsksForItAgain() throws IOException {
        byte[] jar = files.remove(CORE_JAR);

        Run early = run("work");

        assertEquals(1, early.exitCode(), early.log());
        assertTrue(early.log().contains("error: available 3.90.0: it came out 60 minutes ago, so what the repository "
                + "lacks may still come: io.quarkus:quarkus-core:3.90.0 (jar)\n"), early.log());
        assertTrue(early.log().contains("error: available: 1 of 1 releases are not all there\n"), early.log());
        assertEquals("", early.out());

        files.put(CORE_JAR, jar);

        Run later = run("work");

        assertEquals(0, later.exitCode(), later.log());
        assertTrue(later.log().contains("available 3.90.0: no jar or pom is missing\n"), later.log());
    }

    @Test
    void aPomOnItsWayFailsTheRunAndTheNextRunAsksForItAgain() throws IOException {
        int folder = 0;
        for (String path : List.of(CORE_POM, ARC_POM, PLUGIN_POM, PARENT_POM)) {
            // what an earlier run read stays in the cache: each pom starts from an empty one
            String work = "work-" + folder++;
            byte[] pom = files.remove(path);

            Run early = run(work);

            assertEquals(1, early.exitCode(), path + ": " + early.log());
            assertTrue(early.log().contains(" (pom)\n"), path + ": " + early.log());

            files.put(path, pom);

            Run later = run(work);

            assertEquals(0, later.exitCode(), path + ": " + later.log());
        }
    }

    @Test
    void whatIsStillMissingAfterTheRetriesIsTakenAsNeverPublished() throws IOException {
        files.remove(CORE_JAR);
        files.remove(CORE_POM);
        cameAgo(AvailableCommand.RETRY.plusMinutes(1));

        Run run = run("work");

        assertEquals(0, run.exitCode(), run.log());
        assertTrue(run.log().contains("warning: available 3.90.0: it came out 781 minutes ago, so what the repository "
                + "lacks is taken as never published: io.quarkus:quarkus-core:3.90.0 (jar), "
                + "io.quarkus:quarkus-core:3.90.0 (pom)\n"), run.log());
        assertEquals("io.quarkus:quarkus-core:3.90.0 (jar)\nio.quarkus:quarkus-core:3.90.0 (pom)\n", run.out());
    }

    @Test
    void theTimeOfTheReleaseCountsAndNotItsBomWhenGiven() throws IOException {
        // the first run after a GitHub release: its BOM is days old, and the release not
        files.remove(CORE_JAR);
        cameAgo(Duration.ofDays(7));

        Run first = run("work", "--released", Instant.now().minus(Duration.ofHours(12)).toString());

        assertEquals(1, first.exitCode(), first.log());
        assertTrue(first.log().contains("it came out 720 minutes ago, so what the repository lacks may still come"),
                first.log());

        Run next = run("work", "--released", Instant.now().minus(Duration.ofHours(14)).toString());

        assertEquals(0, next.exitCode(), next.log());
        assertEquals("io.quarkus:quarkus-core:3.90.0 (jar)\n", next.out());
    }

    @Test
    void theTimeOfAReleaseIsNotTheTimeOfOthers() throws IOException {
        versions = VERSION + ",3.90.1";

        Run run = run("work", "--released", Instant.now().toString());

        assertEquals(2, run.exitCode(), run.log());
        assertTrue(run.log().contains("--released needs exactly one release in --versions, not 2"), run.log());
        assertEquals(List.of(), requested);
    }

    @Test
    void aMissingArtifactOfABomWithoutADateMayStillCome() throws IOException {
        files.remove(CORE_JAR);
        bomLastModified = null;

        Run run = run("work");

        assertEquals(1, run.exitCode(), run.log());
        assertTrue(run.log().contains("it came out at an unknown time, so what the repository lacks may still come"),
                run.log());
    }

    @Test
    void aRelocatedArtifactPublishesNoJar() throws IOException {
        files.remove(CORE_JAR);
        files.put(CORE_POM, ("<project><groupId>io.quarkus</groupId><artifactId>quarkus-core</artifactId><version>"
                + VERSION + "</version><distributionManagement><relocation><artifactId>quarkus-new-core</artifactId>"
                + "</relocation></distributionManagement></project>").getBytes(StandardCharsets.UTF_8));

        Run run = run("work");

        assertEquals(0, run.exitCode(), run.log());
        assertTrue(run.log().contains("available 3.90.0: no jar or pom is missing\n"), run.log());
    }

    @Test
    void aRefusedPomSaysNothingAboutTheRelease() throws IOException {
        // the last request of the run, long after the release: read as missing, it would pass
        refused.add(PARENT_POM);

        Run run = run("work", "--released", Instant.now().minus(AvailableCommand.RETRY.plusHours(1)).toString());

        assertEquals(PARENT_POM, requested.getLast());
        assertEquals(1, run.exitCode(), run.log());
        assertFalse(run.log().contains("never published"), run.log());
    }

    @Test
    void aRefusedJarSaysNothingAboutTheRelease() throws IOException {
        assertEquals(0, run("work").exitCode());
        // the only request of the next run: all the rest is cached
        Path cached = dir.resolve("work/cache/io/quarkus/quarkus-core/3.90.0/quarkus-core-3.90.0.jar");
        Files.deleteIfExists(cached);
        Files.deleteIfExists(cached.resolveSibling(cached.getFileName() + ".cd"));
        refused.add(CORE_JAR);
        requested.clear();

        Run run = run("work", "--released", Instant.now().minus(AvailableCommand.RETRY.plusHours(1)).toString());

        assertEquals(List.of(CORE_JAR), requested);
        assertEquals(1, run.exitCode(), run.log());
        assertFalse(run.log().contains("never published"), run.log());
    }

    @Test
    void theMarkersOfAnEarlierRunAreAskedAgain() throws IOException {
        // a jar at a version of its own, and what is relocated: their poms are read only when the jar is missing
        deps.add(new ManagedDep("io.quarkus:quarkus-fs-util", "1.4.2"));
        files.put(path("io.quarkus", "quarkus-fs-util", "1.4.2", "pom"), pom("io.quarkus", "quarkus-fs-util", ""));
        files.remove(CORE_JAR);
        byte[] relocated = ("<project><groupId>io.quarkus</groupId><artifactId>quarkus-core</artifactId><version>"
                + VERSION + "</version><distributionManagement><relocation><artifactId>quarkus-new-core</artifactId>"
                + "</relocation></distributionManagement></project>").getBytes(StandardCharsets.UTF_8);
        files.put(CORE_POM, relocated);
        files.put(path("io.quarkus", "quarkus-fs-util", "1.4.2", "jar"), zip());
        Path cache = dir.resolve("work/cache");
        List<Path> markers = new ArrayList<>();
        for (String path : List.of(CORE_POM, ARC_POM, PLUGIN_POM, PARENT_POM,
                path("io.quarkus", "quarkus-fs-util", "1.4.2", "jar"))) {
            markers.add(cache.resolve(path.substring("/maven2/".length()) + ".404"));
        }
        for (Path marker : markers) {
            AtomicFiles.writeString(marker, "");
        }

        Run run = run("work");

        assertEquals(0, run.exitCode(), run.log());
        for (Path marker : markers) {
            assertFalse(Files.exists(marker), marker.toString());
        }
    }

    @Test
    void aParentAtAnotherVersionIsNotOfTheRelease() throws IOException {
        files.put(ARC_POM, pom("io.quarkus.arc", "arc", parent("org.example", "parent", "1.0")));

        Run run = run("work");

        assertEquals(0, run.exitCode(), run.log());
        assertFalse(requested.contains(path("org.example", "parent", "1.0", "pom")), requested.toString());
    }

    @Test
    void anEntryWithATypeOrAClassifierIsNotChecked() throws IOException {
        deps.add(new ManagedDep("io.quarkus:quarkus-grpc-protoc-plugin:shaded", VERSION));

        Run run = run("work");

        assertEquals(0, run.exitCode(), run.log());
        assertFalse(requested.stream().anyMatch(path -> path.contains("protoc")), requested.toString());
    }

    @Test
    void aPomThatDoesNotParseIsThere() throws IOException {
        files.put(ARC_POM, "not a pom".getBytes(StandardCharsets.UTF_8));

        Run run = run("work");

        assertEquals(0, run.exitCode(), run.log());
        assertTrue(run.log().contains("warning: io.quarkus.arc:arc:3.90.0: "), run.log());
        assertTrue(run.log().contains(", so its parent is not looked for\n"), run.log());
    }

    @Test
    void itCannotRunOffline() throws IOException {
        Path marker = dir.resolve("work/cache").resolve(CORE_JAR.substring("/maven2/".length()) + ".404");
        AtomicFiles.writeString(marker, "");

        Run run = run("work", "--offline");

        assertEquals(1, run.exitCode(), run.log());
        assertTrue(run.log().contains("error: available: it asks the repository what it has, so it cannot run offline"),
                run.log());
        assertTrue(Files.exists(marker));
        assertEquals(List.of(), requested);
    }

    @Test
    void whatIsMissingMayStillComeUntilTheRetriesAreOver() {
        Instant now = Instant.parse("2026-11-05T12:00:00Z");
        assertTrue(AvailableCommand.mayStillCome(now.minus(AvailableCommand.RETRY).plusSeconds(1), now));
        assertFalse(AvailableCommand.mayStillCome(now.minus(AvailableCommand.RETRY), now));
        assertTrue(AvailableCommand.mayStillCome(null, now));
    }

    private static String parent(String groupId, String artifactId, String version) {
        return "<parent><groupId>" + groupId + "</groupId><artifactId>" + artifactId + "</artifactId><version>"
                + version + "</version></parent>";
    }

    private static byte[] pom(String groupId, String artifactId, String parent) {
        return ("<project>" + parent + "<groupId>" + groupId + "</groupId><artifactId>" + artifactId + "</artifactId>"
                + "<version>" + VERSION + "</version></project>").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("io/quarkus/Core.class"));
            zip.write(new byte[] {1, 2, 3});
            zip.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    /// One run on the work folder `name`, which gets the release list and the BOM snapshot if it has none.
    private Run run(String name, String... more) throws IOException {
        Path work = dir.resolve(name);
        AtomicFiles.writeString(work.resolve("cache/io/quarkus/quarkus-bom/maven-metadata.xml"),
                "<metadata><versioning><versions><version>" + VERSION + "</version><version>3.90.1</version>"
                        + "</versions></versioning></metadata>\n");
        new SnapshotStore(work.resolve("snapshots"), null).write(VERSION, SnapshotStore.BOM,
                new BomSnapshot(VERSION, "2026-01-01", List.copyOf(deps)));
        String repo = "http://" + repository.getAddress().getHostString() + ":" + repository.getAddress().getPort()
                + "/maven2/";
        List<String> args = new ArrayList<>(List.of("available", "--versions", versions, "--work", work.toString(),
                "--dataset", dir.resolve("dataset").toString(), "--repo", repo, "--pause", "0.001"));
        args.addAll(List.of(more));
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
        try {
            CommandLine commandLine = new CommandLine(new Main()).setCaseInsensitiveEnumValuesAllowed(true);
            commandLine.setOut(new PrintWriter(out));
            commandLine.setErr(new PrintWriter(err));
            int exitCode = commandLine.execute(args.toArray(String[]::new));
            return new Run(exitCode, (log.toString(StandardCharsets.UTF_8) + err).replace("\r\n", "\n"),
                    out.toString().replace("\r\n", "\n"));
        } finally {
            System.setErr(originalErr);
        }
    }
}
