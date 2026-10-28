package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.config.ConfigExtractor;
import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.maven.RemoteJar;
import io.quarkiverse.almanac.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/// `all`: versions → bom (all) → config (streams, with their classes) → dataset import of what they extracted →
/// assemble (from the dataset). Releases the dataset has are not extracted again.
@Command(name = "all", description = "Run versions, bom (all releases), config (newest of each stream, with the "
        + "classes of its jars) for the releases the dataset does not have, import what they extracted into the "
        + "dataset, and assemble the site data from the dataset.")
public final class AllCommand implements Callable<Integer> {

    @Mixin
    CommonOptions common;

    @Option(names = "--source", defaultValue = "auto", paramLabel = "auto|model|bytecode|both",
            description = "Config reader (default: ${DEFAULT-VALUE}).")
    ConfigExtractor.Source source;

    @Option(names = "--force", description = "Also process releases the dataset has, rewrite existing snapshots and "
            + "replace dataset snapshots that differ.")
    boolean force;

    @Option(names = "--full-jar-max", defaultValue = "" + RemoteJar.Options.DEFAULT_FULL_JAR_MAX, paramLabel = "BYTES",
            description = "Jars up to this size are downloaded whole when an entry is read "
                    + "(default: ${DEFAULT-VALUE}).")
    long fullJarMax;

    @Option(names = "--site", defaultValue = "site", paramLabel = "DIR",
            description = "Site folder; the data goes to its data/ subfolder (default: ${DEFAULT-VALUE}).")
    Path site;

    @Override
    public Integer call() throws IOException {
        try (Workspace workspace = Workspace.open(common, ConfigCommand.jarOptions(fullJarMax))) {
            List<QuarkusVersion> all = workspace.select("all");
            List<QuarkusVersion> streams = workspace.select("streams");
            Log.info("versions: %d releases for BOM data, %d for config", all.size(), streams.size());
            List<QuarkusVersion> boms = BomCommand.notInDataset(workspace, all, force);
            boolean bomOk = BomCommand.writeSnapshots(workspace, boms, force);
            List<QuarkusVersion> configs = ConfigCommand.notInDataset(workspace, streams, source, force);
            boolean configOk = configs.isEmpty() || ConfigCommand.extract(workspace, configs, source, force);
            boolean importOk = DatasetImportCommand.importExtracted(workspace, boms, configs, force);
            AssembleCommand.assemble(workspace, site, false);
            return bomOk && configOk && importOk ? 0 : 1;
        }
    }
}
