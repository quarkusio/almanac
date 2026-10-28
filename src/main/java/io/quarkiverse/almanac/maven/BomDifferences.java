package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.model.ManagedDep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/// How the platform BOM of a release differs from the release's own BOM, entry by entry: the form in which the dataset
/// and the site data keep platform BOMs, since the two differ in a few entries only.
public final class BomDifferences {

    /// Value of an entry that the release's BOM manages and the platform BOM does not.
    public static final String NOT_MANAGED = "-";

    /// Value of a managed version equal to the release's own version.
    public static final String SAME_AS_RELEASE = "=";

    private BomDifferences() {
    }

    /// Every id that the two BOMs do not manage alike, sorted: → the platform BOM's version (`"="` when it is the
    /// release's own), or [#NOT_MANAGED] when only the release's BOM manages it.
    ///
    /// @throws IllegalArgumentException when a BOM lists an id twice
    public static SortedMap<String, String> of(String version, List<ManagedDep> bom, List<ManagedDep> platform) {
        Map<String, String> own = byId(version, bom, "bom");
        Map<String, String> other = byId(version, platform, "platform BOM");
        SortedMap<String, String> differences = new TreeMap<>();
        other.forEach((id, v) -> {
            if (!v.equals(own.get(id))) {
                differences.put(id, v.equals(version) ? SAME_AS_RELEASE : v);
            }
        });
        own.keySet().forEach(id -> {
            if (!other.containsKey(id)) {
                differences.put(id, NOT_MANAGED);
            }
        });
        return differences;
    }

    /// The platform BOM's managed dependencies, sorted by id: the release's BOM with `differences` ([#of]) applied.
    ///
    /// @throws IllegalArgumentException when a difference says [#NOT_MANAGED] of an id the release's BOM does not
    ///                                  manage, or the BOM lists an id twice
    public static List<ManagedDep> apply(String version, List<ManagedDep> bom, Map<String, String> differences) {
        SortedMap<String, String> managed = new TreeMap<>(byId(version, bom, "bom"));
        differences.forEach((id, value) -> {
            if (value.equals(NOT_MANAGED)) {
                if (managed.remove(id) == null) {
                    throw new IllegalArgumentException("platform BOM " + version + ": " + id + " is marked as not "
                            + "managed, but the release's BOM does not manage it either");
                }
            } else {
                managed.put(id, value.equals(SAME_AS_RELEASE) ? version : value);
            }
        });
        List<ManagedDep> deps = new ArrayList<>(managed.size());
        managed.forEach((id, v) -> deps.add(new ManagedDep(id, v)));
        return Collections.unmodifiableList(deps);
    }

    private static Map<String, String> byId(String version, List<ManagedDep> deps, String what) {
        Map<String, String> byId = new TreeMap<>();
        for (ManagedDep dep : deps) {
            if (byId.put(dep.id(), dep.v()) != null) {
                throw new IllegalArgumentException(what + " " + version + ": duplicate id " + dep.id());
            }
        }
        return byId;
    }
}
