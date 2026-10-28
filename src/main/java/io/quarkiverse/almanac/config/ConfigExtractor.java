package io.quarkiverse.almanac.config;

import io.quarkiverse.almanac.classes.ClassesReader;
import io.quarkiverse.almanac.config.bytecode.BytecodeConfigReader;
import io.quarkiverse.almanac.config.sourcedoc.SourceDocAnnotations;
import io.quarkiverse.almanac.extensions.ExtensionMetadataReader;
import io.quarkiverse.almanac.maven.Http;
import io.quarkiverse.almanac.maven.MavenRepo;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ClassesSnapshot;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.SnapshotStore;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/// Writes the config and extension snapshots of a release: `config.json` from the selected reader,
/// `config-model.json` / `config-bytecode.json` additionally for [Source#BOTH], and `extensions.json`; and
/// `classes.json` ([ClassesReader]), from the central directories that reading the jars fetches anyway. A snapshot
/// without properties is never written (it would read as "everything removed"), and nothing is written for a release
/// whose config or extension metadata cannot be read completely: every snapshot is computed before the first one is
/// written. Nothing is written either while the repository's requests are stopped ([Http#stopped()]): a reader may
/// have taken a refused request (or the 403 that stopped them) for a missing jar, class or source file.
public final class ConfigExtractor {

    /// Which reader produces `config.json`.
    public enum Source {
        /// The model when any jar contains it, otherwise bytecode.
        AUTO,
        MODEL,
        BYTECODE,
        /// Run both readers, write both files; `config.json` as for [#AUTO].
        BOTH
    }

    /// Where the bytecode reader gets the `@ConfigDoc*` annotations that the class files of releases before 3.14 lack
    ///.
    public enum DocAnnotationMode {
        /// From the release's `-sources` jars ([SourceDocAnnotations]).
        SOURCES,
        /// None: class files only, as the reference prototype reads them.
        NONE
    }

    /// Config roots list present in jars with config since 3.0 (both eras).
    private static final String CONFIG_ROOTS_ENTRY = "META-INF/quarkus-config-roots.list";

    private final MavenRepo repo;
    private final SnapshotStore store;
    private final ConfigReader modelReader = new ModelJsonConfigReader();
    private final ConfigReader bytecodeReader;
    private final ExtensionMetadataReader extensionReader = new ExtensionMetadataReader();

    /// An extractor whose bytecode reader takes pre-3.14 doc annotations from the sources jars.
    public ConfigExtractor(MavenRepo repo, SnapshotStore store) {
        this(repo, store, DocAnnotationMode.SOURCES);
    }

    /// @param docAnnotations where the bytecode reader gets the doc annotations of releases before 3.14
    public ConfigExtractor(MavenRepo repo, SnapshotStore store, DocAnnotationMode docAnnotations) {
        this.repo = repo;
        this.store = store;
        this.bytecodeReader = docAnnotations == DocAnnotationMode.SOURCES
                ? new BytecodeConfigReader(jars -> SourceDocAnnotations.create(repo, jars))
                : new BytecodeConfigReader();
    }

