package io.quarkiverse.almanac.dataset;

import io.quarkiverse.almanac.api.ApiGrammar;
import io.quarkiverse.almanac.maven.BomDifferences;
import io.quarkiverse.almanac.maven.BomSnapshots;
import io.quarkiverse.almanac.model.ApiSnapshot;
import io.quarkiverse.almanac.model.ArtifactApi;
import io.quarkiverse.almanac.model.ArtifactClasses;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ClassApi;
import io.quarkiverse.almanac.model.ClassesSnapshot;
import io.quarkiverse.almanac.model.ConfigProperty;
import io.quarkiverse.almanac.model.ConfigSnapshot;
import io.quarkiverse.almanac.model.ExtensionInfo;
import io.quarkiverse.almanac.model.ExtensionsSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.model.MemberApi;
import io.quarkiverse.almanac.util.ConfigKeys;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/// What `dataset import` requires of a work snapshot: the invariants that make it restorable
/// exactly from the dataset (sorted, unique ids, the release's own version, class lines that can be split again, API
/// values in their grammar), and, for config and the API, a validated reader.
final class SnapshotChecks {

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    /// What `classes.jsonl` can tell apart in `<artifactId> <class>`: no whitespace, and no empty name.
    private static final Pattern NAME = Pattern.compile("\\S+");
    private static final Set<String> CONFIG_SOURCES = Set.of("model", "bytecode");
    /// At most this many problems are listed per snapshot.
    private static final int MAX_PROBLEMS = 5;
    /// Characters of a warning quoted in a refusal.
    private static final int MAX_WARNING_SHOWN = 160;

    private SnapshotChecks() {
    }

