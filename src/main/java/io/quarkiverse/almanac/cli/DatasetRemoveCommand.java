package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.dataset.DatasetEditor;
import io.quarkiverse.almanac.dataset.Kind;
import io.quarkiverse.almanac.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/// `dataset remove`: drops snapshots from the committed dataset (for a bad import); the releases stay known.
@Command(name = "remove", description = "Remove snapshots of the given releases from the dataset; the releases stay "
        + "in releases.jsonl. Removing a BOM removes the release's config, extensions, classes and platform BOM, "
        + "removing extensions removes the config (it needs them); config, classes and the platform BOM can go alone.")
public final class DatasetRemoveCommand implements Callable<Integer> {

    @Mixin
    CommonOptions common;

    @Spec
    CommandSpec spec;

    @Option(names = "--versions", required = true, paramLabel = "LIST", description = "Comma-separated releases.")
    String versions;

    @Option(names = "--kinds", defaultValue = "all", paramLabel = "KINDS",
            description = "Comma-separated: bom, config, extensions, classes, platform, or all (default: "
                    + "${DEFAULT-VALUE}).")
    String kinds;

    @Override
    public Integer call() throws IOException {
        Set<Kind> chosen = DatasetCommand.kinds(spec, kinds);
        List<String> releases = DatasetCommand.versionList(spec, versions);
        try (Workspace workspace = Workspace.open(common)) {
            DatasetEditor editor = DatasetEditor.open(workspace.datasetDir());
            List<String> removed = editor.remove(releases, chosen);
            Log.info("dataset remove: %d snapshots removed%s", removed.size(),
                    removed.isEmpty() ? "" : " (" + String.join(", ", removed) + ")");
            DatasetCommand.write(editor);
            return 0;
        }
    }
}
