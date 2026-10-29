package io.quarkiverse.almanac.assemble;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.maven.VersionSelector;
import io.quarkiverse.almanac.model.ApiSnapshot;
import io.quarkiverse.almanac.model.ArtifactApi;
import io.quarkiverse.almanac.model.ArtifactClasses;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ClassApi;
import io.quarkiverse.almanac.model.GitSource;
import io.quarkiverse.almanac.model.ClassesSnapshot;
import io.quarkiverse.almanac.model.ConfigProperty;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.model.ExtensionInfo;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.model.MemberApi;
import io.quarkiverse.almanac.util.AtomicFiles;
import io.quarkiverse.almanac.util.ConfigKeys;
import io.quarkiverse.almanac.util.Json;
import io.quarkiverse.almanac.util.SnapshotStore;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Synthetic releases: 3.0.0.Final (BOM only), 3.2.0.Final (bytecode-era config), 3.3.0 and 4.0.0.Beta1 (model).
class HistoryBuilderTest {

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00.123Z");
    private static final String NAMED_URL = "quarkus.datasource.\"datasource-name\".jdbc.url";

    private static final List<HistoryBuilder.Release> RELEASES = List.of(
            new HistoryBuilder.Release(bom("3.0.0.Final", "2023-04-26",
                    dep("io.quarkus:quarkus-core", "3.0.0.Final"), dep("io.vertx:vertx-core", "4.4.1")), null, null),
            new HistoryBuilder.Release(bom("3.2.0.Final", "2023-07-05",
                    dep("io.quarkus:quarkus-core", "3.2.0.Final"), dep("io.vertx:vertx-core", "4.4.1")),
                    config("3.2.0.Final", "bytecode",
                            prop("quarkus.datasource.jdbc.url", List.of(), "string", null, "RUN_TIME", null, "JDBC URL."),
                            prop(NAMED_URL, List.of(), "string", null, "RUN_TIME", null, null),
                            prop("quarkus.http.port", List.of(), "int", "8080", "RUN_TIME", null, "Old port."),
                            prop("quarkus.log.category.\"categories\".level", List.of(), "Level", "inherit",
                                    "BUILD_AND_RUN_TIME_FIXED", null, null),
                            prop("quarkus.old.flag", List.of(), "boolean", "false", "BUILD_TIME", null, null)),
                    extensions("3.2.0.Final",
                            extension("quarkus-agroal", "Agroal", List.of("quarkus.datasource"), null),
                            extension("quarkus-resteasy-reactive", "RESTEasy Reactive", List.of(), null))),
            new HistoryBuilder.Release(bom("3.3.0", "2023-08-23",
                    dep("io.netty:netty-common", "4.1.100.Final"), dep("io.quarkus:quarkus-core", "3.3.0"),
                    dep("io.vertx:vertx-core", "4.4.4")),
                    config("3.3.0", "model",
                            prop("quarkus.datasource.jdbc.url", List.of(NAMED_URL), "string", null, "RUN_TIME", null,
                                    "The JDBC URL."),
                            prop("quarkus.http.port", List.of(), "int", "8081", "RUN_TIME", null, "The HTTP port."),
                            prop("quarkus.log.category.\"categories\".level", List.of(), "Level", "inherit",
                                    "BUILD_AND_RUN_TIME_FIXED", null, null),
                            prop("quarkus.new.flag", List.of(), "boolean", null, "RUN_TIME", "", null)),
                    extensions("3.3.0",
                            extension("quarkus-agroal", "Agroal", List.of("quarkus.datasource"), null),
                            extension("quarkus-rest", "REST", List.of(), null),
                            new ExtensionInfo("quarkus-resteasy-reactive", null, null, null, null, null, false, null,
                                    "io.quarkus:quarkus-rest"))),
            new HistoryBuilder.Release(bom("4.0.0.Beta1", "2026-10-19",
                    dep("io.netty:netty-common", "4.1.100.Final"), dep("io.quarkus:quarkus-core", "4.0.0.Beta1")),
                    config("4.0.0.Beta1", "model",
                            prop("quarkus.datasource.jdbc.url", List.of(NAMED_URL), "string", null, "RUN_TIME", null,
                                    null),
                            prop("quarkus.http.port", List.of(), "int", "8080", "RUN_TIME", null, null),
                            prop("quarkus.log.category.\"categories\".level", List.of(), "Level", "inherit",
                                    "BUILD_AND_RUN_TIME_FIXED", null, null),
                            prop("quarkus.new.flag", List.of(), "boolean", null, "RUN_TIME", "", null)),
                    extensions("4.0.0.Beta1",
                            extension("quarkus-agroal", "Agroal", List.of("quarkus.datasource"), null),
                            extension("quarkus-rest", "REST", List.of(), null),
                            new ExtensionInfo("quarkus-resteasy-reactive", null, null, null, null, null, false, null,
                                    "io.quarkus:quarkus-rest"))));

    /// A pre-release between 3.2.0.Final and 3.3.0 with a BOM snapshot but no config snapshot.
    private static final HistoryBuilder.Release PRE_RELEASE = new HistoryBuilder.Release(bom("3.3.0.CR1", "2023-08-16",
            dep("io.quarkus:quarkus-core", "3.3.0.CR1"), dep("io.vertx:vertx-core", "4.4.4")), null, null);

    /// A release before the others with an extensions snapshot (from its extension catalog) but no config snapshot.
    private static final HistoryBuilder.Release EXTENSIONS_ONLY = new HistoryBuilder.Release(bom("2.16.12.Final",
            "2023-10-16", dep("io.quarkus:quarkus-core", "2.16.12.Final"), dep("io.vertx:vertx-core", "4.3.7")), null,
            extensions("2.16.12.Final",
                    extension("quarkus-agroal", "Agroal", List.of("quarkus.datasource"), null),
                    new ExtensionInfo("quarkus-resteasy-reactive", "RESTEasy Reactive", null, "preview",
                            List.of("data"), null, false, List.of(), null)));

