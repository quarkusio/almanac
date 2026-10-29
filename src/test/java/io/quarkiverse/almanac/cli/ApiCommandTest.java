package io.quarkiverse.almanac.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.quarkiverse.almanac.Main;
import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.classes.ClassesReader;
import io.quarkiverse.almanac.config.sourcedoc.TestCompiler;
import io.quarkiverse.almanac.maven.RangeFetcher;
import io.quarkiverse.almanac.maven.RemoteJar;
import io.quarkiverse.almanac.model.ApiSnapshot;
import io.quarkiverse.almanac.model.ArtifactApi;
import io.quarkiverse.almanac.model.ArtifactClasses;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ClassApi;
import io.quarkiverse.almanac.model.ClassesSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.model.MemberApi;
import io.quarkiverse.almanac.util.AtomicFiles;
import io.quarkiverse.almanac.util.Json;
import io.quarkiverse.almanac.util.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `api` over the caches of its work folder and another, read-only one, as the backfill runs over the caches of the
/// workers of earlier downloads: class files read from whole jars, from `.entries/` and from other versions of a jar,
/// each once; the plan; offline releases whose bytes are not all cached; a local repository for the rest, behind
/// `--max-requests`; then `dataset import`, `verify`, `--missing` and `assemble` of what it wrote.
class ApiCommandTest {

    private static final List<String> RELEASES = List.of("3.30.0", "3.31.0", "3.32.0");
    private static final String APPLICATION = "io/quarkus/runtime/Application";
    private static final String UNIT_TEST = "io/quarkus/test/QuarkusUnitTest";

    /// Exit code, standard output and log (standard error) of one run.
    private record Run(int exitCode, String out, String log) {
    }

    @Test
    void readsEachClassFileOnceFromTheCachesAndTheRestFromTheRepository() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Map<String, byte[]> classes = TestCompiler.compile(Map.of(
                    APPLICATION + ".java", """
                            package io.quarkus.runtime;
                            public class Application {
                                public void start(String... args) {}
                                @Deprecated public void stop() {}
                                public static class Builder {}
                            }
                            """,
                    "io/quarkus/runtime/Internal.java", "package io.quarkus.runtime; class Internal {}",
                    UNIT_TEST + ".java", "package io.quarkus.test; public class QuarkusUnitTest {}"));
            // 3.32.0 deprecates the constructor of QuarkusUnitTest for removal
            byte[] unitTest332 = TestCompiler.compile(Map.of(UNIT_TEST + ".java", """
                    package io.quarkus.test;
                    public class QuarkusUnitTest {
                        @Deprecated(since = "3.32", forRemoval = true) public QuarkusUnitTest() {}
                    }
                    """)).get(UNIT_TEST);
            Map<String, byte[]> core = new LinkedHashMap<>();
            core.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            for (String name : List.of(APPLICATION, APPLICATION + "$Builder", "io/quarkus/runtime/Internal")) {
                core.put(name + ".class", classes.get(name));
            }
            Map<String, byte[]> junit = Map.of(UNIT_TEST + ".class", classes.get(UNIT_TEST));
            Map<String, byte[]> junit332 = Map.of(UNIT_TEST + ".class", unitTest332);