    /// The problems that make the snapshot inconsistent; empty when it can be imported.
    static List<String> problems(String release, Kind kind, Object snapshot) {
        return problems(release, kind, snapshot, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /// The problems that make the snapshot inconsistent, skipping the API classes of `checked` (instances checked
    /// before, whose content is the same at every release that shares them) and adding the others to it.
    static List<String> problems(String release, Kind kind, Object snapshot, Set<ClassApi> checked) {
        List<String> problems = new ArrayList<>();
        switch (kind) {
            case BOM -> bom(release, (BomSnapshot) snapshot, problems);
            case CONFIG -> config(release, (ConfigSnapshot) snapshot, problems);
            case EXTENSIONS -> extensions(release, (ExtensionsSnapshot) snapshot, problems);
            case CLASSES -> classes(release, (ClassesSnapshot) snapshot, problems);
            case PLATFORM -> platform(release, (BomSnapshot) snapshot, problems);
            case API -> api(release, (ApiSnapshot) snapshot, checked, problems);
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

    /// Why an API snapshot is not validated, if it is not: its reader is not one of [DatasetFormat#API_READERS].
    static Optional<String> unvalidated(ApiSnapshot api) {
        if (api.reader() == null || !DatasetFormat.API_READERS.contains(api.reader())) {
            return Optional.of("API reader " + api.reader() + " is not validated; its snapshots stay out of the "
                    + "dataset and the site");
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

    private static void classes(String release, ClassesSnapshot classes, List<String> problems) {
        version(release, classes.version(), problems);
        if (classes.artifacts() == null || classes.artifacts().isEmpty()) {
            problems.add("no jar with classes");
            return;
        }
        String previous = null;
        for (ArtifactClasses artifact : classes.artifacts()) {
            if (artifact == null || artifact.id() == null || !NAME.matcher(artifact.id()).matches()) {
                problems.add("a jar without a usable artifactId: " + (artifact == null ? null : artifact.id()));
                continue;
            }
            previous = ascending(previous, artifact.id(), "jar", problems);
            if (artifact.classes() == null || artifact.classes().isEmpty()) {
                problems.add(artifact.id() + " lists no class");
                continue;
            }
            String previousClass = null;
            for (String name : artifact.classes()) {
                if (name == null || !NAME.matcher(name).matches() || name.contains("/")) {
                    problems.add(artifact.id() + " has a class that is no binary name: " + name);
                    continue;
                }
                previousClass = ascending(previousClass, name, "class of " + artifact.id(), problems);
            }
        }
    }

    /// The first class of an API snapshot that the classes snapshot of the release does not list in the same jar, as
    /// a message; empty when every API class is one of the release's classes (both snapshots sorted).
    static Optional<String> apiOutsideClasses(ApiSnapshot api, ClassesSnapshot classes) {
        Map<String, List<String>> byJar = new HashMap<>();
        for (ArtifactClasses artifact : classes.artifacts()) {
            byJar.put(artifact.id(), artifact.classes());
        }
        for (ArtifactApi artifact : api.artifacts()) {
            List<String> names = byJar.getOrDefault(artifact.id(), List.of());
            for (ClassApi type : artifact.classes()) {
                if (Collections.binarySearch(names, type.name()) < 0) {
                    return Optional.of("API class " + artifact.id() + " " + type.name() + " is not one of the "
                            + "release's classes");
                }
            }
        }
        return Optional.empty();
    }

    /// Sorted jars, classes and members, names a dataset id can hold, values and member keys in the [ApiGrammar].
    private static void api(String release, ApiSnapshot api, Set<ClassApi> checked, List<String> problems) {
        version(release, api.version(), problems);
        if (empty(api.reader())) {
            problems.add("no reader");
        }
        if (api.artifacts() == null || api.artifacts().isEmpty()) {
            problems.add("no jar with an API class");
            return;
        }
        String previous = null;
        for (ArtifactApi artifact : api.artifacts()) {
            if (artifact == null || artifact.id() == null || !NAME.matcher(artifact.id()).matches()) {
                problems.add("a jar without a usable artifactId: " + (artifact == null ? null : artifact.id()));
                continue;
            }
            previous = ascending(previous, artifact.id(), "jar", problems);
            if (artifact.classes() == null || artifact.classes().isEmpty()) {
                problems.add(artifact.id() + " lists no API class");
                continue;
            }
            String previousClass = null;
            for (ClassApi type : artifact.classes()) {
                if (type == null || type.name() == null || !NAME.matcher(type.name()).matches()
                        || type.name().contains("/")) {
                    problems.add(artifact.id() + " has a class that is no binary name: "
                            + (type == null ? null : type.name()));
                    continue;
                }
                previousClass = ascending(previousClass, type.name(), "class of " + artifact.id(), problems);
                if (checked.add(type)) {
                    classApi(artifact.id(), type, problems);
                }
                if (problems.size() > MAX_PROBLEMS) {
                    return;
                }
            }
        }
        implicitlyStatic(api, problems);
    }

    /// Classes marked static that are static implicitly: member classes of an interface or an annotation. What encloses
    /// a class is not in the snapshot, so it is found by name, as javac and kotlinc name member classes (`a.B` encloses
    /// `a.B$C`). Since a simple name can hold `$` itself (a member class `C$D` of `a.B` is `a.B$C$D` too), each name
    /// before a `$` of the simple name that is an API class (of the same jar, else of the first other jar that has it,
    /// as [io.quarkiverse.almanac.api.ApiReader#snapshot] finds the enclosing class) may be the enclosing class: the
    /// `s` is refused only when every one of them is an interface or an annotation, so that the `s` the reader keeps
    /// for a member class of a class is never refused. Checked at every release, since the same class record can have
    /// another enclosing class at another release.
    private static void implicitlyStatic(ApiSnapshot api, List<String> problems) {
        for (ArtifactApi artifact : api.artifacts()) {
            if (artifact == null || artifact.classes() == null) {
                continue;
            }
            for (ClassApi type : artifact.classes()) {
                if (type == null || type.name() == null || type.v() == null || !ApiGrammar.marksStatic(type.v())) {
                    continue;
                }
                ClassApi outer = implicitlyStaticIn(api, artifact, type.name());
                if (outer != null) {
                    problems.add(artifact.id() + " " + type.name() + ": class value '" + type.v() + "' marks it "
                            + "static, which a member class of the " + (ApiGrammar.classKind(outer.v())
                            == ApiGrammar.INTERFACE ? "interface " : "annotation ") + outer.name() + " is implicitly");
                    if (problems.size() > MAX_PROBLEMS) {
                        return;
                    }
                }
            }
        }
    }

    /// The interface or annotation that encloses a nested class, by name, when every API class that may enclose it (a
    /// name before a `$` of its simple name, in the same jar, else in the first other jar that has it) is an interface
    /// or an annotation: the one of the longest name. Null when one of them is neither, or there is none.
    private static ClassApi implicitlyStaticIn(ApiSnapshot api, ArtifactApi artifact, String name) {
        ClassApi longest = null;
        int simpleName = name.lastIndexOf('.') + 1;
        for (int dollar = name.lastIndexOf('$'); dollar > simpleName; dollar = name.lastIndexOf('$', dollar - 1)) {
            String outer = name.substring(0, dollar);
            ClassApi found = find(artifact, outer);
            for (int i = 0; found == null && i < api.artifacts().size(); i++) {
                found = find(api.artifacts().get(i), outer);
            }
            if (found == null) {
                continue;
            }
            char kind = found.v() == null ? 0 : ApiGrammar.classKind(found.v());
            if (kind != ApiGrammar.INTERFACE && kind != ApiGrammar.ANNOTATION) {
                return null;
            }
            if (longest == null) {
                longest = found;
            }
        }
        return longest;
    }

    /// The class of that name among the sorted API classes of a jar, null when it has none.
    private static ClassApi find(ArtifactApi artifact, String name) {
        List<ClassApi> classes = artifact == null || artifact.classes() == null ? List.of() : artifact.classes();
        int low = 0;
        int high = classes.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            ClassApi type = classes.get(middle);
            int c = type == null || type.name() == null ? -1 : type.name().compareTo(name);
            if (c == 0) {
                return type;
            }
            if (c < 0) {
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return null;
    }

    private static void classApi(String artifactId, ClassApi type, List<String> problems) {
        String where = artifactId + " " + type.name();
        if (type.v() == null) {
            problems.add(where + " has no value");
        } else {
            ApiGrammar.classValueProblem(type.v()).ifPresent(problem -> problems.add(where + ": " + problem));
        }
        if (type.members() == null) {
            problems.add(where + " has no member list");
            return;
        }
        String previous = null;
        for (MemberApi member : type.members()) {
            if (member == null || member.id() == null || member.v() == null) {
                problems.add(where + " has a member without key or value: " + member);
                continue;
            }
            previous = ascending(previous, member.id(), "member of " + where, problems);
            Optional<String> problem = ApiGrammar.memberKeyProblem(member.id())
                    .or(() -> ApiGrammar.memberValueProblem(member.id(), member.v()));
            problem.ifPresent(text -> problems.add(where + ": " + text));
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