    @Test
    void versionsListEveryReleaseWithItsFlags() throws IOException {
        HistoryBuilder.SiteData data = build();
        assertEquals("2026-10-27T12:00:00Z", data.versions().get("generatedAt").asText());
        assertEquals(json("[\"3.2\",\"3.8\",\"3.15\",\"3.20\",\"3.27\",\"3.33\",\"3.40\"]"), data.versions().get("lts"));
        assertEquals(json("""
                [{"v":"3.0.0.Final","date":"2023-04-26","stream":"3.0","pre":false,"lts":false,"config":false,
                  "ext":false,"classes":false},
                 {"v":"3.2.0.Final","date":"2023-07-05","stream":"3.2","pre":false,"lts":true,"config":true,
                  "configSource":"bytecode","ext":true,"classes":false},
                 {"v":"3.3.0","date":"2023-08-23","stream":"3.3","pre":false,"lts":false,"config":true,
                  "configSource":"model","ext":true,"classes":false},
                 {"v":"4.0.0.Beta1","date":"2026-10-19","stream":"4.0","pre":true,"lts":false,"config":true,
                  "configSource":"model","ext":true,"classes":false}]
                """), data.versions().get("versions"));
        // the flags in a fixed order, classes last
        assertEquals("{\"v\":\"3.2.0.Final\",\"date\":\"2023-07-05\",\"stream\":\"3.2\",\"pre\":false,\"lts\":true,"
                + "\"config\":true,\"configSource\":\"bytecode\",\"ext\":true,\"classes\":false}",
                data.versions().get("versions").get(1).toString());
    }

    @Test
    void aReleaseFromItsGitTagSaysSoAndThePreLaunchReleasesAreListedForReference() throws IOException {
        HistoryBuilder builder = new HistoryBuilder();
        builder.add(gitRelease("0.10.0", "f26057191b8217f43b632e2eb5b3541821cf242d", "io.quarkus:quarkus-bom"));
        RELEASES.forEach(builder::add);
        HistoryBuilder.SiteData alone = builder.build(NOW);
        assertFalse(alone.versions().has("reference"), "without 0.2.0 and 0.9.1, which they are listed next to");

        builder = new HistoryBuilder();
        builder.add(gitRelease("0.2.0", "97a42b3fc1fd62b8e7a8ba61b9016324e9bcd20c", "org.jboss.shamrock:shamrock-bom"));
        builder.add(gitRelease("0.9.1", "66ab40ef2921a6dd839ef7d815a3c57c8aeecadd", "org.jboss.shamrock:shamrock-bom"));
        builder.add(gitRelease("0.10.0", "f26057191b8217f43b632e2eb5b3541821cf242d", "io.quarkus:quarkus-bom"));
        RELEASES.forEach(builder::add);
        HistoryBuilder.SiteData data = builder.build(NOW);

        assertEquals("{\"v\":\"0.10.0\",\"date\":\"2019-03-01\",\"stream\":\"0.10\",\"pre\":false,\"lts\":false,"
                        + "\"config\":false,\"ext\":false,\"classes\":false,\"git\":{\"tag\":\"0.10.0\",\"commit\":"
                        + "\"f26057191b8217f43b632e2eb5b3541821cf242d\",\"bom\":\"io.quarkus:quarkus-bom\"}}",
                data.versions().get("versions").get(2).toString());
        assertEquals("{\"v\":\"0.9.0\",\"date\":\"2019-02-14\",\"stream\":\"0.9\",\"commit\":"
                        + "\"01ddfa0e920a39b7ddb541408ff5341405c5fe00\",\"why\":\"canceled\"}",
                data.versions().get("reference").get(2).toString());
        assertEquals(3, data.versions().get("reference").size());
        assertFalse(build().versions().has("reference"), "listed only next to releases from git tags");
    }

    /// A pre-launch release whose BOM comes from its git tag, managing one module.
    private static HistoryBuilder.Release gitRelease(String version, String commit, String bom) {
        String module = bom.substring(0, bom.indexOf(':')) + ":module-deployment";
        return new HistoryBuilder.Release(new BomSnapshot(version, "2019-03-01", new GitSource(version, commit, bom),
                List.of(dep(module, version))), null, null);
    }

    @Test
    void aReleaseTaggedWithoutAGitHubReleaseSaysSo() throws IOException {
        HistoryBuilder builder = new HistoryBuilder();
        builder.add(new HistoryBuilder.Release(bom("3.17.0", "2024-11-27", dep("io.quarkus:quarkus-core", "3.17.0")), null,
                null));
        builder.add(new HistoryBuilder.Release(bom("3.17.1", "2024-12-04", dep("io.quarkus:quarkus-core", "3.17.1")), null,
                null));
        HistoryBuilder.SiteData data = builder.build(NOW);
        assertFalse(data.versions().get("versions").get(0).has("ghRelease"), "it has one");
        assertEquals("{\"v\":\"3.17.1\",\"date\":\"2024-12-04\",\"stream\":\"3.17\",\"pre\":false,\"lts\":false,"
                + "\"config\":false,\"ext\":false,\"classes\":false,\"ghRelease\":false}",
                data.versions().get("versions").get(1).toString());
    }

    @Test
    void releasesWithExtensionsButNoConfigAreExtensionVersionsOnly() throws IOException {
        HistoryBuilder builder = new HistoryBuilder();
        builder.add(EXTENSIONS_ONLY);
        RELEASES.forEach(builder::add);
        HistoryBuilder.SiteData data = builder.build(NOW);

        assertEquals(json("""
                {"v":"2.16.12.Final","date":"2023-10-16","stream":"2.16","pre":false,"lts":false,"config":false,
                 "ext":true,"classes":false}
                """), data.versions().get("versions").get(0));
        assertEquals(json("[2,3,4]"), data.config().get("versions"), "no config version");
        assertEquals(json("""
                [{"n":"Agroal","s":"stable","c":["data"],"p":["quarkus.datasource"]},
                 {"n":"RESTEasy Reactive","s":"preview","c":["data"]},
                 {"n":"RESTEasy Reactive","s":"stable","c":["data"]},
                 {"n":"REST","s":"stable","c":["data"]},
                 {"r":"io.quarkus:quarkus-rest"}]
                """), data.extensions().get("states"));
        // 3.0.0.Final has no extensions snapshot: no change point, nothing removed there
        assertEquals(json("""
                [{"id":"quarkus-agroal","h":[[0,0]]},
                 {"id":"quarkus-rest","h":[[3,3]]},
                 {"id":"quarkus-resteasy-reactive","h":[[0,1],[2,2],[3,4]]}]
                """), data.extensions().get("exts"));
    }

