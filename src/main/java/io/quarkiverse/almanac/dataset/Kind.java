package io.quarkiverse.almanac.dataset;

import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ClassesSnapshot;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import io.quarkiverse.almanac.util.SnapshotStore;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/// The kinds of release data the dataset keeps, each one restored as one work snapshot file.
public enum Kind {
    /// Managed dependency versions: `bom.json`, a [BomSnapshot].
    BOM("bom", SnapshotStore.BOM, BomSnapshot.class),
    /// Config properties: `config.json`, a [ConfigSnapshot].
    CONFIG("config", SnapshotStore.CONFIG, ConfigSnapshot.class),
    /// Extension metadata: `extensions.json`, an [ExtensionsSnapshot].
    EXTENSIONS("extensions", SnapshotStore.EXTENSIONS, ExtensionsSnapshot.class),
    /// The classes of the `io.quarkus` jars: `classes.json`, a [ClassesSnapshot].
    CLASSES("classes", SnapshotStore.CLASSES, ClassesSnapshot.class),
    /// The platform BOM `io.quarkus.platform:quarkus-bom` of the same version: `platform-bom.json`, a [BomSnapshot].
    /// The dataset keeps how it differs from the release's BOM, which it needs.
    PLATFORM("platform", SnapshotStore.PLATFORM_BOM, BomSnapshot.class);

    private final String label;
    private final String snapshotFile;
    private final Class<?> recordType;

    Kind(String label, String snapshotFile, Class<?> recordType) {
        this.label = label;
        this.snapshotFile = snapshotFile;
        this.recordType = recordType;
    }

    /// The name used on the command line and in `releases.jsonl`: `bom`, `config`, `extensions`, `classes` or
    /// `platform`.
    public String label() {
        return label;
    }

    /// The work snapshot file of this kind, e.g. `bom.json`.
    public String snapshotFile() {
        return snapshotFile;
    }

    /// The snapshot record of this kind.
    public Class<?> recordType() {
        return recordType;
    }

    /// The kind restored as the snapshot file `name`; empty for files the dataset never keeps, such as
    /// `config-model.json` and `config-bytecode.json`.
    public static Optional<Kind> ofSnapshotFile(String name) {
        for (Kind kind : values()) {
            if (kind.snapshotFile.equals(name)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /// Parses a comma-separated list of labels such as `bom,config,extensions,platform`, or `all`.
    ///
    /// @throws IllegalArgumentException for an unknown label or an empty list
    public static Set<Kind> parseList(String text) {
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        for (String part : text.split(",")) {
            String label = part.strip().toLowerCase(Locale.ROOT);
            if (label.isEmpty()) {
                continue;
            }
            if (label.equals("all")) {
                kinds.addAll(EnumSet.allOf(Kind.class));
                continue;
            }
            Kind match = null;
            for (Kind kind : values()) {
                if (kind.label.equals(label)) {
                    match = kind;
                }
            }
            if (match == null) {
                throw new IllegalArgumentException("unknown kind '" + part.strip() + "': expected bom, config, "
                        + "extensions, classes, platform or all");
            }
            kinds.add(match);
        }
        if (kinds.isEmpty()) {
            throw new IllegalArgumentException("no kind given: expected bom, config, extensions, classes, platform "
                    + "or all");
        }
        return kinds;
    }

    @Override
    public String toString() {
        return label;
    }
}