            Path work = dir.resolve("work");
            Path cache = work.resolve("cache");
            Path other = dir.resolve("other-work/cache");
            Path remote = dir.resolve("remote");
            SnapshotStore snapshots = new SnapshotStore(work.resolve("snapshots"), null);
            writeMetadata(work, RELEASES);
            for (String release : RELEASES) {
                Map<String, byte[]> junitJar = release.equals("3.32.0") ? junit332 : junit;
                snapshots.write(release, SnapshotStore.BOM, new BomSnapshot(release, "2026-01-01", List.of(
                        new ManagedDep("io.quarkus:quarkus-core", release),
                        new ManagedDep("io.quarkus:quarkus-junit-internal", release),
                        new ManagedDep("io.vertx:vertx-core", "4.5.1"))));
                snapshots.write(release, SnapshotStore.CLASSES, new ClassesSnapshot(release, List.of(
                        new ArtifactClasses("quarkus-core", ClassesReader.classNames(core.keySet())),
                        new ArtifactClasses("quarkus-junit-internal", ClassesReader.classNames(junitJar.keySet())))));
                write(remote, "quarkus-core", release, zip(core));
                write(remote, "quarkus-junit-internal", release, zip(junitJar));
            }
            // 3.30.0: quarkus-core whole in the other cache, quarkus-junit-internal's class in this one's .entries/
            write(other, "quarkus-core", "3.30.0", read(remote, "quarkus-core", "3.30.0"));
            cacheEntries(cache, "quarkus-junit-internal", "3.30.0", read(remote, "quarkus-junit-internal", "3.30.0"));
            // 3.31.0: central directories only, the same class files as 3.30.0
            cacheCentralDirectory(cache, "quarkus-core", "3.31.0", read(remote, "quarkus-core", "3.31.0"));
            cacheCentralDirectory(cache, "quarkus-junit-internal", "3.31.0", read(remote, "quarkus-junit-internal",
                    "3.31.0"));
            // 3.32.0: quarkus-core whole here; the new QuarkusUnitTest only in the repository
            write(cache, "quarkus-core", "3.32.0", read(remote, "quarkus-core", "3.32.0"));
            cacheCentralDirectory(cache, "quarkus-junit-internal", "3.32.0", read(remote, "quarkus-junit-internal",
                    "3.32.0"));
            Map<String, Long> otherBefore = listing(other);
            String[] offline = {"--offline", "--work", work.toString(), "--dataset", dir.resolve("dataset").toString(),
                    "--cache", other.toString()};

            // the class files of 3.31.0 come from other versions: 3.30.0's whole jar and .entries/
            Run alone = run(concat(offline, "api", "--versions", "3.31.0"));
            assertEquals(0, alone.exitCode(), alone.log());
            assertTrue(alone.log().contains("api 3.31.0: 3 API classes (0 deprecated) with 5 members (1 deprecated) in "
                    + "2 jars; 4 of 4 class files read, 0 fetched with 0 requests"), alone.log());
            ApiSnapshot api331 = snapshots.read("3.31.0", SnapshotStore.API, ApiSnapshot.class);
            assertEquals(new ApiSnapshot("3.31.0", "api/1", List.of(
                    new ArtifactApi("quarkus-core", List.of(
                            new ClassApi("io.quarkus.runtime.Application", "+", List.of(
                                    new MemberApi("<init>()V", "+"),
                                    new MemberApi("start([Ljava/lang/String;)V", "+v"),
                                    new MemberApi("stop()V", "+D"))),
                            new ClassApi("io.quarkus.runtime.Application$Builder", "+s", List.of(
                                    new MemberApi("<init>()V", "+"))))),
                    new ArtifactApi("quarkus-junit-internal", List.of(
                            new ClassApi("io.quarkus.test.QuarkusUnitTest", "+", List.of(
                                    new MemberApi("<init>()V", "+"))))))), api331);

            // the plan of all three, from the caches alone, without looking into the jars cached whole: 3.31.0 has
            // the class file of quarkus-junit-internal from 3.30.0 and those of quarkus-core in other versions; 3.32.0
            // needs the new one, in a jar small enough to be downloaded whole
            Map<String, Long> cacheBefore = listing(cache);
            Run plan = run(concat(offline, "api", "--plan", "--force"));
            assertEquals(0, plan.exitCode(), plan.log());
            assertEquals(List.of("3.30.0", "4", "0", "4", "0", "0", "0", "0", "0.00", "0", "0.00", "0", "0", "0.00"),
                    fields(plan.out(), "3.30.0"));
            assertEquals(List.of("3.31.0", "4", "1", "0", "3", "0", "0"), fields(plan.out(), "3.31.0").subList(0, 7));
            List<String> plan332 = fields(plan.out(), "3.32.0");
            assertEquals(List.of("3.32.0", "4", "0", "3", "0", "1", "0", "1"), plan332.subList(0, 8));
            assertEquals("1", plan332.get(12), plan.out());
            assertTrue(plan.out().contains("1 of 3 releases need requests; the costliest: 3.32.0 (1, 0.0 MB)"),
                    plan.out());
            assertEquals(cacheBefore, listing(cache), "a plan writes nothing");