    @Test
    void aConfigSnapshotNeedsItsExtensions() {
        BomSnapshot bom = RELEASES.get(1).bom();
        ConfigSnapshot config = RELEASES.get(1).config();
        assertThrows(IllegalArgumentException.class, () -> new HistoryBuilder.Release(bom, config, null));
        // a 0.x release has no extension catalog: its config comes alone
        assertTrue(new HistoryBuilder.Release(bom("0.26.1", "2019-11-01", dep("io.quarkus:quarkus-core", "0.26.1")),
                config("0.26.1", "bytecode"), null).hasConfig());
        assertFalse(new HistoryBuilder.Release(bom, null, RELEASES.get(1).extensions()).hasConfig());
    }

    @Test
    void bomHistoriesUseChangePointsAndTheSameAsReleaseMarker() throws IOException {
        assertEquals(json("""
                [{"id":"io.netty:netty-common","h":[[2,"4.1.100.Final"]]},
                 {"id":"io.quarkus:quarkus-core","h":[[0,"="]]},
                 {"id":"io.vertx:vertx-core","h":[[0,"4.4.1"],[2,"4.4.4"],[3,null]]}]
                """), build().bom().get("artifacts"));
    }

    @Test
    void platformDataKeepsWhereThePlatformBomManagesOtherwise() throws IOException {
        String descriptor = "io.quarkus.platform:quarkus-bom-quarkus-platform-descriptor:json:${quarkus.version}";
        HistoryBuilder builder = new HistoryBuilder();
        builder.add(RELEASES.get(0));
        HistoryBuilder.Release r1 = RELEASES.get(1);
        builder.add(new HistoryBuilder.Release(r1.bom(), r1.config(), r1.extensions(), null, bom("3.2.0.Final",
                "2023-07-07", dep(descriptor, "3.2.0.Final"), dep("io.quarkus:quarkus-core", "3.2.0.Final"),
                dep("io.vertx:vertx-core", "4.4.2"))));
        HistoryBuilder.Release r2 = RELEASES.get(2);
        builder.add(new HistoryBuilder.Release(r2.bom(), r2.config(), r2.extensions(), null, bom("3.3.0", null,
                dep(descriptor, "3.3.0"), dep("io.quarkus:quarkus-core", "3.3.0"), dep("io.vertx:vertx-core",
                        "4.4.4"))));
        builder.add(RELEASES.get(3));
        // over 3.2.0.Final and 3.3.0 alone: vertx-core managed alike at 3.3.0, netty-common by the BOM only
        assertEquals(json("""
                {"versions":[1,2],
                 "artifacts":[{"id":"io.netty:netty-common","h":[[2,"-"]]},
                              {"id":"io.quarkus.platform:quarkus-bom-quarkus-platform-descriptor:json:${quarkus.version}","h":[[1,"="]]},
                              {"id":"io.vertx:vertx-core","h":[[1,"4.4.2"],[2,null]]}]}
                """), builder.build(NOW).platform());
        assertEquals(json("{\"versions\":[],\"artifacts\":[]}"), build().platform());
    }

    @Test
    void configHistoriesInternStatesAndFoldUnnamedKeys() throws IOException {
        JsonNode config = build().config();
        assertEquals(json("[1,2,3]"), config.get("versions"));
        assertEquals(json("""
                [{"n":"quarkus.datasource.jdbc.url","a":["quarkus.datasource.\\"datasource-name\\".jdbc.url"],
                  "e":"quarkus-test","t":"string","p":"R"},
                 {"n":"quarkus.http.port","e":"quarkus-test","t":"int","d":"8080","p":"R"},
                 {"n":"quarkus.log.category.\\"categories\\".level","e":"quarkus-test","t":"Level","d":"inherit",
                  "p":"F"},
                 {"n":"quarkus.old.flag","e":"quarkus-test","t":"boolean","d":"false","p":"B"},
                 {"n":"quarkus.http.port","e":"quarkus-test","t":"int","d":"8081","p":"R"},
                 {"n":"quarkus.new.flag","e":"quarkus-test","t":"boolean","p":"R","x":""}]
                """), config.get("states"));
        assertEquals(json("""
                [{"k":"quarkus.datasource.jdbc.url","h":[[1,0]]},
                 {"k":"quarkus.http.port","h":[[1,1],[2,4],[3,1]]},
                 {"k":"quarkus.log.category.*.level","h":[[1,2]]},
                 {"k":"quarkus.new.flag","h":[[2,5]]},
                 {"k":"quarkus.old.flag","h":[[1,3],[2,null]]}]
                """), config.get("props"));
        assertEquals(json("""
                {"quarkus.datasource.jdbc.url":"The JDBC URL.","quarkus.http.port":"The HTTP port."}
                """), config.get("desc"));
    }

    @Test
    void extensionHistoriesTrackRelocations() throws IOException {
        JsonNode extensions = build().extensions();
        assertEquals(json("""
                [{"n":"Agroal","s":"stable","c":["data"],"p":["quarkus.datasource"]},
                 {"n":"RESTEasy Reactive","s":"stable","c":["data"]},
                 {"n":"REST","s":"stable","c":["data"]},
                 {"r":"io.quarkus:quarkus-rest"}]
                """), extensions.get("states"));
        assertEquals(json("""
                [{"id":"quarkus-agroal","h":[[1,0]]},
                 {"id":"quarkus-rest","h":[[2,2]]},
                 {"id":"quarkus-resteasy-reactive","h":[[1,1],[2,3]]}]
                """), extensions.get("exts"));
    }

