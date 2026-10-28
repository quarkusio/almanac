package io.quarkiverse.almanac.dataset;

import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.model.ExtensionInfo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.quarkiverse.almanac.dataset.Samples.BOM_300;
import static io.quarkiverse.almanac.dataset.Samples.BOM_310;
import static io.quarkiverse.almanac.dataset.Samples.BOM_320;
import static io.quarkiverse.almanac.dataset.Samples.BOM_320_CR1;
import static io.quarkiverse.almanac.dataset.Samples.CONFIG_310;
import static io.quarkiverse.almanac.dataset.Samples.CONFIG_320;
import static io.quarkiverse.almanac.dataset.Samples.EXTENSIONS_310;
import static io.quarkiverse.almanac.dataset.Samples.EXTENSIONS_320;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Encoding and decoding of the dataset files (format 1): the exact line format, lossless round trips, determinism,
/// and minimal changes when a release is inserted.
class DatasetCodecTest {

    @Test
    void releasesAndBomLinesHaveTheDocumentedFormat() {
        Map<String, String> files = DatasetEncoder.encode(Samples.contents());
        assertEquals("""
                {"version":"2.16.12.Final"}
                {"version":"3.0.0.Final","date":"2023-04-26","bom":true}
                {"version":"3.1.0","date":"2023-05-31","bom":true,"config":{"source":"model","extractor":"model-json/1","roots":3},"extensions":true}
                {"version":"3.2.0.CR1","date":"2023-06-28","bom":true}
                {"version":"3.2.0","date":"2023-07-05","bom":true,"config":{"source":"model","extractor":"model-json/1","roots":3},"extensions":true}
                """, files.get(DatasetFormat.RELEASES));
        assertEquals("""
                {"id":"io.netty:netty-common","h":[["3.1.0","4.1.100.Final"],["3.2.0","4.1.101.Final"]]}
                {"id":"io.quarkus:quarkus-core","h":[["3.0.0.Final","="]]}
                {"id":"io.quarkus:quarkus-core:pom:${quarkus.version}","h":[["3.2.0","="]]}
                {"id":"io.vertx:vertx-core","h":[["3.0.0.Final","4.4.1"],["3.2.0.CR1","4.4.4"],["3.2.0",null]]}
                """, files.get(DatasetFormat.BOM));
        assertEquals("""
                {"version":"3.1.0","source":"model","extractor":"model-json/1","roots":3,"warnings":["duplicate key quarkus.x: a and b"]}
                {"version":"3.2.0","source":"model","extractor":"model-json/1","roots":3,"warnings":[]}
                """, files.get(DatasetFormat.CONFIG_META));
        assertEquals(DatasetFormat.readme(), files.get(DatasetFormat.README));
        assertTrue(DatasetFormat.readme().startsWith("# Quarkus Almanac dataset, format 1\n\n"));
    }

