package io.quarkiverse.almanac.config.bytecode;

import com.sun.net.httpserver.HttpServer;
import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.config.ConfigExtractor;
import io.quarkiverse.almanac.maven.Http;
import io.quarkiverse.almanac.maven.MavenRepo;
import io.quarkiverse.almanac.maven.Pacer;
import io.quarkiverse.almanac.maven.RemoteJar;
import io.quarkiverse.almanac.model.ArtifactClasses;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ClassesSnapshot;
import io.quarkiverse.almanac.model.ConfigProperty;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.util.AtomicFiles;
import io.quarkiverse.almanac.util.SnapshotStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The extractor with the bytecode reader on an offline repository whose cache holds a fixture release, as
/// `config --offline` runs it: the `@ConfigDoc*` annotations of releases before 3.14 come from cached `-sources` jars
/// (the real [io.quarkiverse.almanac.config.sourcedoc.SourceDocAnnotations]), and a release that cannot be read completely leaves
/// no snapshot behind, nor does one read while a local repository refuses the requests.
class OfflineExtractionTest {

    private static final String ACME = "io.quarkus.acme.AcmeConfig";
    private static final String ACME_SOURCE = """
            package io.quarkus.acme;

            import io.quarkus.runtime.annotations.*;
            import io.smallrye.config.*;
            import java.util.*;

            @ConfigMapping(prefix = "quarkus.acme")
            @ConfigRoot(phase = ConfigPhase.RUN_TIME)
            public interface AcmeConfig {
                /** Named data sources. */
                @WithParentName @WithUnnamedKey @ConfigDocMapKey("datasource-name")
                Map<String, Named> namedDataSources();

                /** The dialect. */
                @ConfigDocDefault("depends on the database")
                Optional<String> dialect();

                /** Hidden. */
                @ConfigDocIgnore
                Optional<Boolean> optimizeContexts();

                interface Named {
                    /** The URL. */
                    Optional<String> url();
                }
            }
            """;
    private static final String OTHER = "io.quarkus.other.OtherConfig";
    private static final String OTHER_SOURCE = """
            package io.quarkus.other;

            import io.quarkus.runtime.annotations.*;
            import io.smallrye.config.*;
            import java.util.*;

            @ConfigMapping(prefix = "quarkus.other")
            @ConfigRoot
            public interface OtherConfig {
                @ConfigDocMapKey("label") Map<String, String> labels();
            }
            """;
    private static final String TLS = "io.quarkus.tls.TlsConfig";
    private static final String TLS_SOURCE = """
            package io.quarkus.tls;

            import io.quarkus.runtime.annotations.*;
            import io.smallrye.config.*;
            import java.util.*;

            @ConfigMapping(prefix = "quarkus.tls")
            @ConfigRoot(phase = ConfigPhase.RUN_TIME)
            public interface TlsConfig {
                /** Client authentication. */
                Optional<io.vertx.core.http.ClientAuth> clientAuth();
            }
            """;
    /// A third-party enum: compiled for the config class above, in no jar of the cache.
    private static final String CLIENT_AUTH = "io.vertx.core.http.ClientAuth";
    private static final String CLIENT_AUTH_SOURCE = """
            package io.vertx.core.http;

            public enum ClientAuth { NONE, REQUEST, REQUIRED }
            """;
    private static final String EXTENSION_YAML = "META-INF/quarkus-extension.yaml";

    private static Map<String, byte[]> classes;

    private ScratchDir dir;

    @BeforeAll
    static void compile() {
        classes = JavaSources.withConfigAnnotations(false).add(ACME, ACME_SOURCE).add(OTHER, OTHER_SOURCE)
                .add(TLS, TLS_SOURCE).add(CLIENT_AUTH, CLIENT_AUTH_SOURCE).compile();
    }

    @BeforeEach
    void createDir() throws IOException {
        dir = ScratchDir.create();
    }

    @AfterEach
    void deleteDir() throws InterruptedException {
        dir.close();
    }

    private Path cacheFile(String artifactId, String version, String classifier) {
        String name = artifactId + "-" + version + (classifier == null ? "" : "-" + classifier) + ".jar";
        return dir.resolve("cache").resolve("io/quarkus").resolve(artifactId).resolve(version).resolve(name);
    }

    /// Caches a whole jar.
    private void cacheJar(String artifactId, String version, String classifier, Map<String, byte[]> entries)
            throws IOException {
        Path file = cacheFile(artifactId, version, classifier);
        Files.createDirectories(file.getParent());
        Files.write(file, zip(entries));
    }

