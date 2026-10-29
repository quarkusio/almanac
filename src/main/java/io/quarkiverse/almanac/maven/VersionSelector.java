package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.BinaryOperator;

/// Chooses the Quarkus releases to process.
///
/// - `all`: every final release of the streams from `since` on, plus the pre-releases that [PreReleases] asks for;
/// - `streams`: the newest final of each `major.minor` stream from `since` on, plus the pre-releases that
///   [PreReleases] asks for;
/// - an explicit comma-separated list of published versions, taken as given, which may also name the versions given
///   to select by name only.
///
/// `since` compares by stream (`major.minor`), so `--since 3.0` keeps `3.0.0.Alpha1` and `--since 4.0` keeps
/// `4.0.0.Beta1`; a longer `since` such as `3.8.6` counts as its stream.
public final class VersionSelector {

    /// Which pre-releases (Alpha, Beta and CR releases) the `all` and `streams` selections add to the finals.
    public enum PreReleases {
        /// No pre-releases: finals only.
        NONE,
        /// The newest pre-release when it is newer than the newest final: the upcoming line, e.g. `4.0.0.Beta1`.
        UPCOMING,
        /// Every pre-release for `all`; for `streams`, the newest pre-release of each stream, so each stream gets
        /// one pre-release next to its newest final.
        ALL;

        /// Parses a mode name in any case: `none`, `upcoming` or `all`.
        ///
        /// @throws IllegalArgumentException for any other name
        public static PreReleases parse(String name) {
            for (PreReleases mode : values()) {
                if (mode.name().equalsIgnoreCase(name.strip())) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("expected none, upcoming or all, not '" + name + "'");
        }
    }

    /// LTS streams, as flagged `"lts": true` by `https://registry.quarkus.io/client/platforms/all`; update when Quarkus
    /// announces new ones.
    public static final List<String> LTS_STREAMS = List.of("3.2", "3.8", "3.15", "3.20", "3.27", "3.33", "3.40");

    /// Default lower bound of the selections.
    public static final String DEFAULT_SINCE = "3.0";

    private final List<QuarkusVersion> available;
    private final List<QuarkusVersion> byNameOnly;

    /// @param published every published version (e.g. from maven-metadata.xml); unparsable ones are ignored
    public VersionSelector(Collection<String> published) {
        this(published, List.of());
    }

    /// @param published  every published version (e.g. from maven-metadata.xml); unparsable ones are ignored
    /// @param byNameOnly versions that only an explicit list selects, such as the releases never published whose BOM
    ///                   comes from their git tag; those also in `published` are selected as any other
    public VersionSelector(Collection<String> published, Collection<String> byNameOnly) {
        this.available = parse(published);
        List<String> availableTexts = available.stream().map(QuarkusVersion::text).toList();
        this.byNameOnly = parse(byNameOnly.stream().filter(text -> !availableTexts.contains(text.strip())).toList());
    }

    private static List<QuarkusVersion> parse(Collection<String> texts) {
        TreeSet<String> seen = new TreeSet<>();
        List<QuarkusVersion> versions = new ArrayList<>();
        for (String text : texts) {
            Optional<QuarkusVersion> version = QuarkusVersion.tryParse(text);
            if (version.isEmpty()) {
                Log.debug("ignoring unparsable version %s", text);
            } else if (seen.add(version.get().text())) {
                versions.add(version.get());
            }
        }
        versions.sort(Comparator.naturalOrder());
        return List.copyOf(versions);
    }

    public static boolean isLts(QuarkusVersion version) {
        return LTS_STREAMS.contains(version.stream());
    }

    /// Resolves a selection with the default [PreReleases#UPCOMING]; see
    /// [#select(String, QuarkusVersion, PreReleases)].
    ///
    /// @throws IllegalArgumentException for an unknown version in an explicit list
    public List<QuarkusVersion> select(String selection, QuarkusVersion since) {
        return select(selection, since, PreReleases.UPCOMING);
    }

    /// Resolves a selection: `all`, `streams` or a comma-separated list of versions. The result is ascending.
    ///
    /// @param since       the oldest stream of `all` and `streams`
    /// @param preReleases the pre-releases that `all` and `streams` add; an explicit list is taken as given
    /// @throws IllegalArgumentException for an unknown version in an explicit list
    public List<QuarkusVersion> select(String selection, QuarkusVersion since, PreReleases preReleases) {
        return switch (selection.strip()) {
            case "all" -> all(since, preReleases);
            case "streams" -> streams(since, preReleases);
            default -> explicit(List.of(selection.split(",")));
        };
    }

