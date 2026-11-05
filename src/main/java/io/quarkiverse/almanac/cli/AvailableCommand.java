package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.config.JarSource;
import io.quarkiverse.almanac.config.VersionJars;
import io.quarkiverse.almanac.maven.MavenRepo;
import io.quarkiverse.almanac.maven.QuarkusVersion;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.util.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/// `available`: fails while the repository lacks artifacts of a release. A jar that is not there yet reads the same as
/// a jar that never existed, and a release in the dataset is never read again, so the new-releases workflow runs this
/// before it reads a release: a run that finds something missing fails, and the next run reads the release again.
///
/// The repository cannot tell a late artifact from one that will never come. About a fifth of the releases have a BOM
/// that lists artifacts never published at that version, like `quarkus-vertx-http-dev-console-spi` in 3.24.0.CR1 to
/// 3.24.5, nearly all with older versions in the repository, so the listing of the artifact is there; and a new
/// artifact on its way has no listing yet. So only time tells: what is still missing [#RETRY] after the release is
/// taken as never published. It is printed, one artifact a line, and the release goes on without it.
@Command(name = "available", description = "Fail while the repository lacks artifacts of a release, so that it is "
        + "read only when it is all there: the io.quarkus jars its BOM lists, and the poms of its own artifacts with "
        + "their parents and of its Maven plugin. What is still missing 13 hours after the release is taken as never "
        + "published: it is printed, one artifact a line, with a warning, and the command succeeds. Run it after bom, "
        + "before anything else reads the release.")
public final class AvailableCommand implements Callable<Integer> {

    /// How long after a release what the repository lacks of it may still come. A little longer than the 12 hours
    /// between two runs of the new-releases workflow, so that its first run to read a release fails on anything
    /// missing, unless a run is dropped or starts hours late.
    static final Duration RETRY = Duration.ofHours(13);

    /// How many missing artifacts a log line names.
    private static final int SHOWN = 10;

    @Spec
    CommandSpec spec;

    @Mixin
    CommonOptions common;

    @Option(names = "--versions", required = true, paramLabel = "LIST",
            description = "Releases to check, e.g. 3.34.0,3.35.0.")
    String versions;

    @Option(names = "--released", paramLabel = "TIME",
            description = "When the release came out, e.g. 2026-11-04T18:40:43Z: the time of its GitHub release, which "
                    + "comes hours or days after its BOM (default: when its BOM reached the repository). Needs "
                    + "exactly one release in --versions.")
    Instant released;

    @Override
    public Integer call() throws IOException {
        if (common.offline) {
            Log.error("available: it asks the repository what it has, so it cannot run offline");
            return 1;
        }
        try (Workspace workspace = Workspace.open(common)) {
            List<QuarkusVersion> selected = Workspace.published(workspace.select(versions), "available");
            if (released != null && selected.size() != 1) {
                throw new ParameterException(spec.commandLine(),
                        "--released needs exactly one release in --versions, not " + selected.size());
            }
            ReleaseRun run = new ReleaseRun("available", workspace.repo().http(), selected.size());
            int failed = 0;
            for (QuarkusVersion version : selected) {
                if (!run.mayStart()) {
                    continue;
                }
                try {
                    if (!mayBeRead(workspace, version.text())) {
                        failed++;
                    }
                } catch (IOException e) {
                    Log.error("available %s: %s", version, e.getMessage());
                    run.failed(e);
                    failed++;
                } catch (RuntimeException e) {
                    // one broken release must not stop the others
                    Log.error("available %s: %s", version, e);
                    run.failed(e);
                    failed++;
                }
            }
            if (failed > 0) {
                Log.error("available: %d of %d releases are not all there", failed, selected.size());
            }
            return run.finish() && failed == 0 ? 0 : 1;
        }
    }

    /// Whether a release may be read: nothing is missing, or what is missing is not coming any more.
    private boolean mayBeRead(Workspace workspace, String version) throws IOException {
        BomSnapshot bom = workspace.boms().existing(version)
                .orElseThrow(() -> new IOException("no BOM snapshot (bom first)"));
        MavenRepo repo = workspace.repo();
        List<String> missing = missing(bom, repo);
        if (missing.isEmpty()) {
            Log.info("available %s: no jar or pom is missing", version);
            return true;
        }
        Instant cameOut = released != null ? released : repo.artifact("io.quarkus", "quarkus-bom", version, "pom")
                .orElseThrow(() -> new IOException("io.quarkus:quarkus-bom:" + version + " has no pom"))
                .lastModified();
        Instant now = Instant.now();
        String since = cameOut == null ? "at an unknown time" : Duration.between(cameOut, now).toMinutes()
                + " minutes ago";
        String named = String.join(", ", missing.subList(0, Math.min(SHOWN, missing.size())))
                + (missing.size() > SHOWN ? ", ... (" + missing.size() + " in all)" : "");
        if (mayStillCome(cameOut, now)) {
            Log.error("available %s: it came out %s, so what the repository lacks may still come: %s", version, since,
                    named);
            return false;
        }
        Log.warn("available %s: it came out %s, so what the repository lacks is taken as never published: %s", version,
                since, named);
        PrintWriter out = spec.commandLine().getOut();
        missing.forEach(out::println);
        out.flush();
        return true;
    }

    /// What the repository lacks of a release, asking again for what the cache marks as missing, which may have come
    /// since: `groupId:artifactId:version` and what of it, jar or pom.
    private static List<String> missing(BomSnapshot bom, MavenRepo repo) throws IOException {
        List<String> poms = new ArrayList<>();
        poms.add(VersionJars.GROUP_ID + ":quarkus-maven-plugin");
        for (ManagedDep dep : bom.deps()) {
            String[] parts = dep.id().split(":");
            if (VersionJars.isQuarkusJar(dep)) {
                repo.forgetMissing(parts[0], parts[1], dep.v(), "jar");
                repo.forgetMissing(parts[0], parts[1], dep.v(), "pom");
            }
            if (parts.length == 2 && bom.version().equals(dep.v())) {
                poms.add(dep.id());
            }
        }
        List<String> missing = new ArrayList<>();
        for (JarSource jar : VersionJars.of(bom, repo).missing()) {
            missing.add(jar.groupId() + ":" + jar.artifactId() + ":" + jar.version() + " (jar)");
        }
        for (String pom : repo.missingPoms(poms, bom.version())) {
            missing.add(pom + " (pom)");
        }
        return missing;
    }

    /// Whether what a repository lacks of a release may still come: it came out less than [#RETRY] before `now`, or at
    /// a time that is not known.
    static boolean mayStillCome(Instant cameOut, Instant now) {
        return cameOut == null || Duration.between(cameOut, now).compareTo(RETRY) < 0;
    }
}
