package io.quarkiverse.almanac.assemble;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkiverse.almanac.ScratchDir;
import io.quarkiverse.almanac.model.ProfileSnapshot;
import io.quarkiverse.almanac.util.Json;
import io.quarkiverse.almanac.util.SnapshotStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/// [ProfileDocument]: the profile snapshots of the work folder over the releases of `versions.json` that have one.
class ProfileDocumentTest {

    @Test
    void changePointsOverTheReleasesWithAProfile() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path data = Files.createDirectories(dir.resolve("data"));
            Files.writeString(data.resolve("versions.json"),
                    "{\"versions\":[{\"v\":\"3.33.0\"},{\"v\":\"3.34.0\"},{\"v\":\"3.35.0\"}]}");
            SnapshotStore work = new SnapshotStore(dir.resolve("snapshots"), null);
            work.write("3.34.0", ProfileDocument.SNAPSHOT, profile("3.34.0",
                    Map.of("io.netty:netty-common", "runtime", "io.vertx:vertx-core", "build"),
                    Map.of("io.netty:netty-common", List.of("io.quarkus:quarkus-netty", "io.vertx:vertx-core!x:y"),
                            "io.vertx:vertx-core!x:y", List.of("io.quarkus:quarkus-vertx-deployment")),
                    Map.of("io.vertx:vertx-core", List.of("io.vertx:vertx-core!x:y"))));
            work.write("3.35.0", ProfileDocument.SNAPSHOT, profile("3.35.0",
                    Map.of("io.netty:netty-common", "runtime", "org.junit.jupiter:junit-jupiter", "test"),
                    Map.of("io.netty:netty-common", List.of("io.quarkus:quarkus-netty"),
                            "org.junit.jupiter:junit-jupiter", List.of("org.example:runner@1.2"),
                            "org.example:runner@1.2", List.of("io.quarkus:quarkus-junit")),
                    Map.of()));

            ProfileDocument.write(data, work);

            JsonNode document = Json.readTree(Files.readAllBytes(data.resolve("profile.json")));
            assertEquals(Json.readTree("""
                    {"versions":[1,2],
                     "releases":["3.34.0","3.35.0"],
                     "artifacts":[{"id":"io.netty:netty-common","h":[[1,"r"]]},
                                  {"id":"io.vertx:vertx-core","h":[[1,"b"],[2,null]]},
                                  {"id":"org.junit.jupiter:junit-jupiter","h":[[2,"t"]]}],
                     "ids":["io.quarkus:quarkus-junit","io.quarkus:quarkus-netty",
                            "io.quarkus:quarkus-vertx-deployment","io.vertx:vertx-core!x:y","org.example:runner@1.2"],
                     "parents":[{"id":"io.netty:netty-common","h":[[1,[1,3]],[2,[1]]]},
                                {"id":"io.vertx:vertx-core!x:y","h":[[1,[2]],[2,null]]},
                                {"id":"org.example:runner@1.2","h":[[2,[0]]]},
                                {"id":"org.junit.jupiter:junit-jupiter","h":[[2,[4]]]}],
                     "starts":[{"id":"io.vertx:vertx-core","h":[[1,[3]],[2,null]]}]}
                    """.getBytes()), document);
        }
    }

    @Test
    void aRunWithoutSnapshotsKeepsTheProfilesOfTheFileAtTheirNewIndices() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path data = Files.createDirectories(dir.resolve("data"));
            Files.writeString(data.resolve("versions.json"),
                    "{\"versions\":[{\"v\":\"3.34.0\"},{\"v\":\"3.35.0\"}]}");
            SnapshotStore work = new SnapshotStore(dir.resolve("snapshots"), null);
            work.write("3.34.0", ProfileDocument.SNAPSHOT, profile("3.34.0",
                    Map.of("io.netty:netty-common", "runtime", "io.vertx:vertx-core", "build"),
                    Map.of("io.netty:netty-common", List.of("io.vertx:vertx-core!x:y"),
                            "io.vertx:vertx-core!x:y", List.of("io.quarkus:quarkus-vertx-deployment")),
                    Map.of("io.vertx:vertx-core", List.of("io.vertx:vertx-core!x:y"))));
            work.write("3.35.0", ProfileDocument.SNAPSHOT, profile("3.35.0",
                    Map.of("io.netty:netty-common", "runtime"), Map.of("io.netty:netty-common",
                            List.of("io.quarkus:quarkus-netty")), Map.of()));
            ProfileDocument.write(data, work);
            Map<String, ProfileSnapshot> before = ProfileDocument.read(data.resolve("profile.json"));

            // the run that adds 3.34.1 and 3.36.0 has no profile snapshot at all: the two profiles stay, at indices
            // 0 and 2
            Files.writeString(data.resolve("versions.json"), "{\"versions\":[{\"v\":\"3.34.0\"},{\"v\":\"3.34.1\"},"
                    + "{\"v\":\"3.35.0\"},{\"v\":\"3.36.0\"}]}");
            ProfileDocument.write(data, new SnapshotStore(dir.resolve("empty"), null));

            JsonNode document = Json.readTree(Files.readAllBytes(data.resolve("profile.json")));
            assertEquals("[0,2]", document.get("versions").toString());
            assertEquals("[\"3.34.0\",\"3.35.0\"]", document.get("releases").toString());
            Map<String, ProfileSnapshot> after = ProfileDocument.read(data.resolve("profile.json"));
            assertEquals(before, after);
            assertEquals(Map.of("io.netty:netty-common", "runtime", "io.vertx:vertx-core", "build"),
                    after.get("3.34.0").used());
            assertEquals(List.of("io.vertx:vertx-core!x:y"), after.get("3.34.0").starts().get("io.vertx:vertx-core"));
            assertEquals(List.of("io.quarkus:quarkus-netty"),
                    after.get("3.35.0").parents().get("io.netty:netty-common"));
        }
    }

    @Test
    void noProfileNoFile() throws Exception {
        try (ScratchDir dir = ScratchDir.create()) {
            Path data = Files.createDirectories(dir.resolve("data"));
            Files.writeString(data.resolve("versions.json"), "{\"versions\":[{\"v\":\"3.35.0\"}]}");
            // a file without the names of its releases cannot be read again: one of an older preview
            Files.writeString(data.resolve("profile.json"), "{\"versions\":[0],\"artifacts\":[]}");
            ProfileDocument.write(data, new SnapshotStore(dir.resolve("snapshots"), null));
            assertFalse(Files.exists(data.resolve("profile.json")), "nothing to keep, so it is removed");
        }
    }

    private static ProfileSnapshot profile(String version, Map<String, String> used,
            Map<String, List<String>> parents, Map<String, List<String>> starts) {
        return new ProfileSnapshot(version, 1, 1, 0, List.of(), new TreeMap<>(used), new TreeMap<>(parents),
                new TreeMap<>(starts));
    }
}
