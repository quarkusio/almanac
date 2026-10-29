package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.dataset.DatasetEditor;
import io.quarkiverse.almanac.dataset.Kind;
import io.quarkiverse.almanac.dataset.ReleaseOrder;
import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.SnapshotStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// `dataset`: the committed dataset of extracted releases.
@Command(name = "dataset", description = "Manage the committed dataset of extracted releases (dataset/): import work "
        + "snapshots, export them back, verify integrity, refresh the release list.",
        subcommands = {DatasetImportCommand.class, DatasetExportCommand.class, DatasetVerifyCommand.class,
                DatasetRefreshReleasesCommand.class, DatasetRemoveCommand.class})
public final class DatasetCommand implements Runnable {

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(),
                "Missing subcommand: import, export, verify, refresh-releases or remove");
    }

    /// Parses `--kinds`, failing as a usage error.
    static Set<Kind> kinds(CommandSpec spec, String text) {
        try {
            return Kind.parseList(text);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage());
        }
    }

    /// The work snapshots that `--versions` (`all` or a comma-separated list) and `--kinds` name. `all` is every
    /// release folder with a snapshot of a requested kind; a listed version is its folder, or a folder whose version
    /// compares equal (`3.0.4` finds `3.0.4.Final`), and each requested kind of it is a candidate (a missing file is
    /// refused when imported).
    static List<DatasetEditor.Candidate> workCandidates(CommandSpec spec, SnapshotStore work, String versions,
            Set<Kind> kinds) throws IOException {
        List<DatasetEditor.Candidate> candidates = new ArrayList<>();
        if (versions.strip().equals("all")) {
            for (Kind kind : kinds) {
                for (String version : work.versionsWith(kind.snapshotFile())) {
                    candidates.add(new DatasetEditor.Candidate(version, kind, work.file(version, kind.snapshotFile())));
                }
            }
        } else {
            for (String version : resolveFolders(spec, work.root(), versions)) {
                for (Kind kind : kinds) {
                    candidates.add(new DatasetEditor.Candidate(version, kind, work.file(version, kind.snapshotFile())));
                }
            }
        }
        candidates.sort((a, b) -> {
            int c = ReleaseOrder.compare(a.version(), b.version());
            return c != 0 ? c : a.kind().compareTo(b.kind());
        });
        return candidates;
    }

    /// The work folders of a comma-separated version list.
    private static List<String> resolveFolders(CommandSpec spec, Path root, String list) throws IOException {
        Set<String> folders = new LinkedHashSet<>();
        for (String part : list.split(",")) {
            String wanted = part.strip();
            if (wanted.isEmpty()) {
                continue;
            }
            if (Files.isDirectory(root.resolve(wanted))) {
                folders.add(wanted);
                continue;
            }
            Optional<QuarkusVersion> parsed = QuarkusVersion.tryParse(wanted);
            Optional<String> match = Optional.empty();
            if (parsed.isPresent() && Files.isDirectory(root)) {
                try (Stream<Path> dirs = Files.list(root)) {
                    match = dirs.filter(Files::isDirectory).map(dir -> dir.getFileName().toString())
                            .filter(name -> QuarkusVersion.tryParse(name)
                                    .filter(v -> v.compareTo(parsed.get()) == 0).isPresent())
                            .sorted().findFirst();
                }
            }
            folders.add(match.orElseThrow(() -> new CommandLine.ParameterException(spec.commandLine(),
                    "no work snapshots of " + wanted + " in " + root)));
        }
        if (folders.isEmpty()) {
            throw new CommandLine.ParameterException(spec.commandLine(), "no version given");
        }
        return ReleaseOrder.sorted(folders);
    }

    /// Logs an import: counts per kind, the warnings of imported snapshots and the snapshots it removed, then each
    /// refusal (unvalidated ones as warnings, others as errors).
    static void logImport(DatasetEditor.ImportReport report) {
        Log.info("dataset import: added %s; replaced %s; unchanged %s; refused %d", perKind(report.added()),
                perKind(report.replaced()), perKind(report.unchanged()), report.refusals().size());
        for (String warning : report.warnings()) {
            Log.warn("dataset import: %s", warning);
        }
        for (String removed : report.removed()) {
            Log.warn("dataset import: removed %s, read from the jars of the classes this import replaces; api extracts "
                    + "it again from the new classes.json of the work folder, unless the api.json there is newer (as "
                    + "when the classes came from elsewhere): then api --force --versions %s does", removed,
                    removed.substring(removed.indexOf(' ') + 1));
        }
        for (DatasetEditor.Refusal refusal : report.refusals()) {
            if (refusal.unvalidated()) {
                Log.warn("dataset import: refused %s", refusal);
            } else {
                Log.error("dataset import: refused %s", refusal);
            }
        }
    }

    /// Writes the edited dataset and logs which files changed.
    static void write(DatasetEditor editor) throws IOException {
        List<String> changed = editor.write();
        if (changed.isEmpty()) {
            Log.info("dataset: no file changed in %s", editor.dir());
        } else {
            Log.info("dataset: wrote %s in %s", String.join(", ", changed), editor.dir());
        }
    }

    /// `3 (bom 2, config 1)` for `kind version` entries.
    private static String perKind(Collection<String> entries) {
        if (entries.isEmpty()) {
            return "0";
        }
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        for (String entry : entries) {
            Kind kind = Kind.parseList(entry.substring(0, entry.indexOf(' '))).iterator().next();
            counts.merge(kind, 1, Integer::sum);
        }
        return entries.size() + " (" + counts.entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(", ")) + ")";
    }

    /// The versions of a comma-separated list, as given (stripped, without duplicates), in release order.
    static List<String> versionList(CommandSpec spec, String list) {
        TreeSet<String> versions = new TreeSet<>(ReleaseOrder.COMPARATOR);
        for (String part : list.split(",")) {
            if (!part.isBlank()) {
                versions.add(part.strip());
            }
        }
        if (versions.isEmpty()) {
            throw new CommandLine.ParameterException(spec.commandLine(), "no version given");
        }
        return List.copyOf(versions);
    }
}