            // offline, 3.32.0 needs the repository: no snapshot, the others are written (3.31.0 reused)
            Run all = run(concat(offline, "api"));
            assertEquals(1, all.exitCode(), all.log());
            assertTrue(all.log().contains("api: reusing the existing snapshots of 1 releases"), all.log());
            assertTrue(all.log().contains("api 3.30.0: 3 API classes"), all.log());
            assertTrue(all.log().contains("warning: api 3.32.0: 1 jars need the repository (quarkus-junit-internal); "
                    + "offline, no snapshot is written"), all.log());
            assertFalse(snapshots.inWork("3.32.0", SnapshotStore.API));
            assertEquals(api331.artifacts(), snapshots.read("3.30.0", SnapshotStore.API, ApiSnapshot.class)
                    .artifacts());

            AtomicInteger requests = new AtomicInteger();
            HttpServer repository = serve(remote, requests);
            try {
                String url = "http://127.0.0.1:" + repository.getAddress().getPort() + "/maven2/";
                String[] online = {"--repo", url, "--pause", "0", "--work", work.toString(), "--dataset",
                        dir.resolve("dataset").toString(), "--cache", other.toString()};
                // a plan of more requests than allowed sends none
                Run refused = run(concat(online, "api", "--max-requests", "0"));
                assertEquals(1, refused.exitCode(), refused.log());
                assertTrue(refused.log().contains("api: the plan needs 1 requests (0.0 MB), more than --max-requests "
                        + "0, so none is sent"), refused.log());
                assertEquals(0, requests.get());

                Run fetched = run(concat(online, "api"));
                assertEquals(0, fetched.exitCode(), fetched.log());
                assertEquals(1, requests.get(), "the small jar downloaded whole");
                assertTrue(fetched.log().contains("api 3.32.0: 3 API classes (0 deprecated) with 5 members (2 "
                        + "deprecated) in 2 jars; 4 of 4 class files read, 1 fetched with 1 requests"), fetched.log());
                ApiSnapshot api332 = snapshots.read("3.32.0", SnapshotStore.API, ApiSnapshot.class);
                assertEquals(new ClassApi("io.quarkus.test.QuarkusUnitTest", "+", List.of(
                        new MemberApi("<init>()V", "+R since 3.32"))), api332.artifacts().get(1).classes().getFirst());
            } finally {
                repository.stop(0);
            }
            assertEquals(otherBefore, listing(other), "the other cache is only read");

            // the dataset takes the API with its BOM and classes; --missing lists the releases with classes but no API
            String[] common = {"--offline", "--work", work.toString(), "--dataset", dir.resolve("dataset").toString()};
            assertEquals(0, run(concat(common, "dataset", "import", "--versions", "all", "--kinds", "bom,classes"))
                    .exitCode());
            assertEquals("3.30.0\n3.31.0\n3.32.0\n", run(concat(common, "api", "--missing")).out());
            Run imported = run(concat(common, "dataset", "import", "--versions", "3.30.0,3.31.0", "--kinds", "api"));
            assertEquals(0, imported.exitCode(), imported.log());
            assertTrue(imported.log().contains("dataset import: added 2 (api 2)"), imported.log());
            assertEquals("3.32.0\n", run(concat(common, "api", "--missing")).out());
            assertEquals(0, run(concat(common, "dataset", "import", "--versions", "all", "--kinds", "api")).exitCode());
            assertEquals("", run(concat(common, "api", "--missing")).out());
            String lines = Files.readString(dir.resolve("dataset/api/3.x.jsonl"));
            assertTrue(lines.contains("{\"id\":\"quarkus-junit-internal io.quarkus.test.QuarkusUnitTest <init>()V\","
                    + "\"h\":[[\"3.30.0\",\"+\"],[\"3.32.0\",\"+R since 3.32\"]]}\n"), lines);
            Run verified = run(concat(common, "dataset", "verify", "--compare-work"));
            assertEquals(0, verified.exitCode(), verified.out());
            assertTrue(verified.out().contains("3 with classes, 0 with platform, 3 with api"), verified.out());
            assertTrue(verified.out().contains("api 3 (0 different)"), verified.out());