    @Test
    void configAndExtensionLinesPointAtContentAddressedStates() {
        Map<String, String> files = DatasetEncoder.encode(Samples.contents());
        String port8080 = id(Samples.PORT_8080);
        String port8081 = id(Samples.PORT_8081);
        String level = id(Samples.LEVEL);
        String oldFlag = id(Samples.OLD_FLAG);
        String newFlag = id(Samples.NEW_FLAG);
        // config changes run over the config releases only: 3.1.0 and 3.2.0
        assertEquals("{\"key\":\"quarkus.http.port\",\"h\":[[\"3.1.0\",\"" + port8080 + "\"],[\"3.2.0\",\"" + port8081
                        + "\"]]}\n"
                        + "{\"key\":\"quarkus.log.category.*.level\",\"h\":[[\"3.1.0\",\"" + level + "\"]]}\n"
                        + "{\"key\":\"quarkus.new.flag[*].enabled\",\"h\":[[\"3.2.0\",\"" + newFlag + "\"]]}\n"
                        + "{\"key\":\"quarkus.old.flag\",\"h\":[[\"3.1.0\",\"" + oldFlag + "\"],[\"3.2.0\",null]]}\n",
                files.get(DatasetFormat.CONFIG));
        List<String> states = DatasetFormat.lines(files.get(DatasetFormat.CONFIG_STATES));
        assertEquals(5, states.size());
        assertEquals(states.stream().sorted().toList(), states, "sorted by id");
        assertTrue(states.contains("{\"id\":\"" + oldFlag + "\",\"state\":{\"name\":\"quarkus.old.flag\","
                + "\"key\":\"quarkus.old.flag\",\"ext\":\"quarkus-core\",\"type\":\"boolean\",\"list\":true,"
                + "\"optional\":false,\"map\":false,\"phase\":\"BUILD_TIME\",\"deprecated\":\"3.1\",\"enum\":[]}}"),
                String.join("\n", states));

        // the id is the SHA-256 of the state's compact JSON (value computed independently with Python's hashlib)
        ExtensionInfo rest = new ExtensionInfo("quarkus-rest", "REST", null, "stable", null, null, false, null, null);
        assertEquals("{\"id\":\"quarkus-rest\",\"name\":\"REST\",\"status\":\"stable\",\"unlisted\":false}",
                DatasetFormat.canonicalJson(rest));
        assertEquals("ad1d74b6584444e9", id(rest));
        assertEquals("44136fa355b3678a", DatasetFormat.stateId("{}"));

        assertEquals("{\"id\":\"quarkus-rest\",\"h\":[[\"3.2.0\",\"" + id(Samples.REST) + "\"]]}\n"
                + "{\"id\":\"quarkus-resteasy-reactive\",\"h\":[[\"3.1.0\",\"" + id(Samples.RESTEASY_REACTIVE)
                + "\"],[\"3.2.0\",\"" + id(Samples.RESTEASY_REACTIVE_RELOCATED) + "\"]]}\n",
                files.get(DatasetFormat.EXTENSIONS));
    }

    @Test
    void decodingRestoresEverySnapshotExactly() throws IOException {
        Contents contents = Samples.contents();
        Dataset dataset = Dataset.of(DatasetEncoder.encode(contents));

        assertEquals(List.of("2.16.12.Final", "3.0.0.Final", "3.1.0", "3.2.0.CR1", "3.2.0"),
                dataset.releases().stream().map(Dataset.Release::version).toList());
        assertEquals(List.of("3.1.0", "3.2.0"), dataset.versionsWith(Kind.CONFIG));
        for (BomSnapshot bom : List.of(BOM_300, BOM_310, BOM_320_CR1, BOM_320)) {
            assertEquals(bom, dataset.bom(bom.version()));
        }
        assertEquals(CONFIG_310, dataset.config("3.1.0"));
        assertEquals(CONFIG_320, dataset.config("3.2.0"));
        assertEquals(EXTENSIONS_310, dataset.extensions("3.1.0"));
        assertEquals(EXTENSIONS_320, dataset.extensions("3.2.0"));
        assertEquals(contents, dataset.contents());

        // null and empty lists, "" and null stay apart
        ConfigSnapshot restored = dataset.config("3.1.0");
        assertEquals(null, restored.properties().get(2).aliases());
        assertEquals(List.of(), restored.properties().get(2).enumValues());
        assertEquals("", restored.properties().get(1).deprecated());
        assertEquals(Samples.DESC_WITH_SEPARATORS, restored.properties().get(1).desc());
        assertFalse(dataset.has("2.16.12.Final", Kind.BOM));
        assertThrows(IOException.class, () -> dataset.bom("2.16.12.Final"));
        assertThrows(IOException.class, () -> dataset.config("3.0.0.Final"));
    }

    @Test
    void encodingIsDeterministicAndIndependentOfInsertionOrder() throws IOException {
        Map<String, String> ascending = DatasetEncoder.encode(Samples.contents());
        assertEquals(ascending, DatasetEncoder.encode(Samples.contents()));
        assertEquals(ascending, DatasetEncoder.encode(Dataset.of(ascending).contents()));

        Contents shuffled = new Contents();
        Samples.put(shuffled, BOM_320, CONFIG_320, EXTENSIONS_320);
        Samples.put(shuffled, BOM_300, null, null);
        Samples.put(shuffled, BOM_320_CR1, null, null);
        shuffled.addRelease("2.16.12.Final", null);
        Samples.put(shuffled, BOM_310, CONFIG_310, EXTENSIONS_310);
        assertEquals(ascending, DatasetEncoder.encode(shuffled));
    }