    @Test
    void preReleaseWithoutConfigTakesItsPlaceInVersionOrder() throws IOException {
        HistoryBuilder builder = new HistoryBuilder();
        List<HistoryBuilder.Release> releases = new ArrayList<>(RELEASES);
        releases.add(2, PRE_RELEASE);
        releases.forEach(builder::add);
        HistoryBuilder.SiteData data = builder.build(NOW);

        assertEquals(json("""
                {"v":"3.3.0.CR1","date":"2023-08-16","stream":"3.3","pre":true,"lts":false,"config":false,"ext":false,
                 "classes":false}
                """), data.versions().get("versions").get(2));
        assertEquals(5, data.versions().get("versions").size());
        assertEquals(json("""
                [{"id":"io.netty:netty-common","h":[[3,"4.1.100.Final"]]},
                 {"id":"io.quarkus:quarkus-core","h":[[0,"="]]},
                 {"id":"io.vertx:vertx-core","h":[[0,"4.4.1"],[2,"4.4.4"],[4,null]]}]
                """), data.bom().get("artifacts"));
        // the pre-release is no config version, and no config change point points at it
        assertEquals(json("[1,3,4]"), data.config().get("versions"));
        assertEquals(json("""
                [{"k":"quarkus.datasource.jdbc.url","h":[[1,0]]},
                 {"k":"quarkus.http.port","h":[[1,1],[3,4],[4,1]]},
                 {"k":"quarkus.log.category.*.level","h":[[1,2]]},
                 {"k":"quarkus.new.flag","h":[[3,5]]},
                 {"k":"quarkus.old.flag","h":[[1,3],[3,null]]}]
                """), data.config().get("props"));
        assertEquals(json("""
                [{"id":"quarkus-agroal","h":[[1,0]]},
                 {"id":"quarkus-rest","h":[[3,2]]},
                 {"id":"quarkus-resteasy-reactive","h":[[1,1],[3,3]]}]
                """), data.extensions().get("exts"));
    }

    @Test
    void foldingKeepsPropertiesWithoutAnUnnamedVariant() {
        ConfigProperty level = prop("quarkus.log.category.\"categories\".level", List.of(), "Level", null,
                "RUN_TIME", null, null);
        ConfigProperty otherType = prop("quarkus.datasource.\"ds\".jdbc.url", List.of(), "int", null, "RUN_TIME",
                null, null);
        ConfigProperty url = prop("quarkus.datasource.jdbc.url", List.of(), "string", null, "RUN_TIME", null, null);
        assertEquals(List.of(url, otherType, level), HistoryBuilder.foldUnnamedKeys(List.of(url, otherType, level)));
    }

    @Test
    void classHistoriesNameTheJarsOfEachClass() throws IOException {
        HistoryBuilder builder = new HistoryBuilder();
        builder.add(withClasses(RELEASES.get(0), classes("3.0.0.Final",
                "quarkus-core", List.of("io.quarkus.runtime.Application"),
                "quarkus-junit5-internal", List.of("io.quarkus.test.QuarkusUnitTest",
                        "io.quarkus.test.QuarkusUnitTest$1"))));
        builder.add(RELEASES.get(1));
        // QuarkusUnitTest moves, its anonymous class goes, a class of the unnamed package and one in two jars come
        builder.add(withClasses(RELEASES.get(2), classes("3.3.0",
                "quarkus-amazon-lambda-http", List.of("io.quarkus.amazon.lambda.http.Handler"),
                "quarkus-amazon-lambda-rest", List.of("io.quarkus.amazon.lambda.http.Handler"),
                "quarkus-core", List.of("Main", "io.quarkus.runtime.Application"),
                "quarkus-junit-internal", List.of("io.quarkus.test.QuarkusExtensionTest",
                        "io.quarkus.test.QuarkusUnitTest"))));
        HistoryBuilder.SiteData data = builder.build(NOW);
        assertEquals(json("""
                {"versions":[0,2],
                 "artifacts":["quarkus-amazon-lambda-http","quarkus-amazon-lambda-rest","quarkus-core",
                              "quarkus-junit-internal","quarkus-junit5-internal"],
                 "packages":["","io.quarkus.amazon.lambda.http","io.quarkus.runtime","io.quarkus.test"],
                 "classes":[[0,"Main",[[2,2]]],
                            [1,"Handler",[[2,[0,1]]]],
                            [2,"Application",[[0,2]]],
                            [3,"QuarkusExtensionTest",[[2,3]]],
                            [3,"QuarkusUnitTest",[[0,4],[2,3]]],
                            [3,"QuarkusUnitTest$1",[[0,4],[2,null]]]]}
                """), data.classes().get(3));
        assertEquals(List.of(3), List.copyOf(data.classes().keySet()));
        JsonNode versions = data.versions().get("versions");
        assertTrue(versions.get(0).get("classes").asBoolean());
        assertFalse(versions.get(1).get("classes").asBoolean());
        assertTrue(build().classes().isEmpty(), "no classes file without classes");

        // another major line: a file of its own, whose histories start afresh
        builder.add(withClasses(RELEASES.get(3), classes("4.0.0.Beta1",
                "quarkus-core", List.of("io.quarkus.runtime.Application"),
                "quarkus-junit-internal", List.of("io.quarkus.test.QuarkusUnitTest"))));
        HistoryBuilder.SiteData both = builder.build(NOW);
        assertEquals(List.of("classes-3.json", "classes-4.json"),
                both.files().keySet().stream().filter(name -> name.startsWith("classes")).toList());
        assertEquals(data.classes().get(3), both.classes().get(3), "the 3.x file does not change");
        assertEquals(json("""
                {"versions":[3],
                 "artifacts":["quarkus-core","quarkus-junit-internal"],
                 "packages":["io.quarkus.runtime","io.quarkus.test"],
                 "classes":[[0,"Application",[[3,0]]],
                            [1,"QuarkusUnitTest",[[3,1]]]]}
                """), both.classes().get(4));
    }

    private static final String APP = "io.quarkus.runtime.Application";
    private static final String OLD = "io.quarkus.runtime.Old";
    private static final String UNIT = "io.quarkus.test.QuarkusUnitTest";

