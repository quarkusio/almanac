package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.dataset.Dataset;
import io.quarkiverse.almanac.dataset.Kind;
import io.quarkiverse.almanac.util.AtomicFiles;
import io.quarkiverse.almanac.util.Json;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.SnapshotStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/// `dataset export`: restores snapshots of the dataset as snapshot files, exactly as `bom` and `config` write them.
@Command(name = "export", description = "Write snapshots of the dataset as snapshot files <out>/<version>/bom.json, "
        + "config.json, extensions.json, platform-bom.json (pretty-printed, as bom, config and platform write them). "
        + "Existing files that differ are kept unless --force.")
public final class DatasetExportCommand implements Callable<Integer> {

    @Mixin
    CommonOptions common;

    @Spec
    CommandSpec spec;

    @Option(names = "--versions", defaultValue = "all", paramLabel = "all|LIST",
            description = "all = every release of the dataset, or a comma-separated list (default: ${DEFAULT-VALUE}).")
    String versions;

    @Option(names = "--kinds", defaultValue = "bom,config,extensions", paramLabel = "KINDS",
            description = "Comma-separated: bom, config, extensions, platform, or all (default: ${DEFAULT-VALUE}).")
    String kinds;

    @Option(names = "--out", paramLabel = "DIR",
            description = "Folder of the version folders (default: the work snapshots folder, <work>/snapshots, where "
                    + "verify snapshots and verify official look for them).")
    Path out;

    @Option(names = "--force", description = "Overwrite existing files that differ.")
    boolean force;

    @Override
    public Integer call() throws IOException {
        Set<Kind> chosen = DatasetCommand.kinds(spec, kinds);
        try (Workspace workspace = Workspace.open(common)) {
            Dataset dataset = Dataset.open(workspace.datasetDir());
            SnapshotStore target = out != null ? new SnapshotStore(out, null) : workspace.workSnapshots();
            boolean everything = versions.strip().equals("all");
            List<String> releases = everything
                    ? dataset.releases().stream().map(Dataset.Release::version).toList()
                    : DatasetCommand.versionList(spec, versions);
            int written = 0;
            int unchanged = 0;
            int kept = 0;
            for (String version : releases) {
                if (dataset.release(version).isEmpty()) {
                    throw new CommandLine.ParameterException(spec.commandLine(), "the dataset does not know " + version);
                }
                for (Kind kind : chosen) {
                    if (!dataset.has(version, kind)) {
                        if (!everything) {
                            Log.warn("dataset export: the dataset has no %s snapshot of %s", kind, version);
                        }
                        continue;
                    }
                    Path file = target.file(version, kind.snapshotFile());
                    String content = Json.pretty(dataset.snapshot(version, kind));
                    if (Files.isRegularFile(file)) {
                        if (Files.readString(file, StandardCharsets.UTF_8).equals(content)) {
                            unchanged++;
                            continue;
                        }
                        if (!force) {
                            Log.warn("dataset export: kept %s, which differs from the dataset's (--force overwrites)",
                                    file);
                            kept++;
                            continue;
                        }
                    }
                    AtomicFiles.writeString(file, content);
                    written++;
                }
            }
            Log.info("dataset export: %d files written, %d unchanged, %d kept (differ) in %s", written, unchanged, kept,
                    target.root());
            return kept == 0 ? 0 : 1;
        }
    }
}
