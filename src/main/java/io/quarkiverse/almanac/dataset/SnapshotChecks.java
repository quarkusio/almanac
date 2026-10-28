package io.quarkiverse.almanac.dataset;

import io.quarkiverse.almanac.maven.BomDifferences;
import io.quarkiverse.almanac.maven.BomSnapshots;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ConfigProperty;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.model.ExtensionInfo;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.util.ConfigKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/// What `dataset import` requires of a work snapshot: the invariants that make it restorable
/// exactly from the dataset (sorted, unique ids, the release's own version), and, for config, a validated extractor.
final class SnapshotChecks {

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Set<String> CONFIG_SOURCES = Set.of("model", "bytecode");
    /// At most this many problems are listed per snapshot.
    private static final int MAX_PROBLEMS = 5;
    /// Characters of a warning quoted in a refusal.
    private static final int MAX_WARNING_SHOWN = 160;

    private SnapshotChecks() {
    }

    /// The problems that make the snapshot inconsistent; empty when it can be imported.
    static List<String> problems(String release, Kind kind, Object snapshot) {
        List<String> problems = new ArrayList<>();
        switch (kind) {
            case BOM -> bom(release, (BomSnapshot) snapshot, problems);
            case CONFIG -> config(release, (ConfigSnapshot) snapshot, problems);
            case EXTENSIONS -> extensions(release, (ExtensionsSnapshot) snapshot, problems);
            case PLATFORM -> platform(release, (BomSnapshot) snapshot, problems);
        }
        return problems.size() > MAX_PROBLEMS
                ? List.copyOf(problems.subList(0, MAX_PROBLEMS)) : List.copyOf(problems);
    }

    /// Why a config snapshot is not validated, if it is not: it comes from an extractor that is not validated, or the
    /// bytecode reader could not read all the `@ConfigDoc*` annotations of a release before 3.14 from its `-sources`
    /// jars (a warning starting with [DatasetFormat#DOC_ANNOTATION_WARNING]).
    static Optional<String> unvalidated(ConfigSnapshot config) {
        if (config.extractor() != null && DatasetFormat.UNVALIDATED_EXTRACTORS.contains(config.extractor())) {
            return Optional.of("extractor " + config.extractor() + " is not validated; its snapshots stay out of the "
                    + "dataset and the site");
        }
        for (String warning : config.warnings() == null ? List.<String>of() : config.warnings()) {
            if (warning != null && warning.startsWith(DatasetFormat.DOC_ANNOTATION_WARNING)) {
                String shown = warning.length() > MAX_WARNING_SHOWN
                        ? warning.substring(0, MAX_WARNING_SHOWN) + "…" : warning;
                return Optional.of("not all @ConfigDoc* annotations were read from the -sources jars (" + shown
                        + "); extract it online with the default --doc-annotations sources");
            }
        }
        return Optional.empty();
    }

    /// What a consistent BOM snapshot keeps as written, for a warning: how many managed dependencies keep a `${...}`
    /// reference ([BomSnapshots#unresolved]), with the first of them; empty when none does.
    static Optional<String> references(BomSnapshot bom) {
        List<ManagedDep> kept = bom.deps().stream().filter(BomSnapshots::unresolved).toList();
        if (kept.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(kept.size() + (kept.size() == 1 ? " managed dependency keeps" : " managed dependencies keep")
                + " a ${...} reference as written (e.g. " + kept.getFirst().id() + " " + kept.getFirst().v() + ")");
    }

    private static void bom(String release, BomSnapshot bom, List<String> problems) {
        version(release, bom.version(), problems);
        if (bom.date() != null && !DATE.matcher(bom.date()).matches()) {
            problems.add("date " + bom.date() + " is not yyyy-MM-dd");
        }
        if (bom.deps() == null || bom.deps().isEmpty()) {
            problems.add("no managed dependencies");
            return;
        }
        String previous = null;
        for (ManagedDep dep : bom.deps()) {
            if (dep == null || empty(dep.id()) || empty(dep.v())) {
                problems.add("a managed dependency without id or version: " + dep);
                continue;
            }
            if (dep.v().equals(DatasetFormat.SAME_AS_RELEASE)) {
                problems.add(dep.id() + " has the version \"" + DatasetFormat.SAME_AS_RELEASE + "\"");
            }
            previous = ascending(previous, dep.id(), "managed dependency", problems);
        }
    }

    /// A BOM whose versions never read as [BomDifferences#NOT_MANAGED], the mark of its differences from the BOM.
    private static void platform(String release, BomSnapshot platform, List<String> problems) {
        bom(release, platform, problems);
        if (platform.deps() != null) {
            for (ManagedDep dep : platform.deps()) {
                if (dep != null && BomDifferences.NOT_MANAGED.equals(dep.v())) {
                    problems.add(dep.id() + " has the version \"" + BomDifferences.NOT_MANAGED + "\"");
                }
            }
        }
    }

    private static void config(String release, ConfigSnapshot config, List<String> problems) {
        version(release, config.version(), problems);
        if (config.source() == null || !CONFIG_SOURCES.contains(config.source())) {
            problems.add("source " + config.source() + " is neither model nor bytecode");
        }
        if (empty(config.extractor())) {
            problems.add("no extractor");
        }
        if (config.roots() < 0) {
            problems.add("negative roots " + config.roots());
        }
        if (config.properties() == null || config.properties().isEmpty()) {
            problems.add("no properties");
            return;
        }
        String previous = null;
        for (ConfigProperty property : config.properties()) {
            if (property == null || empty(property.name()) || empty(property.key())) {
                problems.add("a property without name or key: " + property);
                continue;
            }
            if (!property.key().equals(ConfigKeys.canonical(property.name()))) {
                problems.add("key " + property.key() + " is not the canonical key of " + property.name());
            }
            previous = ascending(previous, property.key(), "key", problems);
        }
    }

    private static void extensions(String release, ExtensionsSnapshot extensions, List<String> problems) {
        version(release, extensions.version(), problems);
        if (extensions.extensions() == null || extensions.extensions().isEmpty()) {
            problems.add("no extensions");
            return;
        }
        String previous = null;
        for (ExtensionInfo extension : extensions.extensions()) {
            if (extension == null || empty(extension.id())) {
                problems.add("an extension without id: " + extension);
                continue;
            }
            previous = ascending(previous, extension.id(), "extension", problems);
        }
    }

    private static void version(String release, String version, List<String> problems) {
        if (!release.equals(version)) {
            problems.add("its version is " + version + ", not " + release);
        }
    }

    /// Records a problem unless `id` sorts after `previous` (String order, no duplicates); returns `id`.
    private static String ascending(String previous, String id, String what, List<String> problems) {
        if (previous != null) {
            int c = previous.compareTo(id);
            if (c == 0) {
                problems.add("duplicate " + what + " " + id);
            } else if (c > 0) {
                problems.add(what + " " + id + " is not sorted (after " + previous + ")");
            }
        }
        return id;
    }

    private static boolean empty(String text) {
        return text == null || text.isEmpty();
    }
}