    /// 3.0.0.Final and 3.3.0 with API, 3.2.0.Final with classes only, 4.0.0.Beta1 with API: Application's stop()
    /// deprecated in 3.3.0 and no longer in 4.0.0.Beta1, Old deprecated for removal in 3.3.0 and gone in 4.x,
    /// QuarkusUnitTest moved to another jar with the same API.
    private static HistoryBuilder apiReleases() {
        HistoryBuilder builder = new HistoryBuilder();
        HistoryBuilder.Release r0 = RELEASES.get(0);
        builder.add(new HistoryBuilder.Release(r0.bom(), null, null, classes("3.0.0.Final",
                "quarkus-core", List.of(APP, OLD),
                "quarkus-junit5-internal", List.of(UNIT, UNIT + "$1")), null, api("3.0.0.Final",
                artifact("quarkus-core", type(APP, "+", member("<init>()V", "+"), member("stop()V", "+")),
                        type(OLD, "+")),
                artifact("quarkus-junit5-internal", type(UNIT, "+", member("<init>()V", "+"))))));
        builder.add(withClasses(RELEASES.get(1), classes("3.2.0.Final",
                "quarkus-core", List.of(APP, OLD),
                "quarkus-junit5-internal", List.of(UNIT, UNIT + "$1"))));
        HistoryBuilder.Release r2 = RELEASES.get(2);
        builder.add(new HistoryBuilder.Release(r2.bom(), r2.config(), r2.extensions(), classes("3.3.0",
                "quarkus-core", List.of(APP, OLD),
                "quarkus-junit-internal", List.of(UNIT)), null, api("3.3.0",
                artifact("quarkus-core", type(APP, "+", member("<init>()V", "+"), member("stop()V", "+D")),
                        type(OLD, "+R since 3.3")),
                artifact("quarkus-junit-internal", type(UNIT, "+", member("<init>()V", "+"))))));
        HistoryBuilder.Release r3 = RELEASES.get(3);
        builder.add(new HistoryBuilder.Release(r3.bom(), r3.config(), r3.extensions(), classes("4.0.0.Beta1",
                "quarkus-core", List.of(APP),
                "quarkus-junit-internal", List.of(UNIT)), null, api("4.0.0.Beta1",
                artifact("quarkus-core", type(APP, "+", member("<init>()V", "+"), member("stop()V", "+"))),
                artifact("quarkus-junit-internal", type(UNIT, "+", member("<init>()V", "+"))))));
        return builder;
    }

    @Test
    void theClassesFilesHoldTheDeprecationsAndTheRevisionsOfTheApi() throws IOException {
        HistoryBuilder.SiteData data = apiReleases().build(NOW);
        JsonNode versions = data.versions().get("versions");
        assertTrue(versions.get(0).get("api").asBoolean());
        assertFalse(versions.get(1).has("api"), "3.2.0.Final has classes but no API");
        assertTrue(versions.get(2).get("api").asBoolean());
        assertTrue(versions.get(3).get("api").asBoolean());
        assertEquals("{\"v\":\"3.0.0.Final\",\"date\":\"2023-04-26\",\"stream\":\"3.0\",\"pre\":false,\"lts\":false,"
                + "\"config\":false,\"ext\":false,\"classes\":true,\"api\":true}", versions.get(0).toString());
        // revisions: Application changes at 3.3.0; Old too; QuarkusUnitTest moves with the same API (one revision);
        // QuarkusUnitTest$1 is never API. dep: what is deprecated somewhere, with its whole history in the line
        assertEquals(json("""
                {"versions":[0,1,2],
                 "artifacts":["quarkus-core","quarkus-junit-internal","quarkus-junit5-internal"],
                 "packages":["io.quarkus.runtime","io.quarkus.test"],
                 "classes":[[0,"Application",[[0,0]],[[0,0],[2,1]]],
                            [0,"Old",[[0,0]],[[0,0],[2,1]]],
                            [1,"QuarkusUnitTest",[[0,2],[2,1]],[[0,0]]],
                            [1,"QuarkusUnitTest$1",[[0,2],[2,null]]]],
                 "api":{"versions":[0,2],"shards":32,
                        "dep":[[0,0,"stop()V",[[0,"+"],[2,"+D"]]],
                               [1,0,null,[[0,"+"],[2,"+R since 3.3"]]]]}}
                """), data.classes().get(3));
        // 4.x: Application has the API of 3.0.0.Final again (its first revision); stop() was deprecated in 3.x, so it
        // keeps a row in this line, where it is not deprecated
        assertEquals(json("""
                {"versions":[3],
                 "artifacts":["quarkus-core","quarkus-junit-internal"],
                 "packages":["io.quarkus.runtime","io.quarkus.test"],
                 "classes":[[0,"Application",[[3,0]],[[3,0]]],
                            [1,"QuarkusUnitTest",[[3,1]],[[3,0]]]],
                 "api":{"versions":[3],"shards":16,"dep":[[0,0,"stop()V",[[3,"+"]]]]}}
                """), data.classes().get(4));
        // without API data, a classes file has no api object and its rows no revisions
        HistoryBuilder classesOnly = new HistoryBuilder();
        classesOnly.add(withClasses(RELEASES.get(1), classes("3.2.0.Final", "quarkus-core", List.of(APP))));
        assertEquals(json("{\"versions\":[0],\"artifacts\":[\"quarkus-core\"],\"packages\":[\"io.quarkus.runtime\"],"
                + "\"classes\":[[0,\"Application\",[[0,0]]]]}"), classesOnly.build(NOW).classes().get(3));
        assertTrue(classesOnly.build(NOW).apiShards().isEmpty());
    }