    @Test
    void insertingAReleaseChangesOnlyTheLinesWhoseHistoryChanges() {
        Contents without = Samples.contents();
        without.remove("3.1.0", Kind.BOM);
        without.remove("3.1.0", Kind.CONFIG);
        without.remove("3.1.0", Kind.EXTENSIONS);
        without.forgetRelease("3.1.0");
        Map<String, String> before = DatasetEncoder.encode(without);
        Map<String, String> after = DatasetEncoder.encode(Samples.contents());

        // releases: the new line; bom: netty-common now starts at 3.1.0 (quarkus-core "=" and vertx-core 4.4.1 hold
        // across 3.1.0 already); config: the new 3.1.0 states of 3.1.0 (old.flag only existed there; port and level
        // move their first point); extensions: resteasy-reactive's first state
        assertEquals(Map.of(DatasetFormat.RELEASES, "+1 -0",
                DatasetFormat.BOM, "+1 -1",
                DatasetFormat.CONFIG, "+3 -2",
                DatasetFormat.CONFIG_STATES, "+2 -0",
                DatasetFormat.CONFIG_META, "+1 -0",
                DatasetFormat.EXTENSIONS, "+1 -1",
                DatasetFormat.EXTENSION_STATES, "+1 -0"), Samples.lineChanges(before, after));
        // and removing it again gives back exactly the files before
        assertEquals(before, DatasetEncoder.encode(without));
    }

    @Test
    void insertingABomOnlyPreReleaseTouchesOnlyItsChanges() {
        Contents without = Samples.contents();
        without.remove("3.2.0.CR1", Kind.BOM);
        without.forgetRelease("3.2.0.CR1");
        Map<String, String> before = DatasetEncoder.encode(without);
        Map<String, String> after = DatasetEncoder.encode(Samples.contents());
        // vertx-core gets the CR1 point; netty-common's 4.1.100.Final from 3.1.0 already holds at CR1
        assertEquals(Map.of(DatasetFormat.RELEASES, "+1 -0", DatasetFormat.BOM, "+1 -1"),
                Samples.lineChanges(before, after));
    }

    @Test
    void platformBomsKeepOnlyTheirDifferencesFromTheBom() throws IOException {
        Contents contents = Samples.contentsWithPlatforms();
        Map<String, String> files = DatasetEncoder.encode(contents);
        // vertx-core: another version at 3.1.0, managed by neither at 3.2.0; the extension holds from 3.1.0 on
        assertEquals("""
                {"id":"io.quarkiverse.vault:quarkus-vault","h":[["3.1.0","3.0.0"]]}
                {"id":"io.quarkus.platform:quarkus-bom-quarkus-platform-descriptor:json:${quarkus.version}","h":[["3.2.0","="]]}
                {"id":"io.quarkus:quarkus-core:pom:${quarkus.version}","h":[["3.2.0","-"]]}
                {"id":"io.vertx:vertx-core","h":[["3.1.0","4.4.2"],["3.2.0",null]]}
                """, files.get(DatasetFormat.PLATFORM));
        List<String> releases = DatasetFormat.lines(files.get(DatasetFormat.RELEASES));
        assertTrue(releases.get(2).endsWith(",\"extensions\":true,\"platform\":{\"date\":\"2023-06-02\"}}"),
                releases.get(2));
        assertTrue(releases.get(4).endsWith(",\"extensions\":true,\"platform\":{}}"), releases.get(4));
        // the other files are those of the same releases without platform BOMs
        Map<String, String> without = DatasetEncoder.encode(Samples.contents());
        assertEquals(Map.of(DatasetFormat.RELEASES, "+2 -2", DatasetFormat.PLATFORM, "+4 -0"),
                Samples.lineChanges(without, files));

        Dataset dataset = Dataset.of(files);
        assertEquals(List.of("3.1.0", "3.2.0"), dataset.versionsWith(Kind.PLATFORM));
        assertEquals(Samples.PLATFORM_310, dataset.platform("3.1.0"));
        assertEquals(Samples.PLATFORM_320, dataset.platform("3.2.0"));
        assertEquals(contents, dataset.contents());
        assertEquals(files, DatasetEncoder.encode(dataset.contents()));
        assertFalse(dataset.has("3.0.0.Final", Kind.PLATFORM));
        assertThrows(IOException.class, () -> dataset.platform("3.0.0.Final"));
    }

