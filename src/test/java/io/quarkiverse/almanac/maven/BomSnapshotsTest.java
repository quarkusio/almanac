package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.GitSource;
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.util.SnapshotStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// [BomSnapshots] over an offline repository cache: a BOM that is not flattened is resolved with its parents and
/// imports; a version that Maven cannot resolve either is kept as written with one warning, while what Maven fails on
/// (a parent or an import that cannot be read or keeps a reference, a cycle, a managed dependency without a valid
/// groupId) writes nothing.
class BomSnapshotsTest {

    /// Shaped like the published Quarkus 1.x BOMs: a parent chain, properties and imported BOMs.
    static final String UNFLATTENED = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>io.quarkus</groupId>
                <artifactId>quarkus-parent</artifactId>
                <version>1.0.0.Final</version>
                <relativePath>../../pom.xml</relativePath>
              </parent>
              <artifactId>quarkus-bom</artifactId>
              <packaging>pom</packaging>
              <properties>
                <netty.version>4.1.42.Final</netty.version>
                <resteasy.version>4.3.1.Final</resteasy.version>
              </properties>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>io.netty</groupId>
                    <artifactId>netty-bom</artifactId>
                    <version>${netty.version}</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency>
                  <dependency>
                    <groupId>io.quarkus</groupId>
                    <artifactId>quarkus-core</artifactId>
                    <version>${project.version}</version>
                  </dependency>
                  <dependency>
                    <groupId>org.jboss.resteasy</groupId>
                    <artifactId>resteasy-core</artifactId>
                    <version>${resteasy.version}</version>
                  </dependency>
                  <dependency>
                    <groupId>io.netty</groupId>
                    <artifactId>netty-codec</artifactId>
                    <version>${netty.version}</version>
                  </dependency>
                  <dependency>
                    <groupId>org.graalvm.sdk</groupId>
                    <artifactId>graal-sdk</artifactId>
                    <version>${graal-sdk.version}</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """;

    static final String QUARKUS_PARENT = """
            <project>
              <parent>
                <groupId>org.jboss</groupId>
                <artifactId>jboss-parent</artifactId>
                <version>35</version>
              </parent>
              <groupId>io.quarkus</groupId>
              <artifactId>quarkus-parent</artifactId>
              <version>1.0.0.Final</version>
              <properties>
                <graal-sdk.version>19.2.1</graal-sdk.version>
              </properties>
            </project>
            """;

    static final String JBOSS_PARENT = """
            <project>
              <groupId>org.jboss</groupId>
              <artifactId>jboss-parent</artifactId>
              <version>35</version>
            </project>
            """;

    static final String NETTY_BOM = """
            <project>
              <parent>
                <groupId>org.sonatype.oss</groupId>
                <artifactId>oss-parent</artifactId>
                <version>7</version>
              </parent>
              <groupId>io.netty</groupId>
              <artifactId>netty-bom</artifactId>
              <version>4.1.42.Final</version>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>${project.groupId}</groupId>
                    <artifactId>netty-codec</artifactId>
                    <version>4.0.0-loses</version>
                  </dependency>
                  <dependency>
                    <groupId>${project.groupId}</groupId>
                    <artifactId>netty-common</artifactId>
                    <version>${project.version}</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """;

    static final String OSS_PARENT = """
            <project>
              <groupId>org.sonatype.oss</groupId>
              <artifactId>oss-parent</artifactId>
              <version>7</version>
            </project>
            """;

    static final List<ManagedDep> RESOLVED = List.of(
            new ManagedDep("io.netty:netty-codec", "4.1.42.Final"),
            new ManagedDep("io.netty:netty-common", "4.1.42.Final"),
            new ManagedDep("io.quarkus:quarkus-core", "1.0.0.Final"),
            new ManagedDep("org.graalvm.sdk:graal-sdk", "19.2.1"),
            new ManagedDep("org.jboss.resteasy:resteasy-core", "4.3.1.Final"));

    /// Writes a pom into a repository cache folder, as [MavenRepo] keeps downloads.
    static void cache(Path cache, String coordinates, String pom) throws IOException {
        String[] parts = coordinates.split(":");
        Path file = cache.resolve(MavenRepo.artifactPath(parts[0], parts[1], parts[2], "pom"));
        Files.createDirectories(file.getParent());
        Files.writeString(file, pom, StandardCharsets.UTF_8);
    }

    /// A cache holding the 1.0.0.Final BOM of [#UNFLATTENED] with its parents and import, released 2019-11-25.
    static void cacheUnflattened(Path cache) throws IOException {
        cache(cache, "io.quarkus:quarkus-bom:1.0.0.Final", UNFLATTENED);
        Files.writeString(cache.resolve(MavenRepo.artifactPath("io.quarkus", "quarkus-bom", "1.0.0.Final", "pom")
                + ".last-modified"), "2019-11-25T10:03:01Z\n", StandardCharsets.UTF_8);
        cache(cache, "io.quarkus:quarkus-parent:1.0.0.Final", QUARKUS_PARENT);
        cache(cache, "org.jboss:jboss-parent:35", JBOSS_PARENT);
        cache(cache, "io.netty:netty-bom:4.1.42.Final", NETTY_BOM);
        cache(cache, "org.sonatype.oss:oss-parent:7", OSS_PARENT);
    }

    @Test
    void resolvesAndWritesABomThatIsNotFlattened() throws Exception {
        try (ScratchDir dir = ScratchDir.create(); Http offline = new Http(1, true)) {
            cacheUnflattened(dir.resolve("cache"));
            SnapshotStore store = new SnapshotStore(dir.resolve("snapshots"), null);
            BomSnapshots boms = new BomSnapshots(
                    new MavenRepo(MavenRepo.CENTRAL, dir.resolve("cache"), offline, RemoteJar.Options.DEFAULT), store);

            BomSnapshot snapshot = boms.load("1.0.0.Final", false);

            assertEquals(new BomSnapshot("1.0.0.Final", "2019-11-25", RESOLVED), snapshot);
            assertEquals(snapshot, store.read("1.0.0.Final", SnapshotStore.BOM, BomSnapshot.class));
            assertEquals(snapshot, boms.load("1.0.0.Final", false), "reused");
        }
    }

    @Test
    void aReleaseNeverPublishedIsResolvedFromItsGitTagWithoutAskingTheRepository() throws Exception {
        try (ScratchDir dir = ScratchDir.create(); Http offline = new Http(1, true)) {
            Path cache = dir.resolve("cache");
            for (String version : List.of("26", "31", "32")) {
                cache(cache, "org.jboss:jboss-parent:" + version,
                        JBOSS_PARENT.replace("<version>35</version>", "<version>" + version + "</version>"));
            }
            SnapshotStore store = new SnapshotStore(dir.resolve("snapshots"), null);
            BomSnapshots boms = boms(cache, store, offline);

            BomSnapshot quarkus = boms.load("0.10.0", false);
            assertEquals(new GitSource("0.10.0", "f26057191b8217f43b632e2eb5b3541821cf242d", "io.quarkus:quarkus-bom"),
                    quarkus.git());
            assertEquals("2019-03-01", quarkus.date(), "the day of the tag's commit");
            assertEquals(34, quarkus.deps().size());
            assertTrue(quarkus.deps().stream()
                    .allMatch(dep -> dep.id().startsWith("io.quarkus:quarkus-") && dep.v().equals("0.10.0")));
            assertEquals(quarkus, store.read("0.10.0", SnapshotStore.BOM, BomSnapshot.class), "written with its source");

            BomSnapshot shamrock = boms.load("0.2.0", false);
            assertEquals("org.jboss.shamrock:shamrock-bom", shamrock.git().bom());
            assertEquals(22, shamrock.deps().size());
            assertTrue(shamrock.deps().stream()
                    .allMatch(dep -> dep.id().startsWith("org.jboss.shamrock:") && dep.v().equals("0.2.0")));

            // neither these BOMs nor their parents in the repository were asked of it, nor cached as missing
            assertFalse(Files.exists(cache.resolve("io/quarkus")));
            assertFalse(Files.exists(cache.resolve("org/jboss/shamrock")));
        }
    }

    /// What `action` logs (standard error), with `\n` line ends.
    static String log(IoAction action) throws IOException {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(originalErr);
        }
        return log.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @FunctionalInterface
    interface IoAction {
        void run() throws IOException;
    }

    /// The BOMs of a cache folder, read offline into a work folder without dataset.
    private static BomSnapshots boms(Path cache, SnapshotStore store, Http offline) {
        return new BomSnapshots(new MavenRepo(MavenRepo.CENTRAL, cache, offline, RemoteJar.Options.DEFAULT), store);
    }

    @Test
    void aVersionThatMavenCannotResolveIsKeptAsWrittenWithOneWarning() throws Exception {
        // shaped like the published 2.4.1.Final, flattened with the netty-tcnative versions of netty-bom 4.1.68.Final
        String flattened = """
                <project>
                  <groupId>io.quarkus</groupId>
                  <artifactId>quarkus-bom</artifactId>
                  <version>2.4.1.Final</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>io.netty</groupId>
                        <artifactId>netty-tcnative</artifactId>
                        <version>${tcnative.version}</version>
                      </dependency>
                      <dependency>
                        <groupId>io.netty</groupId>
                        <artifactId>netty-common</artifactId>
                        <version>4.1.69.Final</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """;
        try (ScratchDir dir = ScratchDir.create(); Http offline = new Http(1, true)) {
            Path cache = dir.resolve("cache");
            cache(cache, "io.quarkus:quarkus-bom:2.4.1.Final", flattened);
            Files.writeString(cache.resolve(MavenRepo.artifactPath("io.quarkus", "quarkus-bom", "2.4.1.Final", "pom")
                    + ".last-modified"), "2021-11-03T09:00:00Z\n", StandardCharsets.UTF_8);
            SnapshotStore store = new SnapshotStore(dir.resolve("snapshots"), null);
            BomSnapshots boms = boms(cache, store, offline);
            BomSnapshot expected = new BomSnapshot("2.4.1.Final", "2021-11-03", List.of(
                    new ManagedDep("io.netty:netty-common", "4.1.69.Final"),
                    new ManagedDep("io.netty:netty-tcnative", "${tcnative.version}")));

            List<BomSnapshot> loaded = new ArrayList<>();
            String first = log(() -> loaded.add(boms.load("2.4.1.Final", false)));

            assertEquals(List.of(expected), loaded);
            assertEquals(expected, store.read("2.4.1.Final", SnapshotStore.BOM, BomSnapshot.class));
            assertEquals("""
                    warning: bom 2.4.1.Final: 1 managed dependency keeps unresolved references: ${tcnative.version} \
                    (declared in io.quarkus:quarkus-bom:2.4.1.Final): io.netty:netty-tcnative; neither the poms that \
                    declare them nor their parents define these properties; they are kept as written, as Maven keeps \
                    them
                    bom 2.4.1.Final: 2 managed dependencies (0 io.quarkus), released 2021-11-03
                    """, first);

            // reused like any other snapshot, without a warning
            byte[] written = Files.readAllBytes(store.file("2.4.1.Final", SnapshotStore.BOM));
            String reused = log(() -> assertEquals(expected, boms.load("2.4.1.Final", false)));
            assertFalse(reused.contains("warning") || reused.contains("managed dependencies"), reused);

            // --force resolves it again: the same warning, the same bytes
            String forced = log(() -> assertEquals(expected, boms.load("2.4.1.Final", true)));
            assertEquals(first, forced);
            assertArrayEquals(written, Files.readAllBytes(store.file("2.4.1.Final", SnapshotStore.BOM)));
        }
    }

    @Test
    void theWarningNamesThePomAndTheImportThatBroughtTheReferencesIn() throws Exception {
        // netty-bom 4.1.68.Final manages netty-tcnative at a property it does not define; an early BOM manages
        // el-impl at a property it misspells
        List<String> tcnative = new ArrayList<>();
        for (String classifier : List.of("", "linux-x86_64", "linux-aarch_64", "osx-x86_64", "windows-x86_64")) {
            tcnative.add("<dependency><groupId>io.netty</groupId><artifactId>netty-tcnative</artifactId>"
                    + "<version>${tcnative.version}</version>"
                    + (classifier.isEmpty() ? "" : "<classifier>" + classifier + "</classifier>") + "</dependency>");
        }
        String nettyBom = """
                <project>
                  <groupId>io.netty</groupId>
                  <artifactId>netty-bom</artifactId>
                  <version>4.1.68.Final</version>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>io.netty</groupId><artifactId>netty-common</artifactId>
                      <version>${project.version}</version></dependency>
                    %s
                  </dependencies></dependencyManagement>
                </project>
                """.formatted(String.join("\n", tcnative));
        String bom = """
                <project>
                  <groupId>io.quarkus</groupId>
                  <artifactId>quarkus-bom</artifactId>
                  <version>0.11.0</version>
                  <properties><javax.el-impl.version>3.0.1-b08</javax.el-impl.version></properties>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.glassfish.web</groupId><artifactId>el-impl</artifactId>
                      <version>${el-impl.version}</version></dependency>
                    <dependency><groupId>io.netty</groupId><artifactId>netty-bom</artifactId>
                      <version>4.1.68.Final</version><type>pom</type><scope>import</scope></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """;
        try (ScratchDir dir = ScratchDir.create(); Http offline = new Http(1, true)) {
            Path cache = dir.resolve("cache");
            cache(cache, "io.quarkus:quarkus-bom:0.11.0", bom);
            Files.writeString(cache.resolve(MavenRepo.artifactPath("io.quarkus", "quarkus-bom", "0.11.0", "pom")
                    + ".last-modified"), "2019-03-20T08:00:00Z\n", StandardCharsets.UTF_8);
            cache(cache, "io.netty:netty-bom:4.1.68.Final", nettyBom);
            SnapshotStore store = new SnapshotStore(dir.resolve("snapshots"), null);

            List<BomSnapshot> loaded = new ArrayList<>();
            String log = log(() -> loaded.add(boms(cache, store, offline).load("0.11.0", false)));

            assertEquals(7, loaded.getFirst().deps().size());
            assertEquals(new ManagedDep("org.glassfish.web:el-impl", "${el-impl.version}"),
                    loaded.getFirst().deps().getLast());
            assertEquals(new ManagedDep("io.netty:netty-tcnative:windows-x86_64", "${tcnative.version}"),
                    loaded.getFirst().deps().get(5));
            List<String> warnings = log.lines().filter(line -> line.startsWith("warning: ")).toList();
            assertEquals(List.of("warning: bom 0.11.0: 6 managed dependencies keep unresolved references: "
                    + "${el-impl.version} (declared in io.quarkus:quarkus-bom:0.11.0): org.glassfish.web:el-impl; "
                    + "${tcnative.version} (declared in io.netty:netty-bom:4.1.68.Final, imported through "
                    + "io.netty:netty-bom:4.1.68.Final): io.netty:netty-tcnative, "
                    + "io.netty:netty-tcnative:linux-aarch_64, io.netty:netty-tcnative:linux-x86_64 and 2 more; "
                    + "neither the poms that declare them nor their parents define these properties; they are kept as "
                    + "written, as Maven keeps them"), warnings);
            assertTrue(log.contains("bom 0.11.0: resolved from 2 poms (parents: ; imported BOMs: 1; entries "
                    + "imported: 6)"), log);
        }
    }

    /// Where Maven fails to build the model, so does the release, writing nothing.
    @Test
    void whatMavenFailsOnStillFailsTheRelease() throws Exception {
        Map<String, String> boms = Map.of(
                "an import version that keeps a reference", managedBom("""
                        <dependency><groupId>io.netty</groupId><artifactId>netty-bom</artifactId>
                          <version>${netty.version}</version><type>pom</type><scope>import</scope></dependency>"""),
                "a parent version that keeps a reference", """
                        <project><parent><groupId>io.quarkus</groupId><artifactId>quarkus-parent</artifactId>
                          <version>${parent.version}</version></parent><artifactId>quarkus-bom</artifactId>
                          <version>1.0.0.Final</version></project>""",
                "an import cycle", managedBom("""
                        <dependency><groupId>io.quarkus</groupId><artifactId>quarkus-bom</artifactId>
                          <version>1.0.0.Final</version><type>pom</type><scope>import</scope></dependency>"""),
                "a property reference cycle", """
                        <project><groupId>io.quarkus</groupId><artifactId>quarkus-bom</artifactId>
                          <version>1.0.0.Final</version><properties><a>${b}</a><b>${a}</b></properties>
                          <dependencyManagement><dependencies><dependency><groupId>g</groupId><artifactId>x</artifactId>
                          <version>${a}</version></dependency></dependencies></dependencyManagement></project>""",
                "a property reference cycle that no managed dependency reads", """
                        <project><groupId>io.quarkus</groupId><artifactId>quarkus-bom</artifactId>
                          <version>1.0.0.Final</version><properties><a>pre-${a}</a></properties>
                          <dependencyManagement><dependencies><dependency><groupId>g</groupId><artifactId>x</artifactId>
                          <version>${unknown}</version></dependency></dependencies></dependencyManagement></project>""",
                "a property reference cycle on the machines that activate a JDK profile", """
                        <project><groupId>io.quarkus</groupId><artifactId>quarkus-bom</artifactId>
                          <version>1.0.0.Final</version><dependencyManagement><dependencies><dependency>
                          <groupId>g</groupId><artifactId>x</artifactId><version>1</version></dependency>
                          </dependencies></dependencyManagement><profiles><profile><id>jdk11</id><activation>
                          <jdk>[11,)</jdk></activation><properties><a>${b}</a><b>${a}</b></properties></profile>
                          </profiles></project>""",
                "a managed groupId that keeps a reference", managedBom("""
                        <dependency><groupId>${unknown.group}</groupId><artifactId>art</artifactId>
                          <version>1.0</version></dependency>"""),
                "a managed dependency without groupId", managedBom("""
                        <dependency><artifactId>art</artifactId><version>1.0</version></dependency>"""));
        try (ScratchDir dir = ScratchDir.create(); Http offline = new Http(1, true)) {
            for (Map.Entry<String, String> bom : boms.entrySet()) {
                Path cache = dir.resolve("cache-" + bom.getKey().replace(' ', '-'));
                cache(cache, "io.quarkus:quarkus-bom:1.0.0.Final", bom.getValue());
                SnapshotStore store = new SnapshotStore(dir.resolve("snapshots"), null);

                String log = log(() -> assertThrows(IOException.class,
                        () -> boms(cache, store, offline).load("1.0.0.Final", false), bom.getKey()));

                assertFalse(store.exists("1.0.0.Final", SnapshotStore.BOM), bom.getKey());
                assertFalse(log.contains("kept as written"), log);
            }
        }
    }

    /// A reference that Maven may resolve where the resolution does not look is not kept: the release fails.
    @Test
    void aReferenceThatMavenMayResolveFromTheModelOrTheMachineFailsTheRelease() throws Exception {
        Map<String, String> boms = Map.of(
                "a model element", """
                        <project><groupId>io.quarkus</groupId><artifactId>quarkus-bom</artifactId>
                          <version>1.0.0.Final</version><name>Quarkus - BOM</name><dependencyManagement><dependencies>
                          <dependency><groupId>org.acme</groupId><artifactId>named</artifactId>
                          <version>${project.name}</version></dependency></dependencies></dependencyManagement>
                        </project>""",
                "a property of the machine", managedBom("""
                        <dependency><groupId>org.acme</groupId><artifactId>jdk</artifactId>
                          <version>${java.version}</version></dependency>"""),
                "a property that only a JDK profile sets", """
                        <project><groupId>io.quarkus</groupId><artifactId>quarkus-bom</artifactId>
                          <version>1.0.0.Final</version><dependencyManagement><dependencies>
                          <dependency><groupId>org.acme</groupId><artifactId>lib</artifactId>
                          <version>${lib.version}</version></dependency></dependencies></dependencyManagement>
                          <profiles><profile><id>jdk11</id><activation><jdk>[11,)</jdk></activation>
                          <properties><lib.version>2.0</lib.version></properties></profile></profiles></project>""");
        Map<String, String> causes = Map.of(
                "a model element", "the managed dependency org.acme:named ",
                "a property of the machine", "the managed dependency org.acme:jdk ",
                "a property that only a JDK profile sets", "the profile jdk11 of io.quarkus:quarkus-bom:1.0.0.Final "
                        + "(JDK [11,)) sets the property lib.version, which the resolution of ");
        try (ScratchDir dir = ScratchDir.create(); Http offline = new Http(1, true)) {
            for (Map.Entry<String, String> bom : boms.entrySet()) {
                Path cache = dir.resolve("cache-" + bom.getKey().replace(' ', '-'));
                cache(cache, "io.quarkus:quarkus-bom:1.0.0.Final", bom.getValue());
                SnapshotStore store = new SnapshotStore(dir.resolve("snapshots"), null);

                String log = log(() -> {
                    IOException e = assertThrows(IOException.class,
                            () -> boms(cache, store, offline).load("1.0.0.Final", false), bom.getKey());
                    assertTrue(e.getMessage().startsWith(causes.get(bom.getKey())), e.getMessage());
                });

                assertFalse(store.exists("1.0.0.Final", SnapshotStore.BOM), bom.getKey());
                assertFalse(log.contains("kept as written"), log);
            }
        }
    }

    private static String managedBom(String dependency) {
        return "<project><groupId>io.quarkus</groupId><artifactId>quarkus-bom</artifactId><version>1.0.0.Final"
                + "</version><dependencyManagement><dependencies>" + dependency
                + "</dependencies></dependencyManagement></project>";
    }

    @Test
    void aBomWhoseParentOrImportIsMissingWritesNothing() throws Exception {
        try (ScratchDir dir = ScratchDir.create(); Http offline = new Http(1, true)) {
            for (String missing : List.of("io.quarkus:quarkus-parent:1.0.0.Final", "io.netty:netty-bom:4.1.42.Final")) {
                Path cache = dir.resolve("cache-" + missing.replace(':', '-'));
                cacheUnflattened(cache);
                String[] parts = missing.split(":");
                Path pom = cache.resolve(MavenRepo.artifactPath(parts[0], parts[1], parts[2], "pom"));
                Files.delete(pom);
                Files.writeString(pom.resolveSibling(pom.getFileName() + ".404"), "");
                SnapshotStore store = new SnapshotStore(dir.resolve("snapshots"), null);
                BomSnapshots boms = new BomSnapshots(
                        new MavenRepo(MavenRepo.CENTRAL, cache, offline, RemoteJar.Options.DEFAULT), store);

                IOException e = assertThrows(IOException.class, () -> boms.load("1.0.0.Final", false));

                assertTrue(e.getMessage().contains(missing) && e.getMessage().endsWith("is not in the repository"),
                        e.getMessage());
                assertFalse(store.exists("1.0.0.Final", SnapshotStore.BOM));
            }
        }
    }

    @Test
    void aReferenceThatIsNeverClosedIsKeptAsWrittenWithAWarning() throws Exception {
        String flattened = """
                <project>
                  <groupId>io.quarkus</groupId>
                  <artifactId>quarkus-bom</artifactId>
                  <version>2.4.1.Final</version>
                  <dependencyManagement><dependencies><dependency>
                    <groupId>io.netty</groupId><artifactId>netty-tcnative</artifactId><version>2.0.45-${oops</version>
                  </dependency></dependencies></dependencyManagement>
                </project>
                """;
        try (ScratchDir dir = ScratchDir.create(); Http offline = new Http(1, true)) {
            cache(dir.resolve("cache"), "io.quarkus:quarkus-bom:2.4.1.Final", flattened);
            SnapshotStore store = new SnapshotStore(dir.resolve("snapshots"), null);

            String log = log(() -> boms(dir.resolve("cache"), store, offline).load("2.4.1.Final", false));

            assertEquals(List.of(new ManagedDep("io.netty:netty-tcnative", "2.0.45-${oops")),
                    store.read("2.4.1.Final", SnapshotStore.BOM, BomSnapshot.class).deps());
            assertTrue(log.contains("warning: bom 2.4.1.Final: 1 managed dependency keeps unresolved references: "
                    + "${oops (declared in io.quarkus:quarkus-bom:2.4.1.Final): io.netty:netty-tcnative;"), log);
        }
    }

    @Test
    void tellsUnresolvedSnapshotEntries() {
        assertTrue(BomSnapshots.unresolved(new ManagedDep("io.netty:netty-tcnative", "${tcnative.version}")));
        assertTrue(BomSnapshots.unresolved(new ManagedDep("${group}:a", "1")));
        assertFalse(BomSnapshots.unresolved(
                new ManagedDep("io.quarkus:quarkus-bom-quarkus-platform-descriptor:json:${quarkus.version}", "1")));
        assertTrue(BomSnapshots.unresolved(new ManagedDep("io.netty:netty-tcnative", "2.0.45-${oops")));
        assertFalse(BomSnapshots.unresolved(new ManagedDep("io.netty:netty-tcnative", null)));
    }
}
