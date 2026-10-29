package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.api.ApiExtractor;
import io.quarkiverse.almanac.api.ApiGrammar;
import io.quarkiverse.almanac.dataset.Dataset;
import io.quarkiverse.almanac.dataset.DatasetFormat;
import io.quarkiverse.almanac.dataset.Kind;
import io.quarkiverse.almanac.maven.Http;
import io.quarkiverse.almanac.maven.MavenRepo;
import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.model.ApiSnapshot;
import io.quarkiverse.almanac.model.ArtifactApi;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ClassApi;
import io.quarkiverse.almanac.model.ClassesSnapshot;
import io.quarkiverse.almanac.model.MemberApi;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.SnapshotStore;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/// `api`: writes `api.json` snapshots, the API of the classes of a release ([ApiExtractor]), for the releases with
/// classes whose API the committed dataset does not have yet. It plans first, from the caches alone: a plan of more
/// requests than `--max-requests` is refused before anything is sent, and the run starts no further release once it
/// has sent more than that many.
@Command(name = "api", description = "Write api.json snapshots: the public and protected classes, fields, methods and "
        + "constructors of the io.quarkus jars, with their deprecation, read from the class files of the classes "
        + "snapshot (except the copies of other projects that some jars bundle, see the README), for the releases "
        + "whose API the dataset does not have yet. Each class file is read once; its bytes come from the caches when "
        + "they have them (this work folder's and those of --cache), from the repository otherwise, after a plan that "
        + "--max-requests bounds. Offline, a release is written only when every byte it needs is cached.")
public final class ApiCommand implements Callable<Integer> {

    /// The default of `--max-requests`.
    static final int DEFAULT_MAX_REQUESTS = 2000;
    /// The costliest releases a plan names.
    private static final int COSTLIEST = 10;

    @Mixin
    CommonOptions common;

    @Spec
    CommandSpec spec;

    @Option(names = "--versions", defaultValue = "all", paramLabel = "all|streams|LIST",
            description = "Releases to process (default: ${DEFAULT-VALUE}).")
    String versions;

    @Option(names = "--cache", paramLabel = "DIR",
            description = "Another HTTP cache to read jars from, such as the cache of another --work folder; may be "
                    + "repeated. It is only read: what no cache has is fetched into the cache of this work folder.")
    List<Path> caches = new ArrayList<>();

    @Option(names = "--plan", description = "Print what the extraction would read, per release and in total, and do "
            + "nothing else (no request): the class files in scope; those whose bytes are cached in the release's jar "
            + "(whole, or in .entries/; a jar cached whole is not opened); of the other jars' class files, those that "
            + "an earlier release of the run reads, those cached in another version of the jar and those to fetch; "
            + "the jars to download whole and the range requests (count, MB); and the jars whose central directory no "
            + "cache has.")
    boolean plan;

    @Option(names = "--max-requests", defaultValue = "" + DEFAULT_MAX_REQUESTS, paramLabel = "N",
            description = "Refuse a plan of more requests than this, and start no further release once the run has "
                    + "sent more than this many (default: ${DEFAULT-VALUE}).")
    int maxRequests;

    @Option(names = "--missing", description = "Print the releases of the dataset that have classes but no API data, "
            + "one a line, and do nothing else: only the dataset is read.")
    boolean missing;

    @Option(names = "--force", description = "Also process releases the dataset has, and rewrite existing snapshots.")
    boolean force;

    @Option(names = "--out", paramLabel = "DIR",
            description = "Folder of the version folders to write (default: the work snapshots folder, "
                    + "<work>/snapshots, where dataset import reads them).")
    Path out;

    @Override
    public Integer call() throws IOException {
        try (Workspace workspace = Workspace.open(common)) {
            PrintWriter output = spec.commandLine().getOut();
            if (missing) {
                missing(workspace.dataset()).forEach(output::println);
                output.flush();
                return 0;
            }
            List<QuarkusVersion> selected = toProcess(workspace, workspace.select(versions), force);
            SnapshotStore target = out != null ? new SnapshotStore(out, null) : workspace.workSnapshots();
            if (plan) {
                boolean planned = printPlan(workspace, target, caches, selected, force, output);
                output.flush();
                return planned ? 0 : 1;
            }
            return extract(workspace, target, caches, selected, force, maxRequests) ? 0 : 1;
        }
    }

    /// The releases of the dataset that have classes but no API data, in release order.
    static List<String> missing(Dataset dataset) {
        return dataset.versionsWith(Kind.CLASSES).stream().filter(version -> !dataset.has(version, Kind.API)).toList();
    }