    @Test
    void decodingRefusesPlatformDifferencesThatDoNotApply() {
        Map<String, String> encoded = DatasetEncoder.encode(Samples.contentsWithPlatforms());
        // "-" where the release's BOM does not manage the id either
        assertRefused(encoded, DatasetFormat.PLATFORM, encoded.get(DatasetFormat.PLATFORM).replace(
                "[[\"3.2.0\",\"-\"]]", "[[\"3.1.0\",\"-\"]]"), "platform-bom.jsonl: platform BOM 3.1.0: "
                + "io.quarkus:quarkus-core:pom:${quarkus.version} is marked as not managed, but the release's BOM does "
                + "not manage it either");
        // a change point at a release without platform BOM
        assertRefused(encoded, DatasetFormat.PLATFORM, encoded.get(DatasetFormat.PLATFORM).replace(
                "[[\"3.2.0\",\"=\"]]", "[[\"3.0.0.Final\",\"=\"]]"), "has a change point at 3.0.0.Final, which "
                + "has no platform");
        // a platform BOM needs the BOM of its release
        Contents noBom = Samples.contentsWithPlatforms();
        noBom.remove("3.2.0", Kind.CONFIG);
        noBom.remove("3.2.0", Kind.EXTENSIONS);
        noBom.remove("3.2.0", Kind.BOM);
        assertThrows(IllegalArgumentException.class, () -> DatasetEncoder.encode(noBom));
    }

    @Test
    void decodingRejectsInconsistentFiles() throws IOException {
        Map<String, String> files = new HashMap<>(DatasetEncoder.encode(Samples.contents()));
        files.put(DatasetFormat.BOM, files.get(DatasetFormat.BOM).replace("[\"3.2.0.CR1\",\"4.4.4\"]",
                "[\"2.16.12.Final\",\"4.4.4\"]"));
        IOException noBom = assertThrows(IOException.class, () -> Dataset.of(files).bom("3.0.0.Final"));
        assertTrue(noBom.getMessage().contains("io.vertx:vertx-core has a change point at 2.16.12.Final, which has no "
                + "bom"), noBom.getMessage());

        Map<String, String> missingState = new HashMap<>(DatasetEncoder.encode(Samples.contents()));
        missingState.put(DatasetFormat.CONFIG_STATES, "");
        IOException missing = assertThrows(IOException.class, () -> Dataset.of(missingState).config("3.1.0"));
        assertTrue(missing.getMessage().contains("no state"), missing.getMessage());

        Map<String, String> otherFormat = new HashMap<>(DatasetEncoder.encode(Samples.contents()));
        otherFormat.put(DatasetFormat.README, "# Quarkus Almanac dataset, format 2\n");
        IOException format = assertThrows(IOException.class, () -> Dataset.of(otherFormat));
        assertTrue(format.getMessage().contains("not a format 1 dataset"), format.getMessage());
    }