    @Test
    void depRowsDateADeprecationThatBeganInAnEarlierLine() throws IOException {
        String x = "io.quarkus.X";
        String y1 = "io.quarkus.Y1";
        String y2 = "io.quarkus.Y2";
        String z = "io.quarkus.Z";
        HistoryBuilder builder = new HistoryBuilder();
        // X and its m() deprecated from 1.1.0 on, through 2.x into 3.x; Y1 deprecated from 1.1.0, undeprecated at
        // 2.1.0 and deprecated again at 2.2.0; Y2 deprecated at 2.0.0, undeprecated at the last 2.x release with API,
        // deprecated again at 3.0.0; Z deprecated from 2.1.0, moved to another jar at 3.0.0; 2.3.0 has no API
        builder.add(apiRelease("1.0.0.Final", List.of(x, y1), List.of(), null, api("1.0.0.Final",
                artifact("quarkus-core", type(x, "+", member("m()V", "+")), type(y1, "+")))));
        builder.add(apiRelease("1.1.0.Final", List.of(x, y1), List.of(), null, api("1.1.0.Final",
                artifact("quarkus-core", type(x, "+D", member("m()V", "+D")), type(y1, "+D")))));
        builder.add(apiRelease("2.0.0.Final", List.of(x, y1, y2), List.of(), null, api("2.0.0.Final",
                artifact("quarkus-core", type(x, "+D", member("m()V", "+D")), type(y1, "+D"), type(y2, "+D")))));
        builder.add(apiRelease("2.1.0.Final", List.of(x, y1, y2), List.of(z), "quarkus-old", api("2.1.0.Final",
                artifact("quarkus-core", type(x, "+D", member("m()V", "+D")), type(y1, "+"), type(y2, "+D")),
                artifact("quarkus-old", type(z, "+D")))));
        builder.add(apiRelease("2.2.0.Final", List.of(x, y1, y2), List.of(z), "quarkus-old", api("2.2.0.Final",
                artifact("quarkus-core", type(x, "+D", member("m()V", "+D")), type(y1, "+D"), type(y2, "+")),
                artifact("quarkus-old", type(z, "+D")))));
        builder.add(apiRelease("2.3.0.Final", List.of(x, y1, y2), List.of(z), "quarkus-old", null));
        builder.add(apiRelease("3.0.0", List.of(x, y1, y2), List.of(z), "quarkus-new", api("3.0.0",
                artifact("quarkus-core", type(x, "+R", member("m()V", "+D")), type(y1, "+D"), type(y2, "+D")),
                artifact("quarkus-new", type(z, "+D")))));
        HistoryBuilder.SiteData data = builder.build(NOW);

        // the first line has no earlier one
        assertEquals(json("""
                [[0,0,null,[[0,"+"],[1,"+D"]]],[0,0,"m()V",[[0,"+"],[1,"+D"]]],[1,0,null,[[0,"+"],[1,"+D"]]]]
                """), data.classes().get(1).at("/api/dep"));
        // X since 1.1.0 (vi 1); Y1 too, its break comes later in the line; Y2's deprecation and Z's begin in this line
        assertEquals(json("""
                [[0,0,null,[[2,"+D"]],1],
                 [0,0,"m()V",[[2,"+D"]],1],
                 [1,0,null,[[2,"+D"],[3,"+"],[4,"+D"]],1],
                 [2,0,null,[[2,"+D"],[4,"+"]]],
                 [3,1,null,[[3,"+D"]]]]
                """), data.classes().get(2).at("/api/dep"));
        // over two line boundaries for X; Y1 since 2.2.0 (vi 4), after its break; Y2 not deprecated at 2.2.0, the
        // release with API before 3.0.0 (2.3.0 has none); Z since 2.1.0 (vi 3) in the jar it left
        assertEquals(json("""
                [[0,0,null,[[6,"+R"]],1],
                 [0,0,"m()V",[[6,"+D"]],1],
                 [1,0,null,[[6,"+D"]],4],
                 [2,0,null,[[6,"+D"]]],
                 [3,1,null,[[6,"+D"]],3]]
                """), data.classes().get(3).at("/api/dep"));
        assertEquals(json("[\"quarkus-core\",\"quarkus-new\"]"), data.classes().get(3).get("artifacts"));
        assertEquals(json("[2,3,4]"), data.classes().get(2).at("/api/versions"));
    }

    /// A release whose BOM manages quarkus-core, with classes in quarkus-core and, when `jar` is not null, in that
    /// jar, and the API snapshot when it is not null.
    private static HistoryBuilder.Release apiRelease(String version, List<String> core, List<String> others,
            String jar, ApiSnapshot api) {
        ClassesSnapshot classes = jar == null ? classes(version, "quarkus-core", core)
                : classes(version, "quarkus-core", core, jar, others);
        return new HistoryBuilder.Release(bom(version, "2020-01-01", dep("io.quarkus:quarkus-core", version)), null,
                null, classes, null, api);
    }

    @Test
    void theShardsHoldTheApiOfTheirClassesByVersion() throws IOException {
        HistoryBuilder.SiteData data = apiReleases().build(NOW);
        List<ObjectNode> shards3 = data.apiShards().get(3);
        assertEquals(32, shards3.size());
        assertEquals(16, data.apiShards().get(4).size());
        assertEquals(19, HistoryBuilder.apiShardOf(UNIT, 32));
        // nested classes are in the shard of their outer class, the jars of a class in one row each
        assertEquals(json("""
                {"rows":[["quarkus-junit-internal","io.quarkus.test.QuarkusUnitTest",[["3.3.0","+"]],
                          [["<init>()V",[["3.3.0","+"]]]]],
                         ["quarkus-junit5-internal","io.quarkus.test.QuarkusUnitTest",
                          [["3.0.0.Final","+"],["3.3.0",null]],
                          [["<init>()V",[["3.0.0.Final","+"],["3.3.0",null]]]]]]}
                """), shards3.get(19));
        int app = HistoryBuilder.apiShardOf(APP, 32);
        JsonNode appRow = null;
        for (JsonNode row : shards3.get(app).get("rows")) {
            if (row.get(1).asText().equals(APP)) {
                appRow = row;
            }
        }
        assertEquals(json("""
                ["quarkus-core","io.quarkus.runtime.Application",[["3.0.0.Final","+"]],
                 [["<init>()V",[["3.0.0.Final","+"]]],["stop()V",[["3.0.0.Final","+"],["3.3.0","+D"]]]]]
                """), appRow);
        int rows = 0;
        for (ObjectNode shard : shards3) {
            rows += shard.get("rows").size();
        }
        assertEquals(4, rows, "one row per jar and class");
        assertEquals(List.of("api-3-0.json", "api-3-15.json", "api-3-31.json", "api-4-0.json", "api-4-15.json"),
                data.files().keySet().stream().filter(name -> name.matches("api-\\d+-(0|15|31)\\.json")).toList());
    }