            // releases the dataset has are not read again
            Run known = run(concat(common, "api"));
            assertEquals(0, known.exitCode(), known.log());
            assertTrue(known.log().contains("api: 3 releases already in the dataset (of 3 selected"), known.log());

            // the site gets the deprecations in classes-3.json and the API in its shards
            Path site = dir.resolve("site");
            Run assembled = run(concat(common, "assemble", "--site", site.toString()));
            assertEquals(0, assembled.exitCode(), assembled.log());
            JsonNode classes3 = Json.readTree(Files.readAllBytes(site.resolve("data/classes-3.json")));
            assertEquals(Json.readTree("""
                    {"versions":[0,1,2],"shards":32,
                     "dep":[[0,0,"stop()V",[[0,"+D"]]],[3,1,"<init>()V",[[0,"+"],[2,"+R since 3.32"]]]]}
                    """.getBytes(StandardCharsets.UTF_8)), classes3.get("api"));
            assertTrue(Files.isRegularFile(site.resolve("data/api-3-31.json")));
        }
    }

    @Test
    void anApiJsonOlderThanTheClassesOfItsReleaseIsReadAgain() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            String extra = "io/quarkus/runtime/Extra";
            Map<String, byte[]> classes = TestCompiler.compile(Map.of(
                    APPLICATION + ".java", "package io.quarkus.runtime; public class Application {}",
                    extra + ".java", "package io.quarkus.runtime; public class Extra {}"));
            Map<String, byte[]> core = new LinkedHashMap<>();
            core.put(APPLICATION + ".class", classes.get(APPLICATION));
            core.put(extra + ".class", classes.get(extra));
            Path work = dir.resolve("work");
            SnapshotStore snapshots = new SnapshotStore(work.resolve("snapshots"), null);
            writeMetadata(work, List.of("3.30.0"));
            snapshots.write("3.30.0", SnapshotStore.BOM, new BomSnapshot("3.30.0", "2026-01-01", List.of(
                    new ManagedDep("io.quarkus:quarkus-core", "3.30.0"))));
            // the classes as a classes reader that left Extra out listed them
            snapshots.write("3.30.0", SnapshotStore.CLASSES, new ClassesSnapshot("3.30.0", List.of(
                    new ArtifactClasses("quarkus-core", List.of("io.quarkus.runtime.Application")))));
            write(work.resolve("cache"), "quarkus-core", "3.30.0", zip(core));
            String[] common = {"--offline", "--work", work.toString(), "--dataset", dir.resolve("dataset").toString()};
            assertEquals(0, run(concat(common, "api")).exitCode());
            assertEquals(0, run(concat(common, "dataset", "import", "--versions", "all", "--kinds",
                    "bom,classes,api")).exitCode());

            // the classes read again, with Extra, replace those of the dataset, whose API goes with them; the
            // api.json read from the old classes is older than the new classes.json (as it is, whatever the
            // resolution of file times)
            snapshots.write("3.30.0", SnapshotStore.CLASSES, new ClassesSnapshot("3.30.0", List.of(
                    new ArtifactClasses("quarkus-core", List.of("io.quarkus.runtime.Application",
                            "io.quarkus.runtime.Extra")))));
            Path apiJson = snapshots.file("3.30.0", SnapshotStore.API);
            Files.setLastModifiedTime(apiJson, FileTime.fromMillis(Files.getLastModifiedTime(snapshots.file("3.30.0",
                    SnapshotStore.CLASSES)).toMillis() - 60_000));
            Run replaced = run(concat(common, "dataset", "import", "--versions", "3.30.0", "--kinds", "classes",
                    "--force"));
            assertEquals(0, replaced.exitCode(), replaced.log());
            assertTrue(replaced.log().contains("warning: dataset import: removed api 3.30.0, read from the jars of the "
                    + "classes this import replaces; api extracts it again from the new classes.json of the work "
                    + "folder, unless the api.json there is newer (as when the classes came from elsewhere): then api "
                    + "--force --versions 3.30.0 does"), replaced.log());
            assertEquals("3.30.0\n", run(concat(common, "api", "--missing")).out());

            // so api reads the release again, and so does its plan, instead of reusing that api.json
            Run plan = run(concat(common, "api", "--plan"));
            assertEquals(0, plan.exitCode(), plan.log());
            assertEquals(List.of("3.30.0", "2"), fields(plan.out(), "3.30.0").subList(0, 2));
            assertTrue(plan.log().contains("api: 1 releases are read again, since their api.json in "), plan.log());
            Run again = run(concat(common, "api"));
            assertEquals(0, again.exitCode(), again.log());
            assertTrue(again.log().contains("api: 1 releases are read again, since their api.json in "), again.log());
            assertTrue(again.log().contains("api 3.30.0: 2 API classes"), again.log());
            assertEquals(List.of("io.quarkus.runtime.Application", "io.quarkus.runtime.Extra"), snapshots.read("3.30.0",
                    SnapshotStore.API, ApiSnapshot.class).artifacts().getFirst().classes().stream().map(ClassApi::name)
                    .toList());
            // newer than its classes.json now, it is reused
            Run reused = run(concat(common, "api"));
            assertEquals(0, reused.exitCode(), reused.log());
            assertTrue(reused.log().contains("api: reusing the existing snapshots of 1 releases"), reused.log());
            assertFalse(reused.log().contains("read again"), reused.log());

            Run imported = run(concat(common, "dataset", "import", "--versions", "all", "--kinds", "api"));
            assertTrue(imported.log().contains("dataset import: added 1 (api 1)"), imported.log());
            assertEquals("", run(concat(common, "api", "--missing")).out());
            Run verified = run(concat(common, "dataset", "verify", "--compare-work"));
            assertEquals(0, verified.exitCode(), verified.out());
        }
    }

    @Test
    void aClassFileThatBreaksTheParserFailsOnlyItsRelease() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            List<String> releases = List.of("3.29.0", "3.30.0", "3.31.0");
            Map<String, byte[]> classes = TestCompiler.compile(Map.of(APPLICATION + ".java",
                    "package io.quarkus.runtime; public class Application {}"));
            Path work = dir.resolve("work");
            SnapshotStore snapshots = new SnapshotStore(work.resolve("snapshots"), null);
            writeMetadata(work, releases);
            for (String release : releases) {
                Map<String, byte[]> core = new LinkedHashMap<>();
                core.put(APPLICATION + ".class", classes.get(APPLICATION));
                if (release.equals("3.30.0")) {
                    // annotation values nested deeper than any parser recurses: a StackOverflowError
                    core.put("io/quarkus/runtime/Deep.class", deeplyAnnotated("io/quarkus/runtime/Deep", 300_000));
                }
                snapshots.write(release, SnapshotStore.BOM, new BomSnapshot(release, "2026-01-01", List.of(
                        new ManagedDep("io.quarkus:quarkus-core", release))));
                snapshots.write(release, SnapshotStore.CLASSES, new ClassesSnapshot(release, List.of(
                        new ArtifactClasses("quarkus-core", ClassesReader.classNames(core.keySet())))));
                write(work.resolve("cache"), "quarkus-core", release, zip(core));
            }
            String[] offline = {"--offline", "--work", work.toString(), "--dataset", dir.resolve("dataset").toString()};

            Run run = run(concat(offline, "api"));
            assertEquals(1, run.exitCode(), run.log());
            assertTrue(run.log().contains("error: api 3.30.0: quarkus-core:3.30.0: io/quarkus/runtime/Deep.class: "
                    + "not a readable class file (java.lang.StackOverflowError)"), run.log());
            assertTrue(run.log().contains("error: api: 1 of 3 releases failed"), run.log());
            assertTrue(snapshots.inWork("3.29.0", SnapshotStore.API));
            assertFalse(snapshots.inWork("3.30.0", SnapshotStore.API));
            assertTrue(snapshots.inWork("3.31.0", SnapshotStore.API), "the run goes on after the failed release");

            // a release that needs no request runs whatever --max-requests allows, 0 included
            Run noRequests = run(concat(offline, "api", "--versions", "3.31.0", "--force", "--max-requests", "0"));
            assertEquals(0, noRequests.exitCode(), noRequests.log());
            assertTrue(noRequests.log().contains("api 3.31.0: 1 API classes"), noRequests.log());
        }
    }

    @Test
    void entriesReadWithTheIndexOfAnotherCacheAreFoundAgain() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            List<String> releases = List.of("3.30.0", "3.31.0");
            String api = "io/quarkus/big/Api";
            String other = "io/quarkus/big/Other";
            Map<String, byte[]> classes = TestCompiler.compile(Map.of(
                    api + ".java", "package io.quarkus.big; public class Api { public void call() {} }",
                    other + ".java", "package io.quarkus.big; public interface Other {}"));
            // a jar of more than 2 MB, which is read with range requests: the class files after 2.2 MB of padding
            byte[] padding = new byte[2_200_000];
            new java.util.Random(3).nextBytes(padding);
            Map<String, byte[]> big = new LinkedHashMap<>();
            big.put("META-INF/padding.bin", padding);
            big.put(api + ".class", classes.get(api));
            big.put(other + ".class", classes.get(other));

            Path work = dir.resolve("work");
            Path cache = work.resolve("cache");
            Path otherCache = dir.resolve("other-work/cache");
            Path remote = dir.resolve("remote");
            SnapshotStore snapshots = new SnapshotStore(work.resolve("snapshots"), null);
            writeMetadata(work, releases);
            for (String release : releases) {
                snapshots.write(release, SnapshotStore.BOM, new BomSnapshot(release, "2026-01-01", List.of(
                        new ManagedDep("io.quarkus:quarkus-big", release))));
                snapshots.write(release, SnapshotStore.CLASSES, new ClassesSnapshot(release, List.of(
                        new ArtifactClasses("quarkus-big", ClassesReader.classNames(big.keySet())))));
                write(remote, "quarkus-big", release, zip(big));
            }
            // the central directory of 3.30.0 only in the other cache, that of 3.31.0 here: no class file is cached
            cacheCentralDirectory(otherCache, "quarkus-big", "3.30.0", read(remote, "quarkus-big", "3.30.0"));
            cacheCentralDirectory(cache, "quarkus-big", "3.31.0", read(remote, "quarkus-big", "3.31.0"));
            Path jar330 = jarFile(cache, "quarkus-big", "3.30.0");
            Path cd330 = jar330.resolveSibling(jar330.getFileName() + ".cd");
            String[] offline = {"--offline", "--work", work.toString(), "--dataset", dir.resolve("dataset").toString(),
                    "--cache", otherCache.toString()};

            AtomicInteger requests = new AtomicInteger();
            HttpServer repository = serve(remote, requests);
            try {
                String url = "http://127.0.0.1:" + repository.getAddress().getPort() + "/maven2/";
                Run fetched = run(concat(new String[] {"--repo", url, "--pause", "0", "--work", work.toString(),
                        "--dataset", dir.resolve("dataset").toString(), "--cache", otherCache.toString()}, "api",
                        "--versions", "3.30.0"));
                assertEquals(0, fetched.exitCode(), fetched.log());
                assertEquals(1, requests.get(), "the two class files with one range request");
            } finally {
                repository.stop(0);
            }
            // the central directory they were read with is kept with them
            assertTrue(Files.isDirectory(jar330.resolveSibling(jar330.getFileName() + ".entries")));
            assertTrue(Arrays.equals(Files.readAllBytes(otherCache.resolve(cache.relativize(cd330))),
                    Files.readAllBytes(cd330)), "the central directory of the other cache, copied");
            assertFalse(Files.exists(jar330), "not downloaded whole");

            // 3.31.0 has the same class files: those of 3.30.0, read offline
            Run same = run(concat(offline, "api", "--versions", "3.31.0"));
            assertEquals(0, same.exitCode(), same.log());
            assertTrue(same.log().contains("api 3.31.0: 2 API classes (0 deprecated) with 2 members (0 deprecated) in "
                    + "1 jars; 2 of 2 class files read, 0 fetched with 0 requests"), same.log());

            // entries without their central directory, as an earlier version of the command left them: the index of
            // the other cache reads them, for their own version and for the other ones
            Files.delete(cd330);
            Run plan = run(concat(offline, "api", "--plan", "--force"));
            assertEquals(0, plan.exitCode(), plan.log());
            assertEquals(List.of("3.30.0", "2", "0", "2", "0", "0", "0"), fields(plan.out(), "3.30.0").subList(0, 7));
            assertEquals(List.of("3.31.0", "2", "2", "0", "0", "0", "0"), fields(plan.out(), "3.31.0").subList(0, 7));
            assertTrue(plan.out().contains("0 of 2 releases need requests"), plan.out());
            Run again = run(concat(offline, "api", "--versions", "3.30.0", "--force"));
            assertEquals(0, again.exitCode(), again.log());
            Run alone = run(concat(offline, "api", "--plan", "--force", "--versions", "3.31.0"));
            assertEquals(List.of("3.31.0", "2", "0", "0", "2", "0", "0"), fields(alone.out(), "3.31.0").subList(0, 7));
            Run otherVersion = run(concat(offline, "api", "--versions", "3.31.0", "--force"));
            assertEquals(0, otherVersion.exitCode(), otherVersion.log());
            assertFalse(Files.exists(cd330), "offline, nothing is written into the cache");
        }
    }

    /// A class whose annotation holds an annotation, `depth` times.
    private static byte[] deeplyAnnotated(String internalName, int depth) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object",
                null);
        List<AnnotationVisitor> nested = new ArrayList<>();
        AnnotationVisitor annotation = writer.visitAnnotation("Lio/quarkus/runtime/Nest;", true);
        nested.add(annotation);
        for (int i = 0; i < depth; i++) {
            annotation = annotation.visitAnnotation("value", "Lio/quarkus/runtime/Nest;");
            nested.add(annotation);
        }
        for (int i = nested.size() - 1; i >= 0; i--) {
            nested.get(i).visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    /// The whitespace-separated fields of the plan line of a release.
    private static List<String> fields(String out, String release) {
        return out.lines().filter(line -> line.startsWith(release + " ")).findFirst()
                .map(line -> Arrays.stream(line.split("[\\s|]+")).filter(field -> !field.isEmpty()).toList())
                .orElseThrow(() -> new AssertionError("no plan line of " + release + " in\n" + out));
    }

    private static Path jarFile(Path cache, String artifactId, String version) {
        return cache.resolve("io/quarkus").resolve(artifactId).resolve(version)
                .resolve(artifactId + "-" + version + ".jar");
    }

    private static void write(Path cache, String artifactId, String version, byte[] jar) throws IOException {
        AtomicFiles.write(jarFile(cache, artifactId, version), jar);
    }

    private static byte[] read(Path cache, String artifactId, String version) throws IOException {
        return Files.readAllBytes(jarFile(cache, artifactId, version));
    }

    /// Caches only the central directory of a jar (`ACD1`, the jar's size, the directory), as indexing a large jar
    /// does.
    private static void cacheCentralDirectory(Path cache, String artifactId, String version, byte[] zip)
            throws IOException {
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

    /// Caches the central directory of a jar and each of its class files in `.entries/`, as reading entries of a
    /// large jar does.
    private static void cacheEntries(Path cache, String artifactId, String version, byte[] zip) throws IOException {
        RangeFetcher fetcher = new RangeFetcher() {
            @Override
            public Optional<Tail> tail(int length) {
                return Optional.of(new Tail(Arrays.copyOfRange(zip, Math.max(0, zip.length - length), zip.length),
                        zip.length));
            }

            @Override
            public byte[] range(long offset, int length) {
                return Arrays.copyOfRange(zip, (int) offset, (int) offset + length);
            }

            @Override
            public byte[] all() {
                throw new AssertionError("not downloaded whole");
            }
        };
        RemoteJar jar = new RemoteJar("io.quarkus", artifactId, version, fetcher, jarFile(cache, artifactId, version),
                new RemoteJar.Options(64, 0));
        for (String entry : jar.entryNames()) {
            if (entry.endsWith(".class")) {
                jar.read(entry);
            }
        }
        assertFalse(Files.exists(jarFile(cache, artifactId, version)));
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : new TreeMap<>(entries).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /// A repository over a folder (`/maven2/<path>`), answering range requests, counting the requests.
    private static HttpServer serve(Path folder, AtomicInteger requests) throws IOException {
        Pattern suffix = Pattern.compile("bytes=-(\\d+)");
        Pattern span = Pattern.compile("bytes=(\\d+)-(\\d+)");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/maven2/", exchange -> {
            requests.incrementAndGet();
            Path file = folder.resolve(exchange.getRequestURI().getPath().substring("/maven2/".length()));
            if (!Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] content = Files.readAllBytes(file);
            String range = exchange.getRequestHeaders().getFirst("Range");
            int from = 0;
            int to = content.length - 1;
            int status = 200;
            if (range != null) {
                Matcher last = suffix.matcher(range);
                Matcher between = span.matcher(range);
                if (last.matches()) {
                    from = Math.max(0, content.length - Integer.parseInt(last.group(1)));
                } else if (between.matches()) {
                    from = Integer.parseInt(between.group(1));
                    to = Math.min(to, Integer.parseInt(between.group(2)));
                }
                status = 206;
                exchange.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + content.length);
            }
            exchange.sendResponseHeaders(status, to - from + 1);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(content, from, to - from + 1);
            }
        });
        server.start();
        return server;
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

    /// A cached `maven-metadata.xml` of the BOM listing these releases.
    private static void writeMetadata(Path work, List<String> releases) throws IOException {
        StringBuilder xml = new StringBuilder("<metadata><groupId>io.quarkus</groupId><artifactId>quarkus-bom"
                + "</artifactId><versioning><versions>");
        releases.forEach(version -> xml.append("<version>").append(version).append("</version>"));
        xml.append("</versions></versioning></metadata>\n");
        AtomicFiles.writeString(work.resolve("cache/io/quarkus/quarkus-bom/maven-metadata.xml"), xml.toString());
    }

    private static String[] concat(String[] common, String... command) {
        List<String> args = new ArrayList<>(List.of(command));
        args.addAll(List.of(common));
        return args.toArray(String[]::new);
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