    /// The releases among `versions` that have a classes snapshot (work folder or dataset), whose classes the API is
    /// of, and whose API the dataset does not have (with `force`, also those it has), logging how many are left out.
    static List<QuarkusVersion> toProcess(Workspace workspace, List<QuarkusVersion> versions, boolean force)
            throws IOException {
        SnapshotStore snapshots = workspace.snapshots();
        Dataset dataset = workspace.dataset();
        List<QuarkusVersion> todo = new ArrayList<>();
        List<String> withoutClasses = new ArrayList<>();
        int known = 0;
        for (QuarkusVersion version : versions) {
            String release = version.text();
            if (!force && dataset.has(release, Kind.API)) {
                known++;
            } else if (!snapshots.exists(release, SnapshotStore.CLASSES)) {
                withoutClasses.add(release);
            } else {
                todo.add(version);
            }
        }
        if (known > 0) {
            Log.info("api: %d releases already in the dataset (of %d selected; --force processes them again)", known,
                    versions.size());
        }
        if (!withoutClasses.isEmpty()) {
            Log.info("api: %d selected releases have no classes snapshot, which the API needs (%s)",
                    withoutClasses.size(), abbreviated(withoutClasses));
        }
        return todo;
    }

    /// Prints the plan of the releases to the given output: a line per release, the totals per major line and in all,
    /// and the costliest releases; nothing is sent, nothing written.
    ///
    /// @return false when a release cannot be planned (it would fail)
    static boolean printPlan(Workspace workspace, SnapshotStore target, List<Path> caches,
            List<QuarkusVersion> versions, boolean force, PrintWriter out) throws IOException {
        List<String> todo = toExtract(workspace, target, versions, force);
        ApiExtractor extractor = extractor(workspace, caches);
        out.println(String.format(Locale.ROOT, "%-16s %11s %9s %9s %9s %9s %9s | %6s %9s %6s %9s %6s | %8s %9s",
                "release", "classfiles", "known", "local", "other v.", "to fetch", "unknown", "whole", "MB", "ranges",
                "MB", "no CD", "requests", "MB"));
        List<ApiExtractor.Plan> plans = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String release : todo) {
            try {
                ApiExtractor.Plan plan = planRelease(workspace, extractor, release);
                plans.add(plan);
                out.println(planLine(plan.version(), plan));
            } catch (IOException | RuntimeException e) {
                failed.add(release);
                out.println(String.format(Locale.ROOT, "%-16s cannot be extracted: %s", release, e.getMessage()));
            }
        }
        SortedMap<Integer, ApiExtractor.Plan> lines = new TreeMap<>();
        SortedMap<Integer, Integer> releasesOfLine = new TreeMap<>();
        ApiExtractor.Plan total = ApiExtractor.Plan.empty("total");
        for (ApiExtractor.Plan plan : plans) {
            int major = DatasetFormat.majorOf(plan.version());
            lines.merge(major, plan, (a, b) -> a.plus(a.version(), b));
            releasesOfLine.merge(major, 1, Integer::sum);
            total = total.plus("total", plan);
        }
        out.println();
        lines.forEach((major, plan) -> out.println(planLine(major + ".x (" + releasesOfLine.get(major) + ")", plan)));
        out.println(planLine("total (" + plans.size() + ")", total));
        List<ApiExtractor.Plan> costliest = plans.stream().filter(plan -> plan.requests() > 0)
                .sorted(Comparator.comparingInt(ApiExtractor.Plan::requests).reversed()
                        .thenComparing(Comparator.comparingLong(ApiExtractor.Plan::bytes).reversed()))
                .limit(COSTLIEST).toList();
        out.println();
        out.println(String.format(Locale.ROOT, "%d of %d releases need requests%s", plans.stream()
                .filter(plan -> plan.requests() > 0).count(), plans.size(), costliest.isEmpty() ? "" : "; the "
                + "costliest: " + costliest.stream().map(plan -> String.format(Locale.ROOT, "%s (%d, %.1f MB)",
                        plan.version(), plan.requests(), plan.bytes() / 1e6)).collect(Collectors.joining(", "))));
        int reused = versions.size() - todo.size();
        if (reused > 0) {
            out.println(reused + " releases have an api.json in " + target.root() + " already, which is reused "
                    + "(--force reads them again)");
        }
        if (total.uncachedIndexes() > 0) {
            out.println(total.uncachedIndexes() + " jars have a central directory that no cache has: each counts two "
                    + "requests (its end, then its classes), and their class files are unknown");
        }
        if (!failed.isEmpty()) {
            out.println(failed.size() + " releases cannot be extracted: " + String.join(", ", failed));
        }
        return failed.isEmpty();
    }

    /// Writes the snapshots of the given releases one after the other, until requests stop ([ReleaseRun]) or the run
    /// has sent more than `maxRequests`; false when any release got no snapshot (offline: one whose bytes are not all
    /// cached), or was not attempted. Online, the releases are planned first, and a plan of more than `maxRequests`
    /// requests is refused before anything is sent. A snapshot in `target` is reused unless `force`, or it is older
    /// than the release's `classes.json` in the work folder.
    ///
    /// @param caches other cache folders, read before the repository is asked for what they lack
    static boolean extract(Workspace workspace, SnapshotStore target, List<Path> caches, List<QuarkusVersion> versions,
            boolean force, int maxRequests) {
        if (versions.isEmpty()) {
            return true;
        }
        List<String> todo = toExtract(workspace, target, versions, force);
        int reused = versions.size() - todo.size();
        if (reused > 0) {
            Log.info("api: reusing the existing snapshots of %d releases in %s", reused, target.root());
        }
        if (todo.isEmpty()) {
            return true;
        }
        Http http = workspace.repo().http();
        ApiExtractor extractor = extractor(workspace, caches);
        Log.info("api: %d releases, from the class files of their jars%s, into %s", todo.size(),
                caches.isEmpty() ? "" : " (cached here or in " + caches.stream().map(Path::toString)
                        .collect(Collectors.joining(", ")) + ")", target.root());
        if (!http.offline()) {
            ApiExtractor.Plan total = ApiExtractor.Plan.empty("total");
            for (String release : todo) {
                try {
                    total = total.plus("total", planRelease(workspace, extractor, release));
                } catch (IOException | RuntimeException e) {
                    // the extraction names it again
                    Log.debug("api %s: cannot be planned: %s", release, e.getMessage());
                }
            }
            if (total.requests() > maxRequests) {
                Log.error("api: the plan needs %d requests (%.1f MB), more than --max-requests %d, so none is sent "
                        + "(api --plan lists them)", total.requests(), total.bytes() / 1e6, maxRequests);
                return false;
            }
            if (total.requests() > 0) {
                Log.info("api: the plan needs %d requests (%.1f MB)", total.requests(), total.bytes() / 1e6);
            }
        }
        ReleaseRun run = new ReleaseRun("api", http, todo.size());
        long first = http.requestCount();
        List<String> notLocal = new ArrayList<>();
        int overLimit = 0;
        int failed = 0;
        for (String release : todo) {
            if (!run.mayStart()) {
                continue;
            }
            if (http.requestCount() - first > maxRequests) {
                if (overLimit++ == 0) {
                    Log.error("api: %d requests sent, more than --max-requests allows; no further release is started",
                            http.requestCount() - first);
                }
                continue;
            }
            try {
                if (!writeOne(workspace, extractor, target, release)) {
                    notLocal.add(release);
                }
            } catch (IOException e) {
                Log.error("api %s: %s", release, e.getMessage());
                run.failed(e);
                failed++;
            } catch (RuntimeException e) {
                // one broken release must not stop the others
                Log.error("api %s: %s", release, e);
                run.failed(e);
                failed++;
            }
        }
        if (!notLocal.isEmpty()) {
            Log.warn("api: %d releases need bytes that no cache has, so offline they got no snapshot (%s)",
                    notLocal.size(), abbreviated(notLocal));
        }
        if (overLimit > 0) {
            Log.error("api: %d of %d releases not attempted, since the run went past --max-requests %d", overLimit,
                    todo.size(), maxRequests);
        }
        if (failed > 0) {
            Log.error("api: %d of %d releases failed", failed, todo.size());
        }
        return run.finish() && failed == 0 && notLocal.isEmpty() && overLimit == 0;
    }

    /// Writes the snapshot of one release; false, writing nothing, when offline and some bytes are in no cache.
    private static boolean writeOne(Workspace workspace, ApiExtractor extractor, SnapshotStore target,
            String release) throws IOException {
        long start = System.nanoTime();
        Http http = workspace.repo().http();
        long requests = http.requestCount();
        ApiExtractor.Extraction extraction = extractor.extract(release, classes(workspace, release),
                bom(workspace, release), http.offline());
        if (extraction.snapshot() == null) {
            Log.warn("api %s: %d jars need the repository (%s); offline, no snapshot is written", release,
                    extraction.notLocal().size(), abbreviated(extraction.notLocal()));
            return false;
        }
        Optional<String> stopped = http.stopReason();
        if (stopped.isPresent()) {
            throw new Http.StoppedException("requests stopped after " + stopped.get() + ", so no snapshot of "
                    + release + " is written");
        }
        ApiSnapshot snapshot = extraction.snapshot();
        if (snapshot.artifacts().isEmpty()) {
            throw new IOException("no class of the release is API; no snapshot is written");
        }
        target.write(release, SnapshotStore.API, snapshot);
        int classes = 0;
        int members = 0;
        int deprecatedClasses = 0;
        int deprecatedMembers = 0;
        for (ArtifactApi artifact : snapshot.artifacts()) {
            for (ClassApi type : artifact.classes()) {
                classes++;
                members += type.members().size();
                deprecatedClasses += ApiGrammar.isDeprecated(type.v()) ? 1 : 0;
                for (MemberApi member : type.members()) {
                    deprecatedMembers += ApiGrammar.isDeprecated(member.v()) ? 1 : 0;
                }
            }
        }
        Log.info("api %s: %,d API classes (%,d deprecated) with %,d members (%,d deprecated) in %d jars; %,d of %,d "
                        + "class files read, %,d fetched with %d requests; %.1f s", release, classes, deprecatedClasses,
                members, deprecatedMembers, snapshot.artifacts().size(), extraction.read(), extraction.classFiles(),
                extraction.fetched(), http.requestCount() - requests, (System.nanoTime() - start) / 1e9);
        return true;
    }

    /// The plan of one release, after those planned before it by the same extractor.
    private static ApiExtractor.Plan planRelease(Workspace workspace, ApiExtractor extractor, String release)
            throws IOException {
        return extractor.plan(release, classes(workspace, release), bom(workspace, release));
    }

    private static ClassesSnapshot classes(Workspace workspace, String release) throws IOException {
        return workspace.snapshots().read(release, SnapshotStore.CLASSES, ClassesSnapshot.class);
    }

    private static BomSnapshot bom(Workspace workspace, String release) throws IOException {
        return workspace.boms().existing(release).orElseThrow(() -> new IOException("no BOM snapshot of " + release
                + ", whose jar versions the classes need"));
    }

    /// The releases whose snapshot is to be written: all with `force`, else those without one in `target` and those
    /// whose one there is older than their `classes.json` in the work folder, logging how many are read again for that
    /// reason. Such an `api.json` was read from other classes: when an import replaces the classes of a release, it
    /// removes the API of the release, which this reads again from the new classes instead of reusing it.
    private static List<String> toExtract(Workspace workspace, SnapshotStore target, List<QuarkusVersion> versions,
            boolean force) {
        SnapshotStore work = workspace.workSnapshots();
        List<String> todo = new ArrayList<>();
        int stale = 0;
        for (QuarkusVersion version : versions) {
            String release = version.text();
            if (force || !target.inWork(release, SnapshotStore.API)) {
                todo.add(release);
            } else if (olderThan(target.file(release, SnapshotStore.API), work.file(release, SnapshotStore.CLASSES))) {
                todo.add(release);
                stale++;
            }
        }
        if (stale > 0) {
            Log.info("api: %d releases are read again, since their api.json in %s is older than their classes.json",
                    stale, target.root());
        }
        return todo;
    }

    /// Whether a file was last modified before another one that exists; true when that cannot be told.
    private static boolean olderThan(Path file, Path other) {
        if (!Files.isRegularFile(other)) {
            return false;
        }
        try {
            return Files.getLastModifiedTime(file).compareTo(Files.getLastModifiedTime(other)) < 0;
        } catch (IOException e) {
            return true;
        }
    }

    private static ApiExtractor extractor(Workspace workspace, List<Path> caches) {
        MavenRepo repo = workspace.repo();
        return new ApiExtractor(repo, caches.stream().map(repo::withCache).toList(), workspace.concurrency());
    }

    private static String planLine(String name, ApiExtractor.Plan plan) {
        return String.format(Locale.ROOT, "%-16s %,11d %,9d %,9d %,9d %,9d %,9d | %,6d %9.2f %,6d %9.2f %,6d | %,8d "
                + "%9.2f", name, plan.classFiles(), plan.known(), plan.local(), plan.elsewhere(), plan.toFetch(),
                plan.unknown(), plan.wholeJars(), plan.wholeBytes() / 1e6, plan.rangedReads(), plan.rangedBytes() / 1e6,
                plan.uncachedIndexes(), plan.requests(), plan.bytes() / 1e6);
    }

    /// The first five, then `...` when there are more.
    private static String abbreviated(List<String> names) {
        return String.join(", ", names.subList(0, Math.min(5, names.size()))) + (names.size() > 5 ? ", ..." : "");
    }
}