    @Test
    void theShardOfAClassIsTheFnv1aOfItsTopLevelName() {
        assertEquals(1599357651L, HistoryBuilder.fnv1a32("io.quarkus.test.QuarkusUnitTest"));
        assertEquals(2782235434L, HistoryBuilder.fnv1a32("io.quarkus.Gr\u00f6\u00dfe"));
        assertEquals(2166136261L, HistoryBuilder.fnv1a32(""));
        assertEquals(19, HistoryBuilder.apiShardOf("io.quarkus.test.QuarkusUnitTest", 32));
        assertEquals(3, HistoryBuilder.apiShardOf("io.quarkus.test.QuarkusUnitTest", 16));
        assertEquals(19, HistoryBuilder.apiShardOf("io.quarkus.test.QuarkusUnitTest$Builder$1", 32));
        assertEquals(10, HistoryBuilder.apiShardOf("io.quarkus.Gr\u00f6\u00dfe", 32));
        assertEquals(32, HistoryBuilder.apiShardCount(3));
        assertEquals(16, HistoryBuilder.apiShardCount(4));
        assertEquals(8, HistoryBuilder.apiShardCount(2));
    }

    @Test
    void assembleWritesTheShardsAndDeletesStaleOnes() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            SnapshotStore store = new SnapshotStore(dir.resolve("snapshots"));
            store.write("3.0.0.Final", SnapshotStore.BOM, RELEASES.get(0).bom());
            store.write("3.0.0.Final", SnapshotStore.CLASSES, classes("3.0.0.Final", "quarkus-core", List.of(APP)));
            store.write("3.0.0.Final", SnapshotStore.API, api("3.0.0.Final", artifact("quarkus-core",
                    type(APP, "+D", member("<init>()V", "+")))));
            Path data = dir.resolve("site/data");
            AtomicFiles.writeString(data.resolve("api-3-40.json"), "{}\n");
            AtomicFiles.writeString(data.resolve("api-2-0.json"), "{}\n");
            AtomicFiles.writeString(data.resolve("api-notes.json"), "{}\n");
            HistoryBuilder.assemble(store, List.of(QuarkusVersion.parse("3.0.0.Final")), data, NOW);
            for (int k = 0; k < 32; k++) {
                assertTrue(Files.isRegularFile(data.resolve(HistoryBuilder.apiShardFile(3, k))), "shard " + k);
            }
            assertFalse(Files.exists(data.resolve("api-3-40.json")));
            assertFalse(Files.exists(data.resolve("api-2-0.json")));
            assertTrue(Files.exists(data.resolve("api-notes.json")), "not a shard");
            JsonNode classes = json(Files.readString(data.resolve("classes-3.json")));
            assertEquals(json("[[0,0,null,[[0,\"+D\"]]]]"), classes.at("/api/dep"));
            assertTrue(json(Files.readString(data.resolve("versions.json"))).at("/versions/0/api").asBoolean());
            // an API snapshot needs the release's classes
            assertThrows(IllegalArgumentException.class, () -> new HistoryBuilder.Release(RELEASES.get(0).bom(), null,
                    null, null, null, api("3.0.0.Final")));
        }
    }

    @Test
    void releasesMustBeAscending() {
        HistoryBuilder builder = new HistoryBuilder();
        builder.add(RELEASES.get(2));
        assertThrows(IllegalArgumentException.class, () -> builder.add(RELEASES.get(1)));
        assertThrows(IllegalArgumentException.class, () -> builder.add(PRE_RELEASE));
    }

    @Test
    void assembleWritesCompactSiteDataFromSnapshots() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            assembleInto(dir);
        }
    }

    @Test
    void assemblePublishesTheSelectedReleasesThatHaveABomSnapshot() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            SnapshotStore store = writeSnapshots(dir, PRE_RELEASE);
            Path data = dir.resolve("site/data");
            // 3.2.0.CR1 is published but has no snapshot
            VersionSelector selector = new VersionSelector(List.of("3.0.0.Final", "3.2.0.CR1", "3.2.0.Final",
                    "3.3.0.CR1", "3.3.0", "4.0.0.Beta1"));
            QuarkusVersion since = QuarkusVersion.parse(VersionSelector.DEFAULT_SINCE);

            HistoryBuilder.assemble(store, selector.all(since, VersionSelector.PreReleases.NONE), data, NOW);
            assertEquals(List.of("3.0.0.Final", "3.2.0.Final", "3.3.0"), publishedVersions(data));

            HistoryBuilder.assemble(store, selector.all(since, VersionSelector.PreReleases.UPCOMING), data, NOW);
            assertEquals(List.of("3.0.0.Final", "3.2.0.Final", "3.3.0", "4.0.0.Beta1"), publishedVersions(data));

            HistoryBuilder.assemble(store, selector.all(since, VersionSelector.PreReleases.ALL), data, NOW);
            assertEquals(List.of("3.0.0.Final", "3.2.0.Final", "3.3.0.CR1", "3.3.0", "4.0.0.Beta1"),
                    publishedVersions(data));
            JsonNode preRelease = json(Files.readString(data.resolve("versions.json"))).get("versions").get(2);
            assertTrue(preRelease.get("pre").asBoolean());
            assertFalse(preRelease.get("config").asBoolean());
            assertEquals(json("[1,3,4]"), json(Files.readString(data.resolve("config.json"))).get("versions"));
        }
    }

    @Test
    void assemblePublishesExtensionsWithoutConfigAndNoConfigWithoutExtensions() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            SnapshotStore store = writeSnapshots(dir, EXTENSIONS_ONLY, PRE_RELEASE);
            // a config.json without its extensions.json is ignored
            store.write("3.3.0.CR1", SnapshotStore.CONFIG, config("3.3.0.CR1", "model",
                    prop("quarkus.http.port", List.of(), "int", "8081", "RUN_TIME", null, null)));
            Path data = dir.resolve("site/data");
            List<QuarkusVersion> selected = new ArrayList<>(List.of(QuarkusVersion.parse("2.16.12.Final"),
                    QuarkusVersion.parse("3.3.0.CR1")));
            RELEASES.forEach(release -> selected.add(QuarkusVersion.parse(release.bom().version())));
            HistoryBuilder.assemble(store, selected, data, NOW);

            JsonNode versions = json(Files.readString(data.resolve("versions.json"))).get("versions");
            assertEquals(List.of("2.16.12.Final", "3.0.0.Final", "3.2.0.Final", "3.3.0.CR1", "3.3.0", "4.0.0.Beta1"),
                    publishedVersions(data));
            assertTrue(versions.get(0).get("ext").asBoolean());
            assertFalse(versions.get(0).get("config").asBoolean());
            assertFalse(versions.get(3).get("config").asBoolean(), "config without extensions is left out");
            assertFalse(versions.get(3).get("ext").asBoolean());
            assertEquals(json("[2,4,5]"), json(Files.readString(data.resolve("config.json"))).get("versions"));
            assertEquals(json("""
                    [{"id":"quarkus-agroal","h":[[0,0]]},
                     {"id":"quarkus-rest","h":[[4,3]]},
                     {"id":"quarkus-resteasy-reactive","h":[[0,1],[2,2],[4,4]]}]
                    """), json(Files.readString(data.resolve("extensions.json"))).get("exts"));
        }
    }

    private static void assembleInto(ScratchDir dir) throws IOException {
        SnapshotStore store = writeSnapshots(dir);
        Path data = dir.resolve("site/data");
        HistoryBuilder.assemble(store, RELEASES.stream().map(r -> QuarkusVersion.parse(r.bom().version())).toList(),
                data, NOW);

        HistoryBuilder.SiteData expected = build();
        for (String file : expected.files().keySet()) {
            String text = Files.readString(data.resolve(file));
            assertFalse(text.strip().contains("\n"), file + " is compact");
            assertEquals(expected.files().get(file), json(text), file);
        }
        assertTrue(Files.exists(data.resolve("versions.json")));
    }

    /// Writes the snapshots of [#RELEASES] and of the extra releases.
    private static SnapshotStore writeSnapshots(ScratchDir dir, HistoryBuilder.Release... extra) throws IOException {
        SnapshotStore store = new SnapshotStore(dir.resolve("snapshots"));
        List<HistoryBuilder.Release> releases = new ArrayList<>(RELEASES);
        releases.addAll(List.of(extra));
        for (HistoryBuilder.Release release : releases) {
            String version = release.bom().version();
            store.write(version, SnapshotStore.BOM, release.bom());
            if (release.config() != null) {
                store.write(version, SnapshotStore.CONFIG, release.config());
            }
            if (release.extensions() != null) {
                store.write(version, SnapshotStore.EXTENSIONS, release.extensions());
            }
            if (release.classes() != null) {
                store.write(version, SnapshotStore.CLASSES, release.classes());
            }
        }
        return store;
    }

    private static HistoryBuilder.Release withClasses(HistoryBuilder.Release release, ClassesSnapshot classes) {
        return new HistoryBuilder.Release(release.bom(), release.config(), release.extensions(), classes);
    }

    /// A classes snapshot from artifactId, class list pairs (both given sorted).
    @SuppressWarnings("unchecked")
    private static ClassesSnapshot classes(String version, Object... idThenClasses) {
        List<ArtifactClasses> artifacts = new ArrayList<>();
        for (int i = 0; i < idThenClasses.length; i += 2) {
            artifacts.add(new ArtifactClasses((String) idThenClasses[i], (List<String>) idThenClasses[i + 1]));
        }
        return new ClassesSnapshot(version, artifacts);
    }

    private static ApiSnapshot api(String version, ArtifactApi... artifacts) {
        return new ApiSnapshot(version, "api/1", List.of(artifacts));
    }

    private static ArtifactApi artifact(String id, ClassApi... classes) {
        return new ArtifactApi(id, List.of(classes));
    }

    private static ClassApi type(String name, String value, MemberApi... members) {
        return new ClassApi(name, value, List.of(members));
    }

    private static MemberApi member(String key, String value) {
        return new MemberApi(key, value);
    }

    private static List<String> publishedVersions(Path data) throws IOException {
        List<String> versions = new ArrayList<>();
        json(Files.readString(data.resolve("versions.json"))).get("versions").forEach(row -> versions.add(
                row.get("v").asText()));
        return versions;
    }

    private static HistoryBuilder.SiteData build() {
        HistoryBuilder builder = new HistoryBuilder();
        RELEASES.forEach(builder::add);
        return builder.build(NOW);
    }

    private static JsonNode json(String text) throws IOException {
        return Json.readTree(text.getBytes(StandardCharsets.UTF_8));
    }

    private static BomSnapshot bom(String version, String date, ManagedDep... deps) {
        return new BomSnapshot(version, date, List.of(deps));
    }

    private static ManagedDep dep(String id, String version) {
        return new ManagedDep(id, version);
    }

    private static ConfigSnapshot config(String version, String source, ConfigProperty... properties) {
        return new ConfigSnapshot(version, source, "test", 1, List.of(), List.of(properties));
    }

    private static ConfigProperty prop(String name, List<String> aliases, String type, String defaultValue,
            String phase, String deprecated, String desc) {
        return new ConfigProperty(name, ConfigKeys.canonical(name), aliases, "quarkus-test", type, false, false,
                false, defaultValue, phase, deprecated, null, desc, "Test#" + name);
    }

    private static ExtensionsSnapshot extensions(String version, ExtensionInfo... extensions) {
        return new ExtensionsSnapshot(version, List.of(extensions));
    }

    private static ExtensionInfo extension(String id, String name, List<String> prefixes, String relocatedTo) {
        return new ExtensionInfo(id, name, null, "stable", List.of("data"), null, false, prefixes, relocatedTo);
    }
}
