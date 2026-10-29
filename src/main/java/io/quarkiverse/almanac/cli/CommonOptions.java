package io.quarkiverse.almanac.cli;

import io.quarkiverse.almanac.maven.Http;
import io.quarkiverse.almanac.maven.MavenRepo;
import io.quarkiverse.almanac.maven.Pacer;
import io.quarkiverse.almanac.maven.VersionSelector;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;
import picocli.CommandLine.TypeConversionException;

import java.net.URI;
import java.nio.file.Path;

/// Options shared by every command.
public final class CommonOptions {

    // help only: the top command's --version is not inherited, so that it cannot stand for a mistyped --versions
    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    boolean help;

    @Option(names = "--work", defaultValue = "work", paramLabel = "DIR",
            description = "Working folder for the HTTP cache and the snapshots (default: ${DEFAULT-VALUE}).")
    Path work;

    @Option(names = "--dataset", defaultValue = "dataset", paramLabel = "DIR",
            description = "The committed dataset of extracted releases; snapshots it has are never extracted again "
                    + "(default: ${DEFAULT-VALUE}).")
    Path dataset;

    @Option(names = "--repo", paramLabel = "URL", description = "Maven repository (default: Maven Central).")
    URI repo = MavenRepo.CENTRAL;

    @Option(names = "--concurrency", defaultValue = "8", paramLabel = "N",
            description = "Maximum parallel HTTP requests, and jars read at the same time (default: ${DEFAULT-VALUE}).")
    int concurrency;

    @Option(names = "--pause", defaultValue = "0.5-1.5", paramLabel = "SECONDS", converter = PauseConverter.class,
            description = "Random pause between two requests to the repository, in seconds: a range such as 2-5, a "
                    + "fixed 3, or 0 for none. With a pause, requests go one at a time, also across commands that "
                    + "share --work. Maven Central needs a pause, at least 0.001, so its requests go one at a time; 0 "
                    + "is only for other repositories (default: ${DEFAULT-VALUE}).")
    Pacer.Pause pause;

    @Option(names = "--offline", description = "Use cached files only; fail when something is not cached.")
    boolean offline;

    @Option(names = "--since", defaultValue = "0.0", paramLabel = "STREAM",
            description = "Oldest stream (major.minor) of the all and streams selections, its pre-releases included; "
                    + "a longer version counts as its stream (default: ${DEFAULT-VALUE}, every release).")
    String since;

    @Option(names = "--pre-releases", defaultValue = "upcoming", paramLabel = "none|upcoming|all",
            converter = PreReleasesConverter.class,
            description = "Pre-releases (Alpha, Beta, CR) in the all and streams selections: none; upcoming = the "
                    + "newest one when it is newer than the newest final; all = every one, for streams the newest "
                    + "of each stream (default: ${DEFAULT-VALUE}).")
    VersionSelector.PreReleases preReleases;

    @Option(names = "--verbose", description = "Log every HTTP request and other details.")
    boolean verbose;

    /// The pacer of the commands on the cache folder `cacheDir`, for `--pause`.
    ///
    /// @throws IllegalArgumentException when `--pause` is too short for `--repo` ([Http#checkPause])
    Pacer pacer(Path cacheDir) {
        Http.checkPause(pause, repo);
        return Pacer.forCache(pause, cacheDir);
    }

    /// Reads `--pause`.
    public static final class PauseConverter implements ITypeConverter<Pacer.Pause> {

        @Override
        public Pacer.Pause convert(String value) {
            try {
                return Pacer.Pause.parse(value);
            } catch (IllegalArgumentException e) {
                throw new TypeConversionException(e.getMessage());
            }
        }
    }

    /// Reads `--pre-releases` in any case, whether or not the command line accepts enum values in any case.
    public static final class PreReleasesConverter implements ITypeConverter<VersionSelector.PreReleases> {

        @Override
        public VersionSelector.PreReleases convert(String value) {
            try {
                return VersionSelector.PreReleases.parse(value);
            } catch (IllegalArgumentException e) {
                throw new TypeConversionException(e.getMessage());
            }
        }
    }
}