    /// Extracts one release unless its snapshots exist already (and `force` is false).
    ///
    /// @return false when no properties were found, so nothing was written
    /// @throws Http.StoppedException when the repository's requests stopped before or while the release was read;
    ///                               nothing was written then
    public boolean extract(BomSnapshot bom, Source source, boolean force) throws IOException {
        String version = bom.version();
        if (!force && upToDate(version, source)) {
            Log.info("config %s: reusing existing snapshots", version);
            return true;
        }
        failIfStopped(version);
        long start = System.nanoTime();
        VersionJars jars = VersionJars.of(bom, repo);
        jars.index();
        boolean hasModel = !jars.jarsContaining(ModelJsonConfigReader.MODEL_ENTRY).isEmpty();
        // config-bytecode.json / config-model.json of --source both, written with the others
        Map<String, ConfigSnapshot> sideFiles = new LinkedHashMap<>();
        ConfigSnapshot config = switch (source) {
            case AUTO -> (hasModel ? modelReader : bytecodeReader).read(jars);
            case MODEL -> {
                if (!hasModel) {
                    throw new IOException(version + ": no jar contains " + ModelJsonConfigReader.MODEL_ENTRY
                            + "; use --source auto or bytecode");
                }
                yield modelReader.read(jars);
            }
            case BYTECODE -> bytecodeReader.read(jars);
            case BOTH -> readBoth(jars, hasModel, sideFiles);
        };
        if (config.properties().isEmpty()) {
            Log.warn("config %s: the %s reader found no properties (%s); no config or extension snapshot written",
                    version, config.source(), String.join("; ", config.warnings()));
            return false;
        }
        // read everything before writing anything: a release whose extension metadata cannot be read (e.g. offline
        // without the cached jar) must not leave a config.json without its extensions.json
        ExtensionsSnapshot extensions = extensionReader.read(jars);
        // the central directories of every jar are read already (indexing fetched them): no further request
        ClassesSnapshot classes = ClassesReader.read(version, jars.jars());
        failIfStopped(version);
        for (Map.Entry<String, ConfigSnapshot> sideFile : sideFiles.entrySet()) {
            writeIfNotEmpty(sideFile.getValue(), sideFile.getKey());
        }
        store.write(version, SnapshotStore.CONFIG, config);
        store.write(version, SnapshotStore.EXTENSIONS, extensions);
        store.write(version, SnapshotStore.CLASSES, classes);

        int withConfig = 0;
        for (JarSource jar : jars.jars()) {
            if (jar.contains(ModelJsonConfigReader.MODEL_ENTRY) || jar.contains(CONFIG_ROOTS_ENTRY)) {
                withConfig++;
            }
        }
        Log.info("config %s: %d jars indexed (%d of %d listed missing, %d relocated), %d with config, "
                        + "%d properties from %s, %d config roots, %d extensions, %d classes in %d jars, %d warnings, "
                        + "%.1f s",
                version, jars.jars().size(), jars.candidateCount() - jars.jars().size(), jars.candidateCount(),
                jars.relocations().size(), withConfig, config.properties().size(), config.source(), config.roots(),
                extensions.extensions().size(), ClassesReader.classCount(classes), classes.artifacts().size(),
                config.warnings().size(), (System.nanoTime() - start) / 1e9);
        for (String warning : config.warnings()) {
            Log.debug("%s: %s", version, warning);
        }
        return true;
    }

    /// Runs both readers; the snapshots for `config-bytecode.json` and `config-model.json` go to `sideFiles`.
    ///
    /// @return the snapshot for `config.json`: the model's when the release has one, else the bytecode one
    private ConfigSnapshot readBoth(VersionJars jars, boolean hasModel, Map<String, ConfigSnapshot> sideFiles)
            throws IOException {
        String version = jars.quarkusVersion();
        ConfigSnapshot bytecode = bytecodeReader.read(jars);
        sideFiles.put(SnapshotStore.CONFIG_BYTECODE, bytecode);
        if (!hasModel) {
            Log.info("config %s: no config model in this release, only the bytecode reader ran", version);
            return bytecode;
        }
        ConfigSnapshot model = modelReader.read(jars);
        sideFiles.put(SnapshotStore.CONFIG_MODEL, model);
        return model;
    }

    /// Fails while the repository's requests are stopped: what the readers got may lack what a refused request held.
    private void failIfStopped(String version) throws Http.StoppedException {
        Optional<String> reason = repo.http().stopReason();
        if (reason.isPresent()) {
            throw new Http.StoppedException("requests stopped after " + reason.get() + ", so no snapshot of "
                    + version + " is written");
        }
    }

    private void writeIfNotEmpty(ConfigSnapshot snapshot, String file) throws IOException {
        if (snapshot.properties().isEmpty()) {
            Log.warn("config %s: %s reader found no properties (%s); %s not written", snapshot.version(),
                    snapshot.source(), String.join("; ", snapshot.warnings()), file);
        } else {
            store.write(snapshot.version(), file, snapshot);
        }
    }

    private boolean upToDate(String version, Source source) throws IOException {
        if (!store.exists(version, SnapshotStore.CONFIG) || !store.exists(version, SnapshotStore.EXTENSIONS)) {
            return false;
        }
        if (source == Source.AUTO) {
            return true;
        }
        String existing = store.read(version, SnapshotStore.CONFIG, ConfigSnapshot.class).source();
        return switch (source) {
            case MODEL -> "model".equals(existing);
            case BYTECODE -> "bytecode".equals(existing);
            default -> store.exists(version, SnapshotStore.CONFIG_BYTECODE)
                    && (store.exists(version, SnapshotStore.CONFIG_MODEL) || !"model".equals(existing));
        };
    }
}
