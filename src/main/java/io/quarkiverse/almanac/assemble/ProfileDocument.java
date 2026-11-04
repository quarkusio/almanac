package io.quarkiverse.almanac.assemble;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkiverse.almanac.model.ProfileSnapshot;
import io.quarkiverse.almanac.util.Json;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.SnapshotStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/// `profile.json`, a preview: the dependency profile of the releases that have one, over those releases alone.
/// `versions` lists their indices in `versions.json` and `releases` their names, in the same order, `artifacts` each
/// managed id that Quarkus depends on at one of them, with change points of its strongest use: `"r"` (runtime), `"b"`
/// (build) or `"t"` (test), `null` where Quarkus does not depend on it. `parents` holds, the same way, the graph of the
/// walks upward ([ProfileSnapshot#parents]): each node → the nodes whose dependency brought it, as indices into `ids`,
/// the sorted list of the nodes that some node has as a parent or start; `starts`, each used id whose ways up do not
/// start at the node written as the id alone → the nodes they start at ([ProfileSnapshot#starts]), as indices too.
///
/// The profiles come from the `profile.json` snapshots of the work folder (`quarkus-almanac profile`), and for the
/// other releases from the file as it was, which its `releases` let this read again whatever indices `versions.json`
/// gives them now: a run without those snapshots (the one that adds new releases) keeps them.
public final class ProfileDocument {

    /// The snapshot file of a dependency profile.
    public static final String SNAPSHOT = "profile.json";

    private static final Map<String, String> USES = Map.of("r", "runtime", "b", "build", "t", "test");

    private ProfileDocument() {
    }

    /// Writes `profile.json` next to `versions.json` in `dataDir`, from the profile snapshots in `work` and the file as
    /// it was; removes it when no release of `versions.json` has a profile.
    public static void write(Path dataDir, SnapshotStore work) throws IOException {
        JsonNode versions = Json.readTree(Files.readAllBytes(dataDir.resolve("versions.json"))).get("versions");
        Path file = dataDir.resolve("profile.json");
        Map<String, ProfileSnapshot> earlier = Files.exists(file) ? read(file) : Map.of();
        Map<Integer, ProfileSnapshot> profiles = new LinkedHashMap<>();
        int kept = 0;
        for (int vi = 0; vi < versions.size(); vi++) {
            String version = versions.get(vi).get("v").asText();
            if (work.inWork(version, SNAPSHOT)) {
                profiles.put(vi, work.read(version, SNAPSHOT, ProfileSnapshot.class));
            } else if (earlier.containsKey(version)) {
                profiles.put(vi, earlier.get(version));
                kept++;
            }
        }
        if (profiles.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        // the nodes that parents and starts name, once each: a list of them is a list of indices into these
        TreeSet<String> names = new TreeSet<>();
        for (ProfileSnapshot profile : profiles.values()) {
            if (profile.parents() != null) {
                profile.parents().values().forEach(names::addAll);
            }
            if (profile.starts() != null) {
                profile.starts().values().forEach(names::addAll);
            }
        }
        Map<String, Integer> index = new HashMap<>();
        ArrayNode ids = JsonNodeFactory.instance.arrayNode();
        for (String name : names) {
            index.put(name, index.size());
            ids.add(name);
        }
        ChangePoints history = new ChangePoints();
        ChangePoints parents = new ChangePoints();
        ChangePoints starts = new ChangePoints();
        ArrayNode indices = JsonNodeFactory.instance.arrayNode();
        ArrayNode releases = JsonNodeFactory.instance.arrayNode();
        for (Map.Entry<Integer, ProfileSnapshot> entry : profiles.entrySet()) {
            int vi = entry.getKey();
            ProfileSnapshot profile = entry.getValue();
            Map<String, JsonNode> used = new HashMap<>();
            profile.used().forEach((id, use) -> used.put(id, TextNode.valueOf(use.substring(0, 1))));
            history.observe(vi, used);
            parents.observe(vi, numbered(profile.parents(), index));
            starts.observe(vi, numbered(profile.starts(), index));
            indices.add(vi);
            releases.add(versions.get(vi).get("v").asText());
        }
        ObjectNode document = JsonNodeFactory.instance.objectNode();
        document.set("versions", indices);
        document.set("releases", releases);
        document.set("artifacts", history.toJson("id"));
        document.set("ids", ids);
        document.set("parents", parents.toJson("id"));
        document.set("starts", starts.toJson("id"));
        Json.writeCompact(file, document);
        Log.info("assemble: wrote %s (a preview), the dependency profile of %d releases (%d of them kept from the "
                + "file, without a snapshot in the work folder), %d artifacts, %d nodes", file, indices.size(), kept,
                history.size(), parents.size());
    }

    /// The profiles that a `profile.json` holds, by release, as far as it says: their uses, parents and starts. Empty
    /// for a file without `releases` (an older one), which cannot be read against the release list it was written
    /// with.
    static Map<String, ProfileSnapshot> read(Path file) throws IOException {
        JsonNode document = Json.readTree(Files.readAllBytes(file));
        JsonNode versions = document.path("versions");
        JsonNode releases = document.path("releases");
        if (!versions.isArray() || !releases.isArray() || versions.size() != releases.size()) {
            return Map.of();
        }
        List<String> ids = new ArrayList<>();
        document.path("ids").forEach(id -> ids.add(id.asText()));
        Map<String, ProfileSnapshot> profiles = new LinkedHashMap<>();
        for (int k = 0; k < versions.size(); k++) {
            int vi = versions.get(k).asInt();
            TreeMap<String, String> used = new TreeMap<>();
            for (JsonNode artifact : document.path("artifacts")) {
                JsonNode use = at(artifact.get("h"), vi);
                if (use != null && USES.containsKey(use.asText())) {
                    used.put(artifact.get("id").asText(), USES.get(use.asText()));
                }
            }
            profiles.put(releases.get(k).asText(), new ProfileSnapshot(releases.get(k).asText(), 0, 0, 0, List.of(),
                    used, named(document.path("parents"), vi, ids), named(document.path("starts"), vi, ids)));
        }
        return profiles;
    }

    /// The value of a history of change points (`[[vi, value], …]`) at release `vi`; null where it is null or not yet
    /// set.
    private static JsonNode at(JsonNode history, int vi) {
        JsonNode value = null;
        if (history != null) {
            for (JsonNode point : history) {
                if (point.get(0).asInt() > vi) {
                    break;
                }
                value = point.get(1);
            }
        }
        return value == null || value.isNull() ? null : value;
    }

    /// Each key's list of indices into `ids` at release `vi`, as the nodes they stand for.
    private static TreeMap<String, List<String>> named(JsonNode histories, int vi, List<String> ids) {
        TreeMap<String, List<String>> lists = new TreeMap<>();
        for (JsonNode entry : histories) {
            JsonNode numbers = at(entry.get("h"), vi);
            if (numbers != null) {
                List<String> nodes = new ArrayList<>();
                numbers.forEach(number -> nodes.add(ids.get(number.asInt())));
                lists.put(entry.get("id").asText(), nodes);
            }
        }
        return lists;
    }

    /// Each key's list of nodes as sorted indices into `ids`.
    private static Map<String, JsonNode> numbered(Map<String, List<String>> lists, Map<String, Integer> index) {
        Map<String, JsonNode> numbered = new HashMap<>();
        if (lists != null) {
            lists.forEach((key, list) -> {
                ArrayNode numbers = JsonNodeFactory.instance.arrayNode();
                list.stream().map(index::get).sorted().forEach(numbers::add);
                numbered.put(key, numbers);
            });
        }
        return numbered;
    }
}
