package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.config.ConfigExtractor;
import io.quarkiverse.almanac.dataset.Dataset;
import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.maven.RemoteJar;
import io.quarkiverse.almanac.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;

/// `config`: writes `config.json`, `extensions.json` and `classes.json` snapshots of the releases the committed dataset
/// does not have yet.
@Command(name = "config", description = "Write config.json and extensions.json snapshots of the releases the dataset "
        + "does not have yet, and classes.json from the same reads of the jars.")
public final class ConfigCommand implements Callable<Integer> {

    @Mixin
    CommonOptions common;

    @Option(names = "--versions", defaultValue = "streams", paramLabel = "all|streams|LIST",
            description = "Releases to process (default: ${DEFAULT-VALUE}).")
    String versions;

    @Option(names = "--source", defaultValue = "auto", paramLabel = "auto|model|bytecode|both",
            description = "Config reader: auto = model when the release ships the config model, else bytecode; "
                    + "both = run both and also write config-model.json and config-bytecode.json "
                    + "(default: ${DEFAULT-VALUE}).")
    ConfigExtractor.Source source;

    @Option(names = "--force", description = "Also process releases the dataset has, and rewrite existing snapshots.")
    boolean force;

    @Option(names = "--doc-annotations", defaultValue = "sources", paramLabel = "sources|none",
            description = "Where the bytecode reader gets the @ConfigDoc* annotations that class files before 3.14 "
                    + "lack: sources = the -sources jars, none = class files only (default: ${DEFAULT-VALUE}).")
    ConfigExtractor.DocAnnotationMode docAnnotations;

    @Option(names = "--full-jar-max", defaultValue = "" + RemoteJar.Options.DEFAULT_FULL_JAR_MAX, paramLabel = "BYTES",
            description = "Jars up to this size are downloaded whole when an entry is read; larger ones are read "
                    + "per entry with range requests (default: ${DEFAULT-VALUE}).")
    long fullJarMax;

    @Override
    public Integer call() throws IOException {
        try (Workspace workspace = Workspace.open(common, jarOptions(fullJarMax))) {
            List<QuarkusVersion> selected = notInDataset(workspace, workspace.select(versions), source, force);
            return selected.isEmpty() || extract(workspace, selected, source, force, docAnnotations) ? 0 : 1;
        }
    }

    static RemoteJar.Options jarOptions(long fullJarMax) {
        return new RemoteJar.Options(RemoteJar.Options.DEFAULT_TAIL_SIZE, fullJarMax);
    }

    /// The releases whose config the dataset does not have (all of them with `force`), logging how many it has. The
    /// dataset has a release's config when it holds its config and extensions snapshots from the reader `source`
    /// asks for (any reader for `auto`); `both` always extracts, since the dataset keeps neither `config-model.json`
    /// nor `config-bytecode.json`. A release never published on Maven Central has no jars to read, even with `force`.
    static List<QuarkusVersion> notInDataset(Workspace workspace, List<QuarkusVersion> versions,
            ConfigExtractor.Source source, boolean force) throws IOException {
        versions = Workspace.published(versions, "config");
        if (force || source == ConfigExtractor.Source.BOTH) {
            return versions;
        }
        Dataset dataset = workspace.dataset();
        List<QuarkusVersion> missing = versions.stream().filter(version -> {
            Optional<Dataset.Release> release = dataset.release(version.text());
            if (release.isEmpty() || release.get().config() == null || !release.get().extensions()) {
                return true;
            }
            String reader = release.get().config().source();
            return switch (source) {
                case MODEL -> !"model".equals(reader);
                case BYTECODE -> !"bytecode".equals(reader);
                default -> false;
            };
        }).toList();
        int known = versions.size() - missing.size();
        if (known > 0) {
            Log.info("config: %d releases already in the dataset (of %d selected; --force processes them again)",
                    known, versions.size());
        }
        return missing;
    }

    /// Extracts the releases one after the other (each one in parallel internally); false when any failed or was not
    /// attempted.
    static boolean extract(Workspace workspace, List<QuarkusVersion> versions, ConfigExtractor.Source source,
            boolean force) {
        return extract(workspace, versions, source, force, ConfigExtractor.DocAnnotationMode.SOURCES);
    }

    /// Extracts the releases one after the other (each one in parallel internally), until requests stop
    /// ([ReleaseRun]); false when any failed or was not attempted.
    ///
    /// @param docAnnotations where the bytecode reader gets the doc annotations of releases before 3.14
    static boolean extract(Workspace workspace, List<QuarkusVersion> versions, ConfigExtractor.Source source,
            boolean force, ConfigExtractor.DocAnnotationMode docAnnotations) {
        Log.info("config: %d releases, source %s, doc annotations %s", versions.size(),
                source.name().toLowerCase(Locale.ROOT), docAnnotations.name().toLowerCase(Locale.ROOT));
        ConfigExtractor extractor = new ConfigExtractor(workspace.repo(), workspace.snapshots(), docAnnotations);
        ReleaseRun run = new ReleaseRun("config", workspace.repo().http(), versions.size());
        int failed = 0;
        for (QuarkusVersion version : versions) {
            if (!run.mayStart()) {
                continue;
            }
            try {
                if (!extractor.extract(workspace.boms().load(version.text(), false), source, force)) {
                    failed++;
                }
            } catch (IOException e) {
                Log.error("config %s: %s", version, e.getMessage());
                run.failed(e);
                failed++;
            } catch (RuntimeException e) {
                // one broken release must not stop the others
                Log.error("config %s: %s", version, e);
                run.failed(e);
                failed++;
            }
        }
        if (failed > 0) {
            Log.error("config: %d of %d releases produced no snapshot", failed, versions.size());
        }
        return run.finish() && failed == 0;
    }
}