    @Test
    void decodingRefusesFilesThatWouldRestoreWrongData() throws IOException {
        Map<String, String> encoded = DatasetEncoder.encode(Samples.contents());
        List<String> bom = DatasetFormat.lines(encoded.get(DatasetFormat.BOM));
        String port8080 = id(Samples.PORT_8080);
        String port8081 = id(Samples.PORT_8081);

        // a lost newline: a lenient parser reads the first object and drops the joined line
        assertRefused(encoded, DatasetFormat.BOM, bom.get(0) + bom.get(1) + "\n" + bom.get(2) + "\n" + bom.get(3)
                + "\n", "bom.jsonl:1: not JSON: Trailing token");
        // a repeated field: a lenient parser keeps the last one
        assertRefused(encoded, DatasetFormat.BOM, encoded.get(DatasetFormat.BOM).replace("[[\"3.0.0.Final\",\"=\"]]}",
                "[[\"3.0.0.Final\",\"=\"]],\"h\":[[\"3.0.0.Final\",\"1\"]]}"), "Duplicate field 'h'");
        // CR-only line ends: one line of joined objects
        assertRefused(encoded, DatasetFormat.BOM, encoded.get(DatasetFormat.BOM).replace('\n', '\r'),
                "not JSON: Trailing token");
        // a state whose content changed, its id kept
        assertRefused(encoded, DatasetFormat.CONFIG_STATES, encoded.get(DatasetFormat.CONFIG_STATES)
                .replace("\"default\":\"8080\"", "\"default\":\"9090\""),
                "state " + port8080 + " does not hash to its id");
        // a history line lost: its states are no longer referenced
        assertRefused(encoded, DatasetFormat.CONFIG, encoded.get(DatasetFormat.CONFIG).lines()
                .filter(line -> !line.contains("quarkus.old.flag")).map(line -> line + "\n").reduce("", String::concat),
                "state " + id(Samples.OLD_FLAG) + " is not referenced by config.jsonl");
        // a null in place of a state
        assertRefused(encoded, DatasetFormat.CONFIG, encoded.get(DatasetFormat.CONFIG).replace("\"" + port8081 + "\"",
                "null"), "state " + port8081 + " is not referenced by config.jsonl");
        // config-meta.jsonl and releases.jsonl disagree about a release
        assertRefused(encoded, DatasetFormat.RELEASES, encoded.get(DatasetFormat.RELEASES).replace(
                "{\"version\":\"3.1.0\",\"date\":\"2023-05-31\",\"bom\":true,\"config\":{\"source\":\"model\","
                        + "\"extractor\":\"model-json/1\",\"roots\":3}",
                "{\"version\":\"3.1.0\",\"date\":\"2023-05-31\",\"bom\":true,\"config\":{\"source\":\"model\","
                        + "\"extractor\":\"model-json/1\",\"roots\":4}"),
                "the line of 3.1.0 says ConfigInfo[source=model, extractor=model-json/1, roots=3] but releases.jsonl "
                        + "says ConfigInfo[source=model, extractor=model-json/1, roots=4]");
    }

    /// Decoding the encoded files with one file replaced fails with a message containing `expected`.
    private static void assertRefused(Map<String, String> encoded, String file, String content, String expected) {
        assertFalse(content.equals(encoded.get(file)), "the replacement changes " + file);
        Map<String, String> files = new HashMap<>(encoded);
        files.put(file, content);
        IOException e = assertThrows(IOException.class, () -> Dataset.of(files).contents(), expected);
        assertTrue(e.getMessage().contains(expected), e.getMessage());
    }

    @Test
    void crlfCheckoutsStillDecode() throws IOException {
        Map<String, String> files = new HashMap<>();
        DatasetEncoder.encode(Samples.contents()).forEach((name, content) -> files.put(name,
                content.replace("\n", "\r\n")));
        assertEquals(Samples.contents(), Dataset.of(files).contents());
    }

    @Test
    void releaseOrderFollowsQuarkusVersionsThenText() {
        assertEquals(List.of("2.16.12.Final", "3.0.0", "3.0.0.Final", "3.8.6", "3.8.6.1", "3.9.0.CR1", "3.9.0",
                "3.10.0", "4.0.0.Beta1", "999-SNAPSHOT"), ReleaseOrder.sorted(List.of("999-SNAPSHOT", "3.10.0",
                "3.9.0", "3.9.0.CR1", "4.0.0.Beta1", "3.8.6.1", "3.8.6", "3.0.0.Final", "3.0.0", "2.16.12.Final")));
    }

    private static String id(Object state) {
        return DatasetFormat.stateId(DatasetFormat.canonicalJson(state));
    }
}
