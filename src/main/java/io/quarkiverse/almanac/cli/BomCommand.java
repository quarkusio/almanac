package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.dataset.Dataset;
import io.quarkiverse.almanac.dataset.Kind;
import io.quarkiverse.almanac.maven.BomResolver;
import io.quarkiverse.almanac.maven.BomSnapshots;
import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.Parallel;
import io.quarkiverse.almanac.util.SnapshotStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/// `bom`: writes `bom.json` snapshots of the releases the committed dataset does not have yet.
@Command(name = "bom", description = "Write bom.json snapshots (managed dependency versions) of the releases the "
        + "dataset does not have yet. A BOM is resolved as Maven builds it: parents, properties and imported BOMs "
        + "(read from the repository). A managed version, type or classifier with a property reference that Maven "
        + "cannot resolve either is kept as written, with a warning. A release fails where Maven fails (a parent or "
        + "imported BOM that cannot be read or has no valid coordinates, a managed dependency without a valid groupId "
        + "and artifactId, a cycle, also one that profiles depending on the machine form), where Maven may resolve a "
        + "reference from the machine that builds, from the project it builds (its packaging) or from parts of the pom "
        + "that are not read, where a profile that depends on the machine (JDK, OS, file, or a property of the JVM, "
        + "the launcher or the build such as java.vendor, native.encoding or packaging) manages dependencies or sets "
        + "a property that is read, or drops a profile active by default that does, and where a property that is read "
        + "refers to itself with more text (Maven's value then depends on the order of the property names).")
public final class BomCommand implements Callable<Integer> {

    @Mixin
    CommonOptions common;

    @Spec
    CommandSpec spec;

    @Option(names = "--versions", defaultValue = "all", paramLabel = "all|streams|LIST",
            description = "Releases to process (default: ${DEFAULT-VALUE}).")
    String versions;

    @Option(names = "--force", description = "Also process releases the dataset has, and rewrite existing snapshots.")
    boolean force;

    @Option(names = "--out", paramLabel = "DIR",
            description = "Folder of the version folders to write the snapshots to, instead of the work snapshots "
                    + "folder; only snapshots there are reused. Releases the dataset has are still skipped unless "
                    + "--force.")
    Path out;

    @Option(names = "--compare-pom", paramLabel = "FILE",
            description = "Resolve this pom instead, for example the BOM at the release's tag in the Quarkus "
                    + "repository, which is not flattened (its parents and imported BOMs come from the repository), "
                    + "and compare its managed dependencies with the existing snapshot of the one release given with "
                    + "--versions. Writes nothing; exit code 1 when they differ.")
    Path comparePom;

    @Override
    public Integer call() throws IOException {
        try (Workspace workspace = Workspace.open(common)) {
            List<QuarkusVersion> selected = workspace.select(versions);
            BomSnapshots boms = out == null
                    ? workspace.boms()
                    : new BomSnapshots(workspace.repo(), new SnapshotStore(out, null));
            if (comparePom != null) {
                if (selected.size() != 1) {
                    throw new CommandLine.ParameterException(spec.commandLine(),
                            "--compare-pom needs exactly one release in --versions, not " + selected.size());
                }
                return compare(boms, selected.getFirst().text(), comparePom, spec.commandLine().getOut()) ? 0 : 1;
            }
            return writeSnapshots(workspace, boms, notInDataset(workspace, selected, force), force) ? 0 : 1;
        }
    }

    /// The releases whose BOM the dataset does not have (all of them with `force`), logging how many it has: published
    /// releases never change, so a release in the dataset is never downloaded again.
    static List<QuarkusVersion> notInDataset(Workspace workspace, List<QuarkusVersion> versions, boolean force)
            throws IOException {
        if (force) {
            return versions;
        }
        Dataset dataset = workspace.dataset();
        List<QuarkusVersion> missing = versions.stream()
                .filter(version -> !dataset.has(version.text(), Kind.BOM))
                .toList();
        int known = versions.size() - missing.size();
        if (known > 0) {
            Log.info("bom: %d releases already in the dataset (of %d selected; --force processes them again)", known,
                    versions.size());
        }
        return missing;
    }