    /// Caches only the central directory of a jar, as indexing does for a jar whose entries were never read.
    private void cacheCentralDirectoryOnly(String artifactId, String version, Map<String, byte[]> entries)
            throws IOException {
        byte[] zip = zip(entries);
        // the end-of-central-directory record is the last 22 bytes of a zip without comment
        ByteBuffer bytesLittleEndian = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);
        int cdSize = bytesLittleEndian.getInt(zip.length - 22 + 12);
        int cdOffset = bytesLittleEndian.getInt(zip.length - 22 + 16);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x41434431);
            out.writeLong(zip.length);
            out.write(Arrays.copyOfRange(zip, cdOffset, cdOffset + cdSize));
        }
        Path file = cacheFile(artifactId, version, null);
        Files.createDirectories(file.getParent());
        Files.write(file.resolveSibling(file.getFileName() + ".cd"), bytes.toByteArray());
    }

    /// Caches the 404 answer for a jar.
    private void cacheMissing(String artifactId, String version, String classifier) throws IOException {
        Path file = cacheFile(artifactId, version, classifier);
        Files.createDirectories(file.getParent());
        Files.write(file.resolveSibling(file.getFileName() + ".404"), new byte[0]);
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Map<String, byte[]> binaryJar(String root, String... internalNames) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String name : internalNames) {
            entries.put(name + ".class", classes.get(name));
        }
        entries.put(BytecodeConfigReader.ROOTS_ENTRY, (root + "\n").getBytes(StandardCharsets.UTF_8));
        entries.put(EXTENSION_YAML, "name: Acme\nmetadata:\n  status: stable\n".getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    private static Map<String, byte[]> acmeJar() {
        return binaryJar(ACME, "io/quarkus/acme/AcmeConfig", "io/quarkus/acme/AcmeConfig$Named");
    }

    private static Map<String, byte[]> sourcesJar(String path, String source) {
        return Map.of(path, source.getBytes(StandardCharsets.UTF_8));
    }

    private static BomSnapshot bom(String version, String... artifactIds) {
        return new BomSnapshot(version, "2026-01-01", Arrays.stream(artifactIds)
                .map(artifactId -> new ManagedDep("io.quarkus:" + artifactId, version)).toList());
    }

    /// Extracts a release offline with the bytecode reader.
    private boolean extract(BomSnapshot bom, ConfigExtractor.Source source, ConfigExtractor.DocAnnotationMode mode)
            throws IOException {
        try (Http http = new Http(1, true)) {
            MavenRepo repo = new MavenRepo(MavenRepo.CENTRAL, dir.resolve("cache"), http, RemoteJar.Options.DEFAULT);
            return new ConfigExtractor(repo, store(), mode).extract(bom, source, true);
        }
    }

    private SnapshotStore store() {
        return new SnapshotStore(dir.resolve("snapshots"));
    }

    private ConfigSnapshot config(String version) throws IOException {
        return store().read(version, SnapshotStore.CONFIG, ConfigSnapshot.class);
    }

    private static ConfigProperty property(ConfigSnapshot snapshot, String key) {
        return snapshot.properties().stream().filter(p -> p.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no key " + key + " in "
                        + snapshot.properties().stream().map(ConfigProperty::key).toList()));
    }

    private static List<String> docWarnings(ConfigSnapshot snapshot) {
        return snapshot.warnings().stream().filter(w -> w.startsWith("doc annotations")).toList();
    }

    @Test
    void writesConfigAndExtensionsTogether() throws IOException {
        cacheJar("quarkus-acme", "3.99.0", null, acmeJar());
        // a jar without config, known by its central directory only, and one that does not exist
        cacheCentralDirectoryOnly("quarkus-acme-spi", "3.99.0", Map.of("io/quarkus/acme/spi/AcmeBuildItem.class",
                new byte[] {1}, "io/quarkus/acme/spi/package-info.class", new byte[] {1}));
        cacheMissing("quarkus-acme-gone", "3.99.0", null);
        // nor a pom: no relocation
        Files.write(cacheFile("quarkus-acme-gone", "3.99.0", null).resolveSibling("quarkus-acme-gone-3.99.0.pom.404"),
                new byte[0]);
        assertTrue(extract(bom("3.99.0", "quarkus-acme", "quarkus-acme-gone", "quarkus-acme-spi"),
                ConfigExtractor.Source.BOTH, ConfigExtractor.DocAnnotationMode.SOURCES));
        SnapshotStore store = store();
        assertTrue(store.exists("3.99.0", SnapshotStore.CONFIG));
        assertTrue(store.exists("3.99.0", SnapshotStore.CONFIG_BYTECODE));
        assertTrue(store.exists("3.99.0", SnapshotStore.EXTENSIONS));
        // the classes of every jar, from the central directories read anyway
        assertEquals(new ClassesSnapshot("3.99.0", List.of(
                new ArtifactClasses("quarkus-acme", List.of("io.quarkus.acme.AcmeConfig",
                        "io.quarkus.acme.AcmeConfig$Named")),
                new ArtifactClasses("quarkus-acme-spi", List.of("io.quarkus.acme.spi.AcmeBuildItem")))),
                store.read("3.99.0", SnapshotStore.CLASSES, ClassesSnapshot.class));
        // no model in the release: config.json is the bytecode snapshot
        assertFalse(store.exists("3.99.0", SnapshotStore.CONFIG_MODEL));
        assertEquals("bytecode", config("3.99.0").source());
        // 3.14+ releases never consult the sources jars, and read the class files only without a warning
        assertTrue(docWarnings(config("3.99.0")).isEmpty(), config("3.99.0").warnings().toString());
    }

    @Test
    void writesNothingWhenTheExtensionMetadataCannotBeRead() throws IOException {
        cacheJar("quarkus-acme", "3.99.0", null, acmeJar());
        // indexed once, but the entry itself was never downloaded: offline it cannot be read
        cacheCentralDirectoryOnly("quarkus-broken", "3.99.0", Map.of("x/Dummy.class", new byte[] {1},
                EXTENSION_YAML, "name: Broken\n".getBytes(StandardCharsets.UTF_8)));
        BomSnapshot bom = bom("3.99.0", "quarkus-acme", "quarkus-broken");
        for (ConfigExtractor.Source source : List.of(ConfigExtractor.Source.BYTECODE, ConfigExtractor.Source.BOTH)) {
            IOException e = assertThrows(IOException.class,
                    () -> extract(bom, source, ConfigExtractor.DocAnnotationMode.SOURCES));
            assertTrue(e.getMessage().contains("offline"), e.getMessage());
            for (String file : List.of(SnapshotStore.CONFIG, SnapshotStore.CONFIG_BYTECODE, SnapshotStore.EXTENSIONS,
                    SnapshotStore.CLASSES)) {
                assertFalse(store().exists("3.99.0", file), source + " wrote " + file);
            }
        }
    }

    @Test
    void writesNothingWhenRequestsStopWhileTheReleaseIsRead() throws IOException {
        cacheJar("quarkus-tls", "3.99.0", null, binaryJar(TLS, "io/quarkus/tls/TlsConfig"));
        cacheJar("quarkus-acme", "3.99.1", null, acmeJar());
        List<String> requested = new CopyOnWriteArrayList<>();
        HttpServer refusing = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        refusing.createContext("/", exchange -> {
            requested.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        refusing.start();
        try (Http http = new Http(1, false, Pacer.none())) {
            URI base = URI.create("http://" + refusing.getAddress().getHostString() + ":"
                    + refusing.getAddress().getPort() + "/maven2/");
            ConfigExtractor extractor = new ConfigExtractor(
                    new MavenRepo(base, dir.resolve("cache"), http, RemoteJar.Options.DEFAULT), store(),
                    ConfigExtractor.DocAnnotationMode.SOURCES);
            // the enum of a config member is in a third-party jar: the reader takes the 403 for an unreadable jar,
            // which would be a mere warning
            BomSnapshot tls = new BomSnapshot("3.99.0", "2026-01-01", List.of(
                    new ManagedDep("io.quarkus:quarkus-tls", "3.99.0"),
                    new ManagedDep("io.vertx:vertx-core", "4.5.1")));
            Http.StoppedException stopped = assertThrows(Http.StoppedException.class,
                    () -> extractor.extract(tls, ConfigExtractor.Source.BYTECODE, true));
            assertTrue(stopped.getMessage().contains("HTTP 403 for " + base
                    + "io/vertx/vertx-core/4.5.1/vertx-core-4.5.1.jar"), stopped.getMessage());
            assertEquals(List.of("/maven2/io/vertx/vertx-core/4.5.1/vertx-core-4.5.1.jar"), requested);

            // once requests stopped, not even a release the cache holds whole is written
            assertThrows(Http.StoppedException.class, () -> extractor.extract(bom("3.99.1", "quarkus-acme"),
                    ConfigExtractor.Source.BYTECODE, true));
            assertEquals(1, requested.size());
        } finally {
            refusing.stop(0);
        }
        for (String version : List.of("3.99.0", "3.99.1")) {
            for (String file : List.of(SnapshotStore.CONFIG, SnapshotStore.EXTENSIONS)) {
                assertFalse(store().exists(version, file), version + ": " + file + " written");
            }
        }
    }

    /// Whatever request of a release is refused, by a 403 or by the stop marker of another command on the same
    /// cache, no snapshot is written; a stop recorded after the last request takes nothing away.
    @Test
    void writesNothingWhicheverRequestOfTheReleaseIsRefused() throws IOException {
        Map<String, byte[]> repository = new HashMap<>();
        repository.put("io/quarkus/quarkus-acme/3.8.6/quarkus-acme-3.8.6.jar", zip(acmeJar()));
        repository.put("io/quarkus/quarkus-acme/3.8.6/quarkus-acme-3.8.6-sources.jar",
                zip(sourcesJar("io/quarkus/acme/AcmeConfig.java", ACME_SOURCE)));
        repository.put("io/quarkus/quarkus-tls/3.8.6/quarkus-tls-3.8.6.jar",
                zip(binaryJar(TLS, "io/quarkus/tls/TlsConfig")));
        repository.put("io/vertx/vertx-core/4.5.1/vertx-core-4.5.1.jar",
                zip(Map.of("io/vertx/core/http/ClientAuth.class", classes.get("io/vertx/core/http/ClientAuth"))));
        BomSnapshot bom = new BomSnapshot("3.8.6", "2026-01-01", List.of(
                new ManagedDep("io.quarkus:quarkus-acme", "3.8.6"), new ManagedDep("io.quarkus:quarkus-tls", "3.8.6"),
                new ManagedDep("io.vertx:vertx-core", "4.5.1")));

        Served all = extractServed(repository, bom, "all", 0, false);
        assertNull(all.failure());
        // the release needs every request: the sources jar for its doc annotations, the third-party jar for its enum
        ConfigSnapshot snapshot = all.store().read("3.8.6", SnapshotStore.CONFIG, ConfigSnapshot.class);
        assertEquals("depends on the database", property(snapshot, "quarkus.acme.dialect").defaultValue());
        assertEquals(List.of("none", "request", "required"),
                property(snapshot, "quarkus.tls.client-auth").enumValues());
        assertTrue(all.requests() >= 4, all.requests() + " requests");

        for (int refused = 1; refused <= all.requests(); refused++) {
            Served refusal = extractServed(repository, bom, "refused-" + refused, refused, false);
            assertNotNull(refusal.failure(), "request " + refused + " refused");
            assertTrue(refusal.stopped(), "request " + refused + " refused");
            Served marker = extractServed(repository, bom, "marker-" + refused, refused, true);
            // the marker refuses the requests after the one that wrote it
            boolean lastRequest = refused == all.requests();
            assertEquals(lastRequest, marker.failure() == null, "marker after request " + refused + ": "
                    + marker.failure());
            for (String file : List.of(SnapshotStore.CONFIG, SnapshotStore.EXTENSIONS)) {
                assertFalse(refusal.store().exists("3.8.6", file), "403 at request " + refused + ": " + file);
                assertEquals(lastRequest, marker.store().exists("3.8.6", file), "marker after request " + refused
                        + ": " + file);
            }
        }
    }

    /// What an extraction from a local repository did.
    ///
    /// @param requests the requests that reached the repository
    /// @param failure  what the extraction threw, null when it wrote its snapshots
    /// @param stopped  whether the client's requests stopped
    private record Served(int requests, IOException failure, boolean stopped, SnapshotStore store) {
    }

    /// Extracts a release with the bytecode reader from a local repository serving `files` by path; `refuseAt`
    /// (counted from 1, 0 for never) is the request answered with a 403, or, with `marker`, the answered request
    /// after which another command on the same cache records a stop of an hour.
    private Served extractServed(Map<String, byte[]> files, BomSnapshot bom, String run, int refuseAt, boolean marker)
            throws IOException {
        Path cache = dir.resolve(run).resolve("cache");
        Path stopFile = cache.resolve(Pacer.STOP_FILE);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            int request = requests.incrementAndGet();
            byte[] body = files.get(exchange.getRequestURI().getPath().substring("/maven2/".length()));
            if (request == refuseAt && marker) {
                AtomicFiles.writeString(stopFile, (System.currentTimeMillis() + 3_600_000) + "\nHTTP 403 elsewhere\n");
            } else if (request == refuseAt) {
                body = null;
                exchange.sendResponseHeaders(403, -1);
            }
            if (body != null) {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else if (request != refuseAt || marker) {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        server.start();
        SnapshotStore store = new SnapshotStore(dir.resolve(run).resolve("snapshots"));
        try (Http http = new Http(4, false, Pacer.shared(Pacer.Pause.parse("0.001"), cache.resolve(Pacer.LOCK_FILE)))) {
            URI base = URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort()
                    + "/maven2/");
            ConfigExtractor extractor = new ConfigExtractor(new MavenRepo(base, cache, http, RemoteJar.Options.DEFAULT),
                    store, ConfigExtractor.DocAnnotationMode.SOURCES);
            IOException failure = null;
            try {
                assertTrue(extractor.extract(bom, ConfigExtractor.Source.BYTECODE, true));
            } catch (IOException e) {
                failure = e;
            }
            return new Served(requests.get(), failure, http.stopped(), store);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void appliesDocAnnotationsFromCachedSourcesJars() throws IOException {
        cacheJar("quarkus-acme", "3.8.6", null, acmeJar());
        cacheJar("quarkus-acme", "3.8.6", "sources", sourcesJar("io/quarkus/acme/AcmeConfig.java", ACME_SOURCE));
        cacheJar("quarkus-other", "3.8.6", null, binaryJar(OTHER, "io/quarkus/other/OtherConfig"));
        cacheMissing("quarkus-other", "3.8.6", "sources");
        assertTrue(extract(bom("3.8.6", "quarkus-acme", "quarkus-other"), ConfigExtractor.Source.BYTECODE,
                ConfigExtractor.DocAnnotationMode.SOURCES));
        ConfigSnapshot snapshot = config("3.8.6");
        // the annotations of the sources jar apply as if the class files had them
        assertEquals(List.of("quarkus.acme.\"datasource-name\".url"), property(snapshot, "quarkus.acme.url").aliases());
        assertEquals("depends on the database", property(snapshot, "quarkus.acme.dialect").defaultValue());
        assertTrue(snapshot.properties().stream().noneMatch(p -> p.key().equals("quarkus.acme.optimize-contexts")));
        // a jar without sources keeps the class-file names, and the snapshot says why
        assertEquals("quarkus.other.labels.\"labels\"", property(snapshot, "quarkus.other.labels.*").name());
        List<String> warnings = docWarnings(snapshot);
        assertEquals(1, warnings.size(), snapshot.warnings().toString());
        assertTrue(warnings.getFirst().startsWith("doc annotations: no sources jar for io.quarkus:quarkus-other:3.8.6"),
                warnings.getFirst());
    }

    @Test
    void readsClassFilesOnlyWhenNoSourcesJarIsCached() throws IOException {
        cacheJar("quarkus-acme", "3.8.6", null, acmeJar());
        assertTrue(extract(bom("3.8.6", "quarkus-acme"), ConfigExtractor.Source.BYTECODE,
                ConfigExtractor.DocAnnotationMode.SOURCES));
        ConfigSnapshot offline = config("3.8.6");
        List<String> warnings = docWarnings(offline);
        assertEquals(1, warnings.size(), offline.warnings().toString());
        assertTrue(warnings.getFirst().startsWith("doc annotations unavailable: the lookup failed for all 2 types"),
                warnings.getFirst());
        assertTrue(warnings.getFirst().contains("offline mode"), warnings.getFirst());
        assertEquals(List.of("quarkus.acme.\"named-data-sources\".url"),
                property(offline, "quarkus.acme.url").aliases());
        // the same snapshot as a read of the class files only, but for the warning
        assertTrue(extract(bom("3.8.6", "quarkus-acme"), ConfigExtractor.Source.BYTECODE,
                ConfigExtractor.DocAnnotationMode.NONE));
        ConfigSnapshot none = config("3.8.6");
        assertEquals(none.properties(), offline.properties());
        assertTrue(none.warnings().getFirst().startsWith("doc annotations unavailable: the class files"),
                none.warnings().toString());
    }
}
