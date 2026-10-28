package io.quarkiverse.almanac.dataset;

import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ClassesSnapshot;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;

import java.util.Objects;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/// The dataset in decoded form: every known release with its date, and the snapshots it holds per kind. Decoding the
/// files gives it ([Dataset#contents()]); [DatasetEncoder] turns it back into files; [DatasetEditor] changes it.
/// Maps are keyed by release version in [ReleaseOrder].
final class Contents {

    /// Every known release → its date (null when unknown). For a release with a BOM it equals the BOM's date.
    private final SortedMap<String, String> dates = new TreeMap<>(ReleaseOrder.COMPARATOR);
    private final SortedMap<String, BomSnapshot> boms = new TreeMap<>(ReleaseOrder.COMPARATOR);
    private final SortedMap<String, ConfigSnapshot> configs = new TreeMap<>(ReleaseOrder.COMPARATOR);
    private final SortedMap<String, ExtensionsSnapshot> extensions = new TreeMap<>(ReleaseOrder.COMPARATOR);
    private final SortedMap<String, ClassesSnapshot> classes = new TreeMap<>(ReleaseOrder.COMPARATOR);

    /// Every known release, in release order.
    SortedSet<String> releases() {
        SortedSet<String> all = new TreeSet<>(ReleaseOrder.COMPARATOR);
        all.addAll(dates.keySet());
        all.addAll(boms.keySet());
        all.addAll(configs.keySet());
        all.addAll(extensions.keySet());
        all.addAll(classes.keySet());
        return all;
    }

    /// The date of a release: its BOM's date when the dataset has its BOM, otherwise the date recorded for it.
    String date(String version) {
        BomSnapshot bom = boms.get(version);
        return bom != null ? bom.date() : dates.get(version);
    }

    boolean isKnown(String version) {
        return dates.containsKey(version);
    }

    /// Records a known release; an existing date is kept unless `date` is not null.
    void addRelease(String version, String date) {
        if (date != null || !dates.containsKey(version)) {
            dates.put(version, date);
        }
    }

    /// Forgets a release; it must hold no data.
    void forgetRelease(String version) {
        if (holdsData(version)) {
            throw new IllegalStateException(version + " still holds data");
        }
        dates.remove(version);
    }

    /// Whether the release holds a snapshot of any kind.
    boolean holdsData(String version) {
        for (Kind kind : Kind.values()) {
            if (has(version, kind)) {
                return true;
            }
        }
        return false;
    }

    SortedMap<String, BomSnapshot> boms() {
        return boms;
    }

    SortedMap<String, ConfigSnapshot> configs() {
        return configs;
    }

    SortedMap<String, ExtensionsSnapshot> extensions() {
        return extensions;
    }

    SortedMap<String, ClassesSnapshot> classes() {
        return classes;
    }

    SortedMap<String, String> dates() {
        return dates;
    }

    boolean has(String version, Kind kind) {
        return snapshots(kind).containsKey(version);
    }

    /// The snapshot of a kind, null when the dataset does not hold it.
    Object get(String version, Kind kind) {
        return snapshots(kind).get(version);
    }

    /// Stores a snapshot (the release becomes known; a BOM sets its date).
    void put(String version, Kind kind, Object snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        switch (kind) {
            case BOM -> {
                BomSnapshot bom = (BomSnapshot) snapshot;
                boms.put(version, bom);
                dates.put(version, bom.date());
            }
            case CONFIG -> {
                configs.put(version, (ConfigSnapshot) snapshot);
                addRelease(version, null);
            }
            case EXTENSIONS -> {
                extensions.put(version, (ExtensionsSnapshot) snapshot);
                addRelease(version, null);
            }
            case CLASSES -> {
                classes.put(version, (ClassesSnapshot) snapshot);
                addRelease(version, null);
            }
        }
    }

    /// Removes a snapshot; the release stays known with its date. Returns whether there was one.
    boolean remove(String version, Kind kind) {
        return snapshots(kind).remove(version) != null;
    }

    private SortedMap<String, ?> snapshots(Kind kind) {
        return switch (kind) {
            case BOM -> boms;
            case CONFIG -> configs;
            case EXTENSIONS -> extensions;
            case CLASSES -> classes;
        };
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Contents that)) {
            return false;
        }
        if (!releases().equals(that.releases())) {
            return false;
        }
        for (String version : releases()) {
            if (!Objects.equals(date(version), that.date(version))) {
                return false;
            }
        }
        return boms.equals(that.boms) && configs.equals(that.configs) && extensions.equals(that.extensions)
                && classes.equals(that.classes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(releases(), boms, configs, extensions, classes);
    }
}