    /// Writes the BOM snapshots of the given releases into the work folder; false when any release failed or was not
    /// attempted.
    static boolean writeSnapshots(Workspace workspace, List<QuarkusVersion> versions, boolean force)
            throws IOException {
        return writeSnapshots(workspace, workspace.boms(), versions, force);
    }

    /// Writes the snapshots of `boms` (of the BOM or of the platform BOM) of the given releases in parallel, until
    /// requests stop ([ReleaseRun]); false when any release failed or was not attempted.
    static boolean writeSnapshots(Workspace workspace, BomSnapshots boms, List<QuarkusVersion> versions, boolean force)
            throws IOException {
        if (versions.isEmpty()) {
            return true;
        }
        String label = boms.label();
        Log.info("%s: %d releases", label, versions.size());
        ReleaseRun run = new ReleaseRun(label, workspace.repo().http(), versions.size());
        AtomicInteger failed = new AtomicInteger();
        Parallel.<QuarkusVersion, Void>map(versions, workspace.concurrency(), version -> {
            if (run.mayStart()) {
                try {
                    boms.load(version.text(), force);
                } catch (IOException e) {
                    Log.error("%s %s: %s", label, version, e.getMessage());
                    run.failed(e);
                    failed.incrementAndGet();
                } catch (RuntimeException e) {
                    // one broken release must not stop the others
                    Log.error("%s %s: %s", label, version, e);
                    run.failed(e);
                    failed.incrementAndGet();
                }
            }
            return null;
        });
        if (failed.get() > 0) {
            Log.error("%s: %d of %d releases failed", label, failed.get(), versions.size());
        }
        return run.finish() && failed.get() == 0;
    }

    /// Prints how the managed dependencies resolved from `pomFile` differ from the release's snapshot, each difference
    /// with the pom that declares the entry; true when they are equal. References left unresolved are compared as
    /// written.
    static boolean compare(BomSnapshots boms, String version, Path pomFile, PrintWriter out) throws IOException {
        BomSnapshot snapshot = boms.existing(version)
                .orElseThrow(() -> new IOException("no BOM snapshot of " + version + " to compare with"));
        BomResolver.Result result = boms.resolve(version, Files.readAllBytes(pomFile));
        Map<String, String> stored = new TreeMap<>();
        snapshot.deps().forEach(dep -> stored.put(dep.id(), dep.v()));
        Map<String, BomResolver.Entry> resolved = new TreeMap<>();
        result.entries().forEach(entry -> resolved.put(entry.id(), entry));
        TreeSet<String> ids = new TreeSet<>(stored.keySet());
        ids.addAll(resolved.keySet());
        List<String> differences = new ArrayList<>();
        for (String id : ids) {
            String expected = stored.get(id);
            BomResolver.Entry actual = resolved.get(id);
            if (actual == null) {
                differences.add("only in the snapshot: " + id + " " + expected);
            } else if (expected == null) {
                differences.add("only in the pom: " + id + " " + actual.version() + describe(actual));
            } else if (!expected.equals(actual.version())) {
                differences.add("other version: " + id + " " + actual.version() + describe(actual) + ", snapshot "
                        + expected);
            }
        }
        out.printf("%s: %d managed dependencies resolved from %s (poms read: %d, parents: %d, imported BOMs: %d, "
                        + "entries imported: %d), %d in the snapshot: %s%n", version, result.entries().size(), pomFile,
                result.poms(), result.parents().size(), result.imports().size(), result.imported(),
                snapshot.deps().size(), differences.isEmpty() ? "equal" : "differences: " + differences.size());
        differences.forEach(line -> out.println("  " + line));
        result.profiles().forEach(profile -> out.println("  profile applied: " + profile));
        for (BomResolver.Entry entry : result.unresolved()) {
            out.println("  unresolved in the pom: " + entry.id() + " " + entry.version() + describe(entry));
        }
        for (ManagedDep dep : snapshot.deps()) {
            if (BomSnapshots.unresolved(dep)) {
                out.println("  unresolved in the snapshot: " + dep.id() + " " + dep.v());
            }
        }
        out.flush();
        return differences.isEmpty();
    }

    private static String describe(BomResolver.Entry entry) {
        return " (declared in " + entry.origin() + (entry.via() == null ? "" : ", imported through " + entry.via())
                + ")";
    }
}
