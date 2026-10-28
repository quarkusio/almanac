package io.quarkiverse.almanac.util;

import io.quarkiverse.almanac.dataset.Dataset;
import io.quarkiverse.almanac.dataset.DatasetFormat;
import io.quarkiverse.almanac.dataset.Kind;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Stream;

/// The per-version snapshot folder layout (`work/snapshots/<version>/*.json`), backed by the committed
/// dataset.
///
/// Writes always go to the work folder, pretty-printed and atomically. Reads look in the work folder first; a
/// `bom.json`, `config.json`, `extensions.json`, `classes.json` or `platform-bom.json` it lacks is restored from the
/// dataset (the dataset is opened on first use and stays in memory; each snapshot is restored on demand). So code that
/// reads snapshots through a store, such as `bom`, `config` and `verify official`, works on a fresh clone that has no
/// work folder. [#file] is the work path only: code that opens snapshot files itself sees the work folder alone.
public final class SnapshotStore {

    public static final String BOM = "bom.json";
    public static final String CONFIG = "config.json";
    public static final String CONFIG_MODEL = "config-model.json";
    public static final String CONFIG_BYTECODE = "config-bytecode.json";
    public static final String EXTENSIONS = "extensions.json";
    public static final String CLASSES = "classes.json";
    public static final String PLATFORM_BOM = "platform-bom.json";

    private final Path root;
    private final Path datasetDir;
    private volatile Dataset dataset;

    /// A store over the default layout: when `root` is `<project>/work/snapshots` (a work folder named `work`, as
    /// with the default `--work work`), reads fall back to `<project>/dataset`; for any other folder there is no
    /// fallback (so tests on scratch folders never see the project's dataset).
    ///
    /// @param root the snapshots folder, usually `work/snapshots`
    public SnapshotStore(Path root) {
        this(root, siblingDataset(root));
    }

    /// @param root       the snapshots folder, usually `work/snapshots`
    /// @param datasetDir the committed dataset that reads fall back to (`--dataset`); null for none
    public SnapshotStore(Path root, Path datasetDir) {
        this.root = root;
        this.datasetDir = datasetDir;
    }

    /// The snapshots folder.
    public Path root() {
        return root;
    }

    /// The dataset folder that reads fall back to, if any.
    public Optional<Path> datasetDir() {
        return Optional.ofNullable(datasetDir);
    }

    /// Path of a snapshot file in the work folder, e.g. `file("3.20.6", CONFIG)`.
    public Path file(String version, String name) {
        return root.resolve(version).resolve(name);
    }

    /// Whether the snapshot is in the work folder or, for the kinds the dataset keeps, in the dataset.
    ///
    /// @throws UncheckedIOException when the dataset cannot be read
    public boolean exists(String version, String name) {
        return inWork(version, name) || inDataset(version, name);
    }

    /// Whether the snapshot file is in the work folder.
    public boolean inWork(String version, String name) {
        return Files.isRegularFile(file(version, name));
    }

    /// Whether the dataset holds the snapshot (`bom.json`, `config.json`, `extensions.json`, `classes.json` or
    /// `platform-bom.json`).
    ///
    /// @throws UncheckedIOException when the dataset cannot be read
    public boolean inDataset(String version, String name) {
        Optional<Kind> kind = Kind.ofSnapshotFile(name);
        if (kind.isEmpty() || datasetDir == null) {
            return false;
        }
        try {
            return dataset().has(version, kind.get());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Reads a snapshot from the work folder, or restores it from the dataset when the work folder lacks it.
    ///
    /// @throws NoSuchFileException when neither has it
    public <T> T read(String version, String name, Class<T> type) throws IOException {
        Path file = file(version, name);
        if (Files.isRegularFile(file)) {
            return Json.read(file, type);
        }
        Optional<Kind> kind = Kind.ofSnapshotFile(name);
        if (kind.isPresent() && datasetDir != null && dataset().has(version, kind.get())) {
            Object snapshot = dataset().snapshot(version, kind.get());
            return type.isInstance(snapshot) ? type.cast(snapshot) : Json.mapper().convertValue(snapshot, type);
        }
        throw new NoSuchFileException(file.toString());
    }

    /// Writes a snapshot into the work folder (never into the dataset).
    public void write(String version, String name, Object snapshot) throws IOException {
        Json.writePretty(file(version, name), snapshot);
    }

    /// Names of the versions that have the snapshot, in the work folder or the dataset (unordered).
    public List<String> versionsWith(String name) throws IOException {
        TreeSet<String> versions = new TreeSet<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> dirs = Files.list(root)) {
                dirs.filter(dir -> Files.isRegularFile(dir.resolve(name)))
                        .map(dir -> dir.getFileName().toString())
                        .forEach(versions::add);
            }
        }
        Optional<Kind> kind = Kind.ofSnapshotFile(name);
        if (kind.isPresent() && datasetDir != null) {
            versions.addAll(dataset().versionsWith(kind.get()));
        }
        return List.copyOf(versions);
    }

    /// The releases the dataset knows (`releases.jsonl`, all versions of the BOM when it was last refreshed), in
    /// release order; empty without a dataset.
    public List<String> knownReleases() throws IOException {
        if (datasetDir == null) {
            return List.of();
        }
        return dataset().releases().stream().map(Dataset.Release::version).toList();
    }

    /// The dataset reads fall back to, opened on first use (an empty one when there is no dataset folder).
    public Dataset dataset() throws IOException {
        Dataset result = dataset;
        if (result == null) {
            synchronized (this) {
                result = dataset;
                if (result == null) {
                    result = datasetDir == null ? Dataset.empty() : Dataset.open(datasetDir);
                    dataset = result;
                }
            }
        }
        return result;
    }

    /// `<project>/dataset` when `root` is `<project>/work/<anything>`, else null.
    private static Path siblingDataset(Path root) {
        Path work = root.toAbsolutePath().normalize().getParent();
        if (work == null || work.getFileName() == null || !work.getFileName().toString().equals("work")) {
            return null;
        }
        return work.resolveSibling(DatasetFormat.DEFAULT_DIR);
    }
}
