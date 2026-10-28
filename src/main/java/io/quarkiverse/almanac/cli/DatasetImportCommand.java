package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.dataset.DatasetEditor;
import io.quarkiverse.almanac.dataset.Kind;
import io.quarkiverse.almanac.maven.BomSnapshots;
import io.quarkiverse.almanac.maven.MavenRepo;
import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.SnapshotStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/// `dataset import`: merges work snapshots into the committed dataset.
@Command(name = "import", description = "Merge work snapshots (work/snapshots/<version>/bom.json, config.json, "
        + "extensions.json, classes.json) into the dataset. Refuses inconsistent snapshots, unvalidated config (the "
        + "bytecode/1 extractor, or bytecode config of a release before 3.14 without all its doc annotations), config "
        + "without extensions, config, extensions or classes without the BOM, and, without --force, snapshots that "
        + "differ from the dataset's; the others are imported. Extensions need no config, classes need neither.")
public final class DatasetImportCommand implements Callable<Integer> {

    @Mixin
    CommonOptions common;

    @Spec
    CommandSpec spec;

    @Option(names = "--versions", required = true, paramLabel = "all|LIST",
            description = "all = every release with a work snapshot of the chosen kinds; or a comma-separated list.")
    String versions;

    @Option(names = "--kinds", defaultValue = "all", paramLabel = "KINDS",
            description = "Comma-separated: bom, config, extensions, classes, or all (default: ${DEFAULT-VALUE}).")
    String kinds;

    @Option(names = "--force", description = "Replace dataset snapshots that differ from the work snapshots.")
    boolean force;

    @Override
    public Integer call() throws IOException {
        Set<Kind> chosen = DatasetCommand.kinds(spec, kinds);
        try (Workspace workspace = Workspace.open(common)) {
            List<DatasetEditor.Candidate> candidates = DatasetCommand.workCandidates(spec, workspace.workSnapshots(),
                    versions, chosen);
            Log.info("dataset import: %d work snapshots into %s", candidates.size(), workspace.datasetDir());
            DatasetEditor editor = DatasetEditor.open(workspace.datasetDir());
            DatasetEditor.ImportReport report = editor.importSnapshots(candidates, force);
            DatasetCommand.logImport(report);
            DatasetCommand.write(editor);
            return report.refusals().isEmpty() ? 0 : 1;
        }
    }

    /// The import step of `all`: the BOMs of `boms` and the config, extensions and classes of `configs` that the work
    /// folder has (what the run extracted, or reused from the work folder), replacing different dataset snapshots only
    /// with `force`; then the release list is refreshed from the cached `maven-metadata.xml`.
    ///
    /// @return false when a snapshot was refused as inconsistent (refusals of unvalidated extractors are expected and
    ///         only logged)
    static boolean importExtracted(Workspace workspace, List<QuarkusVersion> boms, List<QuarkusVersion> configs,
            boolean force) throws IOException {
        SnapshotStore work = workspace.workSnapshots();
        List<DatasetEditor.Candidate> candidates = new ArrayList<>();
        for (QuarkusVersion version : boms) {
            addIfPresent(candidates, work, version.text(), Kind.BOM);
        }
        for (QuarkusVersion version : configs) {
            if (work.inWork(version.text(), Kind.CONFIG.snapshotFile())
                    || work.inWork(version.text(), Kind.EXTENSIONS.snapshotFile())) {
                candidates.add(new DatasetEditor.Candidate(version.text(), Kind.CONFIG,
                        work.file(version.text(), Kind.CONFIG.snapshotFile())));
                candidates.add(new DatasetEditor.Candidate(version.text(), Kind.EXTENSIONS,
                        work.file(version.text(), Kind.EXTENSIONS.snapshotFile())));
            }
            // config writes them from the same reads; a release extracted before it did has none
            addIfPresent(candidates, work, version.text(), Kind.CLASSES);
        }
        DatasetEditor editor = DatasetEditor.open(workspace.datasetDir());
        boolean consistent = true;
        if (!candidates.isEmpty()) {
            Log.info("dataset import: %d work snapshots into %s", candidates.size(), workspace.datasetDir());
            DatasetEditor.ImportReport report = editor.importSnapshots(candidates, force);
            DatasetCommand.logImport(report);
            consistent = !report.hasInconsistencies();
        }
        try {
            List<String> published = workspace.repo().versions(BomSnapshots.GROUP_ID, BomSnapshots.BOM_ARTIFACT_ID);
            DatasetRefreshReleasesCommand.logRefresh(editor.refreshReleases(published,
                    version -> cachedBomDate(workspace.cacheDir(), version)));
        } catch (IOException e) {
            Log.info("dataset: release list not refreshed (%s)", e.getMessage());
        }
        DatasetCommand.write(editor);
        return consistent;
    }

    private static void addIfPresent(List<DatasetEditor.Candidate> candidates, SnapshotStore work, String version,
            Kind kind) {
        if (work.inWork(version, kind.snapshotFile())) {
            candidates.add(new DatasetEditor.Candidate(version, kind, work.file(version, kind.snapshotFile())));
        }
    }

    /// The UTC day of the cached `Last-Modified` of a release's BOM pom, null when the pom was never downloaded.
    static String cachedBomDate(Path cacheDir, String version) {
        Path file = cacheDir.resolve(MavenRepo.artifactPath(BomSnapshots.GROUP_ID, BomSnapshots.BOM_ARTIFACT_ID,
                version, "pom") + ".last-modified");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            Instant lastModified = Instant.parse(Files.readString(file).strip());
            return DateTimeFormatter.ISO_LOCAL_DATE.format(lastModified.atOffset(ZoneOffset.UTC));
        } catch (IOException | DateTimeParseException e) {
            Log.debug("unreadable %s: %s", file, e.getMessage());
            return null;
        }
    }
}