    /// `all` with the default [PreReleases#UPCOMING].
    public List<QuarkusVersion> all(QuarkusVersion since) {
        return all(since, PreReleases.UPCOMING);
    }

    /// Every final release of the streams from `since` on, plus the pre-releases that `preReleases` asks for,
    /// ascending.
    public List<QuarkusVersion> all(QuarkusVersion since, PreReleases preReleases) {
        List<QuarkusVersion> selected = new ArrayList<>(finalsSince(since));
        Collection<QuarkusVersion> pre = switch (preReleases) {
            case NONE -> List.of();
            case UPCOMING -> upcoming(since).stream().toList();
            case ALL -> preReleasesSince(since);
        };
        selected.addAll(pre);
        selected.sort(Comparator.naturalOrder());
        return List.copyOf(selected);
    }

    /// `streams` with the default [PreReleases#UPCOMING].
    public List<QuarkusVersion> streams(QuarkusVersion since) {
        return streams(since, PreReleases.UPCOMING);
    }

    /// The newest final of each stream from `since` on, plus the pre-releases that `preReleases` asks for (with
    /// [PreReleases#ALL], the newest pre-release of each stream), ascending.
    public List<QuarkusVersion> streams(QuarkusVersion since, PreReleases preReleases) {
        List<QuarkusVersion> selected = new ArrayList<>(newestPerStream(finalsSince(since)));
        Collection<QuarkusVersion> pre = switch (preReleases) {
            case NONE -> List.of();
            case UPCOMING -> upcoming(since).stream().toList();
            case ALL -> newestPerStream(preReleasesSince(since));
        };
        selected.addAll(pre);
        selected.sort(Comparator.naturalOrder());
        return List.copyOf(selected);
    }

    /// Resolves explicitly named versions against the published ones, then those selected by name only: an exact
    /// match first, otherwise a version that compares equal (so `3.0.4` finds `3.0.4.Final`). Returns them ascending
    /// without duplicates.
    public List<QuarkusVersion> explicit(List<String> requested) {
        TreeSet<QuarkusVersion> selected = new TreeSet<>(Comparator.naturalOrder());
        for (String text : requested) {
            String wanted = text.strip();
            if (wanted.isEmpty()) {
                continue;
            }
            QuarkusVersion parsed = QuarkusVersion.parse(wanted);
            QuarkusVersion match = find(available, wanted, parsed)
                    .or(() -> find(byNameOnly, wanted, parsed))
                    .orElseThrow(() -> new IllegalArgumentException("unknown Quarkus version: " + wanted));
            selected.add(match);
        }
        return List.copyOf(selected);
    }

    private static Optional<QuarkusVersion> find(List<QuarkusVersion> versions, String wanted, QuarkusVersion parsed) {
        return versions.stream().filter(v -> v.text().equals(wanted)).findFirst()
                .or(() -> versions.stream().filter(v -> v.compareTo(parsed) == 0).findFirst());
    }

    private List<QuarkusVersion> finalsSince(QuarkusVersion since) {
        return available.stream().filter(QuarkusVersion::isFinal).filter(v -> inStreamFrom(v, since)).toList();
    }

    private List<QuarkusVersion> preReleasesSince(QuarkusVersion since) {
        return available.stream().filter(v -> !v.isFinal()).filter(v -> inStreamFrom(v, since)).toList();
    }

    /// The newest pre-release, when it is in a stream from `since` on and newer than the newest final.
    private Optional<QuarkusVersion> upcoming(QuarkusVersion since) {
        Optional<QuarkusVersion> newestFinal = available.stream().filter(QuarkusVersion::isFinal)
                .max(Comparator.naturalOrder());
        return available.stream()
                .filter(v -> !v.isFinal())
                .max(Comparator.naturalOrder())
                .filter(pre -> inStreamFrom(pre, since))
                .filter(pre -> newestFinal.isEmpty() || pre.compareTo(newestFinal.get()) > 0);
    }

    /// The newest of the given versions in each stream.
    private static Collection<QuarkusVersion> newestPerStream(List<QuarkusVersion> versions) {
        Map<String, QuarkusVersion> newest = new LinkedHashMap<>();
        BinaryOperator<QuarkusVersion> newer = (a, b) -> a.compareTo(b) >= 0 ? a : b;
        for (QuarkusVersion version : versions) {
            newest.merge(version.stream(), version, newer);
        }
        return newest.values();
    }

    /// Whether `version` is in the stream of `since` or a later one; only `major.minor` count.
    private static boolean inStreamFrom(QuarkusVersion version, QuarkusVersion since) {
        int c = Integer.compare(version.major(), since.major());
        return (c != 0 ? c : Integer.compare(version.minor(), since.minor())) >= 0;
    }
}
