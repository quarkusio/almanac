package io.quarkiverse.almanac.maven;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/// The dependency profile of a release: of the artifacts that its BOM manages, those that Quarkus itself depends on,
/// through its extensions (their runtime and deployment artifacts), its Maven plugin and its test frameworks. The BOM's
/// other entries only set the version of what an application uses itself. (The two categories are Alexey
/// Loubyansky's, on the Quarkus Zulip; they match what a vulnerability scan of Quarkus looks at.)
///
/// The dependencies of each root are collected as Maven collects those of a project's dependency: the compile and
/// runtime dependencies of each pom, inherited from its parents and from its active profiles, neither optional nor
/// excluded, by the dependency that brought them or by the BOM's entry of them. A profile counts as active when it is
/// active by default, or by conditions that hold in some build of an application: a JDK range that holds JDK 21, a
/// property left undefined, an operating system (Quarkus runs on several, and each gets the profile of its own). An
/// artifact that the BOM manages is followed at the version the BOM gives, any other at the version that the pom
/// declaring it gives, its own or its dependency management's (imported BOMs included), as Maven does below a
/// project's direct dependencies; only those the BOM manages count as used. One that the BOM manages in scope
/// provided, test or system is neither, below the roots: Maven gives it that scope in every application. A pom that
/// relocates leads to the artifact it names.
///
/// Besides the uses, the profile keeps the graph of the walks upward ([Result#parents]), so that a reader can tell
/// which artifacts of Quarkus bring an artifact in, and through what. Its nodes are the artifacts together with the
/// exclusions of their path that leave something out below them: two paths that leave out different things below an
/// artifact reach two nodes of it, so the nodes above a node are exactly those whose dependencies bring it in.
public final class DependencyProfile {

    /// How Quarkus uses an artifact, the strongest first: at run time (a dependency of an extension's runtime
    /// artifact), at build time only (of a deployment artifact or of the Maven plugin), in tests only (of a test
    /// framework).
    public enum Use {
        RUNTIME("runtime"), BUILD("build"), TEST("test");

        private final String label;

        Use(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /// An artifact of Quarkus that the profile starts from, and how what it depends on is used.
    public record Root(String groupId, String artifactId, String version, Use use) {
    }

    /// @param used      each id that the BOM manages and that a root reaches → its strongest use
    /// @param parents   each node of the walks on a way up from a used id → the nodes whose dependency brought it, in
    ///                  the walk of the node's strongest use (the first walk that reached it, so that those above a node
    ///                  all have its use). A node is written as its id, then `@` and the version for an artifact the BOM
    ///                  does not manage that the release reaches at more than one version, then `!` and the exclusion
    ///                  patterns of its path that leave out something below it, sorted and comma-separated, when there
    ///                  are any (but for the BOM's own exclusions of the id, which every path to it has)
    /// @param starts    each used id whose nodes of its strongest use are not just the node written as the id → those
    ///                  nodes, where the ways up to Quarkus start
    /// @param poms      the poms read, parents and imported BOMs included (not those the repository lacks)
    /// @param unmanaged the ids of the dependencies that the BOM does not manage, on a path that does not exclude them,
    ///                  followed or not
    /// @param problems  the poms that could not be read, the dependencies that could not be interpolated or followed
    public record Result(SortedMap<String, Use> used, SortedMap<String, SortedSet<String>> parents,
                         SortedMap<String, SortedSet<String>> starts, int poms, SortedSet<String> unmanaged,
                         List<String> problems) {
    }

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([^}]+)}");
    private static final String JDK = "21";
    private static final int MAX_PARENTS = 20;
    private static final int MAX_IMPORT_DEPTH = 10;

    private final BomResolver.PomSource source;
    private final String bomVersion;
    private final Map<String, String> managed;
    private final Map<String, List<String>> exclusions;
    private final Map<String, String> scopes;
    private final Map<String, Optional<PomFile>> poms = new HashMap<>();
    private final Map<String, List<Dependency>> dependencies = new HashMap<>();
    private final Map<String, Management> imported = new HashMap<>();
    private final SortedSet<String> unmanaged = new TreeSet<>();
    private final Set<String> problems = new LinkedHashSet<>();
    // the graph of the poms followed, whatever their path excludes: each pom read → the poms of its dependencies, and
    // the other way; and for each exclusion pattern, the poms with a dependency it matches somewhere below them
    private final Map<String, Set<String>> below = new HashMap<>();
    private final Map<String, Set<String>> above = new HashMap<>();
    private final Map<String, Set<String>> reaching = new HashMap<>();
    // the versions at which the release reaches each artifact that the BOM does not manage
    private final Map<String, Set<String>> unmanagedVersions = new HashMap<>();

    /// @param source     the poms, by coordinates
    /// @param bomVersion the release, for [BomParser#id] of classifiers equal to it
    /// @param managed    the BOM's managed versions by [ManagedDep][io.quarkiverse.almanac.model.ManagedDep] id
    /// @param exclusions the BOM's exclusions by id, as `groupId:artifactId` patterns ([#exclusions(byte[], String)])
    /// @param scopes     the BOM's scopes by id, of the entries that give one ([#scopes(byte[], String)])
    public DependencyProfile(BomResolver.PomSource source, String bomVersion, Map<String, String> managed,
            Map<String, List<String>> exclusions, Map<String, String> scopes) {
        this.source = source;
        this.bomVersion = bomVersion;
        this.managed = managed;
        this.exclusions = exclusions;
        this.scopes = scopes;
    }

    /// Collects what the roots depend on, root use by root use, the strongest first: an id that roots of several uses
    /// reach keeps the strongest. The poms are read first, by a walk that visits a pom again only with exclusions that
    /// leave out less than before; the walks by use then read no other.
    public Result collect(List<Root> roots) throws IOException {
        explore(roots);
        SortedMap<String, Use> used = new TreeMap<>();
        SortedMap<String, SortedSet<String>> parents = new TreeMap<>();
        Map<String, Use> first = new HashMap<>();
        Map<String, Set<String>> nodesOf = new HashMap<>();
        for (Use use : Use.values()) {
            walk(roots.stream().filter(root -> root.use() == use).toList(), use,
                    new Walk(used, parents, first, nodesOf));
        }
        SortedMap<String, SortedSet<String>> starts = new TreeMap<>();
        used.forEach((id, use) -> {
            SortedSet<String> nodes = new TreeSet<>();
            for (String node : nodesOf.getOrDefault(id, Set.of())) {
                if (first.get(node) == use) {
                    nodes.add(node);
                }
            }
            if (!nodes.equals(Set.of(id))) {
                starts.put(id, nodes);
            }
        });
        // only the nodes on a way up from a used id: no reader ever reaches the others
        Set<String> kept = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        used.keySet().forEach(id -> starts.getOrDefault(id, new TreeSet<>(Set.of(id))).forEach(node -> {
            if (kept.add(node)) {
                queue.add(node);
            }
        }));
        while (!queue.isEmpty()) {
            for (String parent : parents.getOrDefault(queue.poll(), Collections.emptySortedSet())) {
                if (kept.add(parent)) {
                    queue.add(parent);
                }
            }
        }
        parents.keySet().retainAll(kept);
        int read = (int) poms.values().stream().filter(Optional::isPresent).count();
        return new Result(used, parents, starts, read, unmanaged, List.copyOf(problems));
    }

    /// Where a dependency leads: its id, the pom to read at the version it is followed at (`version` null when it
    /// cannot be followed: a version the BOM leaves unresolved), and whether the BOM manages it.
    private record Target(String id, String groupId, String artifactId, String version, boolean managed) {

        String coordinates() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }

    /// Where a dependency of the pom `from` leads; null when it leads nowhere: one that the BOM manages in scope
    /// provided, test or system, or one it does not manage without a version to follow it at. `record` says whether a
    /// path follows it, so that the unmanaged ids and the problems are those of the dependencies a path follows.
    private Target target(Dependency dependency, String from, boolean record) {
        String id = BomParser.id(dependency.groupId(), dependency.artifactId(), dependency.type(),
                dependency.classifier(), bomVersion);
        String version = managed.get(id);
        if (version != null) {
            if (!Dependency.transitiveScope(scopes.get(id))) {
                return null;
            }
            if (version.contains("${")) {
                if (record) {
                    problems.add(id + ": the BOM's version " + version + " is not resolved, so it is not followed");
                }
                version = null;
            }
            return new Target(id, dependency.groupId(), dependency.artifactId(), version, true);
        }
        if (record) {
            unmanaged.add(id);
        }
        String declared = dependency.version();
        if (declared == null) {
            if (record) {
                problems.add(from + ": no version of " + id + ", which the BOM does not manage, so it is not followed");
            }
            return null;
        }
        if (!followable(declared)) {
            if (record) {
                problems.add(from + ": " + id + " " + declared + " is not a version to follow (a range or unresolved)");
            }
            return null;
        }
        return new Target(id, dependency.groupId(), dependency.artifactId(), declared, false);
    }

    /// Whether a version names one release: no range, no unresolved reference, no LATEST or RELEASE.
    private static boolean followable(String version) {
        return !version.isEmpty() && !version.contains("${") && !version.startsWith("[") && !version.startsWith("(")
                && !version.contains(",") && !version.equals("LATEST") && !version.equals("RELEASE");
    }

    /// Reads every pom that a root reaches and records the graph of the poms followed: Maven's walk, a pom visited
    /// again only with exclusions that leave out less than its earlier visits'.
    private void explore(List<Root> roots) throws IOException {
        record Visit(String groupId, String artifactId, String version, Set<String> excluded) {
        }
        Deque<Visit> queue = new ArrayDeque<>();
        for (Root root : roots) {
            String id = BomParser.id(root.groupId(), root.artifactId(), null, null, bomVersion);
            if (!managed.containsKey(id)) {
                unmanagedVersions.computeIfAbsent(id, key -> new HashSet<>()).add(root.version());
            }
            queue.add(new Visit(root.groupId(), root.artifactId(), root.version(),
                    Set.copyOf(exclusions.getOrDefault(id, List.of()))));
        }
        Map<String, List<Set<String>>> visited = new HashMap<>();
        while (!queue.isEmpty()) {
            Visit visit = queue.poll();
            String coordinates = visit.groupId() + ":" + visit.artifactId() + ":" + visit.version();
            List<Set<String>> earlier = visited.computeIfAbsent(coordinates, key -> new ArrayList<>());
            if (earlier.stream().anyMatch(visit.excluded()::containsAll)) {
                continue;
            }
            earlier.add(visit.excluded());
            Set<String> targets = below.computeIfAbsent(coordinates, key -> new HashSet<>());
            for (Dependency dependency : dependencies(visit.groupId(), visit.artifactId(), visit.version())) {
                boolean cut = excludes(visit.excluded(), dependency.groupId(), dependency.artifactId());
                Target target = target(dependency, coordinates, !cut);
                if (target == null || target.version() == null) {
                    continue;
                }
                // the graph keeps the dependencies this path excludes too: another path may not
                if (targets.add(target.coordinates())) {
                    above.computeIfAbsent(target.coordinates(), key -> new HashSet<>()).add(coordinates);
                }
                if (cut) {
                    continue;
                }
                if (!target.managed()) {
                    unmanagedVersions.computeIfAbsent(target.id(), key -> new HashSet<>()).add(target.version());
                }
                Set<String> excluded = new HashSet<>(visit.excluded());
                excluded.addAll(dependency.exclusions());
                if (target.managed()) {
                    excluded.addAll(exclusions.getOrDefault(target.id(), List.of()));
                }
                queue.add(new Visit(target.groupId(), target.artifactId(), target.version(), Set.copyOf(excluded)));
            }
        }
    }

    /// The poms that have a dependency that `pattern` matches, themselves or somewhere below them in the graph that
    /// [#explore] recorded: those where an exclusion of it can leave something out.
    private Set<String> reaching(String pattern) {
        Set<String> found = reaching.get(pattern);
        if (found != null) {
            return found;
        }
        found = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, List<Dependency>> pom : dependencies.entrySet()) {
            for (Dependency dependency : pom.getValue()) {
                if (excludes(Set.of(pattern), dependency.groupId(), dependency.artifactId())) {
                    found.add(pom.getKey());
                    queue.add(pom.getKey());
                    break;
                }
            }
        }
        while (!queue.isEmpty()) {
            for (String up : above.getOrDefault(queue.poll(), Set.of())) {
                if (found.add(up)) {
                    queue.add(up);
                }
            }
        }
        reaching.put(pattern, found);
        return found;
    }

    /// A node of the graph of the walks: where a dependency leads, and the exclusions of its path that leave out
    /// something below it there (the BOM's own exclusions of the id included).
    private record Visited(String key, Target target, Set<String> excluded) {
    }

    /// The node that a path reaches `target` as, with the exclusions it has gathered.
    private Visited node(Target target, Set<String> excluded) {
        Set<String> effective = new TreeSet<>();
        if (target.version() != null) {
            for (String pattern : excluded) {
                if (reaching(pattern).contains(target.coordinates())) {
                    effective.add(pattern);
                }
            }
        }
        StringBuilder key = new StringBuilder(target.id());
        // the version only tells apart the versions of an artifact the BOM does not manage, when the release has several
        if (!target.managed() && target.version() != null
                && unmanagedVersions.getOrDefault(target.id(), Set.of()).size() > 1) {
            key.append('@').append(target.version());
        }
        List<String> own = target.managed() ? exclusions.getOrDefault(target.id(), List.of()) : List.of();
        String path = effective.stream().filter(pattern -> !own.contains(pattern)).collect(Collectors.joining(","));
        if (!path.isEmpty()) {
            key.append('!').append(path);
        }
        return new Visited(key.toString(), target, Set.copyOf(effective));
    }

    /// What the walks record: the uses of the ids, the graph upward, the use of the first walk to reach each node
    /// (its strongest) and the nodes of each used id.
    private record Walk(Map<String, Use> used, Map<String, SortedSet<String>> parents, Map<String, Use> first,
                        Map<String, Set<String>> nodesOf) {

        /// Records that the walk of `use` reached `node`, from the node `parent` (null for a root).
        void reached(Visited node, String parent, Use use) {
            Target target = node.target();
            if (target.managed()) {
                mark(used, target.id(), use);
                nodesOf.computeIfAbsent(target.id(), key -> new HashSet<>()).add(node.key());
            }
            Use before = first.putIfAbsent(node.key(), use);
            // only the ways of its strongest use, so that the nodes above it all have that use
            if (parent != null && (before == null || before == use)) {
                parents.computeIfAbsent(node.key(), key -> new TreeSet<>()).add(parent);
            }
        }
    }

    private void walk(List<Root> roots, Use use, Walk walk) throws IOException {
        Deque<Visited> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        for (Root root : roots) {
            String id = BomParser.id(root.groupId(), root.artifactId(), null, null, bomVersion);
            Visited node = node(new Target(id, root.groupId(), root.artifactId(), root.version(),
                    managed.containsKey(id)), Set.copyOf(exclusions.getOrDefault(id, List.of())));
            walk.reached(node, null, use);
            if (seen.add(node.key())) {
                queue.add(node);
            }
        }
        // a node's key says all that its subtree depends on, so each is visited once
        while (!queue.isEmpty()) {
            Visited node = queue.poll();
            Target at = node.target();
            if (!below.containsKey(at.coordinates())) {
                problems.add(at.coordinates() + ": reached by a walk but not by the exploration (a defect)");
            }
            for (Dependency dependency : dependencies(at.groupId(), at.artifactId(), at.version())) {
                if (excludes(node.excluded(), dependency.groupId(), dependency.artifactId())) {
                    continue;
                }
                Target target = target(dependency, at.coordinates(), true);
                if (target == null) {
                    continue;
                }
                Set<String> excluded = new HashSet<>(node.excluded());
                excluded.addAll(dependency.exclusions());
                if (target.managed()) {
                    excluded.addAll(exclusions.getOrDefault(target.id(), List.of()));
                }
                Visited child = node(target, excluded);
                walk.reached(child, node.key(), use);
                if (target.version() != null && seen.add(child.key())) {
                    queue.add(child);
                }
            }
        }
    }

    /// Records a use of an id, which keeps its strongest; true when this one is it, the first or a stronger one.
    private static boolean mark(Map<String, Use> used, String id, Use use) {
        Use before = used.get(id);
        if (before != null && before.ordinal() <= use.ordinal()) {
            return false;
        }
        used.put(id, use);
        return true;
    }

    private static boolean excludes(Set<String> patterns, String groupId, String artifactId) {
        for (String pattern : patterns) {
            int colon = pattern.indexOf(':');
            String g = pattern.substring(0, colon);
            String a = pattern.substring(colon + 1);
            if ((g.equals("*") || g.equals(groupId)) && (a.equals("*") || a.equals(artifactId))) {
                return true;
            }
        }
        return false;
    }

    /// The dependencies of a pom as Maven sees them in a project that depends on it: its own and its parents', those
    /// of its active profiles, interpolated with its properties and model values, with the version, scope and
    /// exclusions of its dependency management for those that give none. A pom that relocates has the artifact it
    /// names as its one dependency. Empty when the pom cannot be read.
    private List<Dependency> dependencies(String groupId, String artifactId, String version) throws IOException {
        String coordinates = groupId + ":" + artifactId + ":" + version;
        List<Dependency> cached = dependencies.get(coordinates);
        if (cached != null) {
            return cached;
        }
        List<PomFile> lineage = lineage(groupId, artifactId, version);
        List<Dependency> result = new ArrayList<>();
        if (!lineage.isEmpty()) {
            Model model = model(lineage);
            PomFile.Coordinates relocation = lineage.getFirst().relocation();
            if (relocation != null) {
                try {
                    // Maven reads the artifact that a relocated pom names instead
                    result.add(new Dependency(
                            relocation.groupId() != null ? Dependency.substitute(relocation.groupId(), model.values())
                                    : model.groupId(),
                            relocation.artifactId() != null
                                    ? Dependency.substitute(relocation.artifactId(), model.values())
                                    : lineage.getFirst().artifactId(),
                            relocation.version() != null ? Dependency.substitute(relocation.version(), model.values())
                                    : model.version(),
                            null, null, null, null, List.of()));
                } catch (IOException e) {
                    problems.add(coordinates + ": relocation " + relocation + ": " + e.getMessage());
                }
            } else {
                Management management = management(model, coordinates, 0);
                for (Dependency raw : model.declared()) {
                    try {
                        // only what reaches a dependent project, whose other values may name properties of the
                        // machine that builds (an optional native transport's ${os.detected.classifier}): the scope
                        // and optional flag first
                        String scope = Dependency.substitute(raw.scope(), model.values());
                        if ("true".equalsIgnoreCase(Dependency.substitute(raw.optional(), model.values()))
                                || !Dependency.transitiveScope(scope)) {
                            continue;
                        }
                        Dependency dependency = raw.interpolated(model.values());
                        Dependency entry = management.get(dependency.key());
                        if (scope == null && entry != null) {
                            scope = entry.scope();
                        }
                        if (!Dependency.transitiveScope(scope)) {
                            continue;
                        }
                        List<String> excluded = dependency.exclusions().isEmpty() && entry != null
                                ? entry.exclusions() : dependency.exclusions();
                        String declared = dependency.version() != null ? dependency.version()
                                : entry != null ? entry.version() : null;
                        result.add(new Dependency(dependency.groupId(), dependency.artifactId(), declared,
                                dependency.type(), dependency.classifier(), scope, null, excluded));
                    } catch (IOException e) {
                        problems.add(coordinates + ": " + e.getMessage());
                    }
                }
            }
        }
        List<Dependency> list = List.copyOf(result);
        dependencies.put(coordinates, list);
        return list;
    }

    /// What a pom's lineage gives its dependencies: the values of its references, its own groupId and version, its
    /// dependencies and managed dependencies (a child's entry replaces its parents' of the same key, those of active
    /// profiles included) and the BOMs it imports, the child's first.
    private record Model(Interpolation values, String groupId, String version, Collection<Dependency> declared,
                         Collection<Dependency> management, List<Dependency> imports) {
    }

    private static Model model(List<PomFile> lineage) {
        Map<String, String> properties = new HashMap<>();
        Map<String, Dependency> declared = new LinkedHashMap<>();
        Map<String, Dependency> declaredManagement = new LinkedHashMap<>();
        for (PomFile pom : lineage.reversed()) {
            properties.putAll(pom.properties());
            for (PomFile.Profile profile : pom.activeProfiles()) {
                properties.putAll(profile.properties());
            }
            // a child's dependency (or managed dependency) replaces its parents' of the same key
            for (Dependency dependency : pom.dependencies()) {
                declared.put(dependency.key(), dependency);
            }
            for (Dependency dependency : pom.managed()) {
                declaredManagement.put(dependency.key(), dependency);
            }
            for (PomFile.Profile profile : pom.activeProfiles()) {
                for (Dependency dependency : profile.dependencies()) {
                    declared.put(dependency.key(), dependency);
                }
                for (Dependency dependency : profile.managed()) {
                    declaredManagement.put(dependency.key(), dependency);
                }
            }
        }
        PomFile pom = lineage.getFirst();
        Map<String, String> model = new HashMap<>();
        String ownGroupId = pom.groupId() != null ? pom.groupId()
                : pom.parent() != null ? pom.parent().groupId() : null;
        String ownVersion = pom.version() != null ? pom.version()
                : pom.parent() != null ? pom.parent().version() : null;
        for (String prefix : List.of("project.", "pom.", "")) {
            putIfNotNull(model, prefix + "groupId", ownGroupId);
            putIfNotNull(model, prefix + "artifactId", pom.artifactId());
            putIfNotNull(model, prefix + "version", ownVersion);
        }
        if (pom.parent() != null) {
            for (String prefix : List.of("project.parent.", "parent.")) {
                putIfNotNull(model, prefix + "groupId", pom.parent().groupId());
                putIfNotNull(model, prefix + "artifactId", pom.parent().artifactId());
                putIfNotNull(model, prefix + "version", pom.parent().version());
            }
        }
        Interpolation values = name -> interpolate("${" + name + "}", model, properties);
        // the imports, the child's first: Maven adds an imported entry only when no earlier entry has its key
        Map<String, Dependency> imports = new LinkedHashMap<>();
        for (PomFile each : lineage) {
            List<Dependency> entries = new ArrayList<>(each.managed());
            each.activeProfiles().forEach(profile -> entries.addAll(profile.managed()));
            for (Dependency entry : entries) {
                if ("import".equals(entry.scope())) {
                    imports.putIfAbsent(entry.key(), entry);
                }
            }
        }
        List<Dependency> management = declaredManagement.values().stream()
                .filter(entry -> !"import".equals(entry.scope())).toList();
        return new Model(values, ownGroupId, ownVersion, declared.values(), management,
                List.copyOf(imports.values()));
    }

    /// A pom's dependency management as its dependencies see it: its own (its parents' included), then that of the
    /// BOMs it imports, in order, the first entry of a key winning.
    private record Management(Map<String, Dependency> own, List<Management> imports) {

        static final Management NONE = new Management(Map.of(), List.of());

        Dependency get(String key) {
            Dependency entry = own.get(key);
            for (int i = 0; entry == null && i < imports.size(); i++) {
                entry = imports.get(i).get(key);
            }
            return entry;
        }
    }

    private Management management(Model model, String coordinates, int depth) throws IOException {
        Map<String, Dependency> own = new HashMap<>();
        for (Dependency raw : model.management()) {
            try {
                Dependency entry = raw.interpolated(model.values());
                own.putIfAbsent(entry.key(), entry);
            } catch (IOException e) {
                // an entry whose coordinates cannot be read manages nothing of this pom's
            }
        }
        List<Management> imports = new ArrayList<>();
        for (Dependency raw : model.imports()) {
            if (depth >= MAX_IMPORT_DEPTH) {
                problems.add(coordinates + ": imports nested deeper than " + MAX_IMPORT_DEPTH + " are left out");
                break;
            }
            Dependency bom;
            try {
                bom = raw.interpolated(model.values());
            } catch (IOException e) {
                problems.add(coordinates + ": import " + raw.groupId() + ":" + raw.artifactId() + ": "
                        + e.getMessage());
                continue;
            }
            if (bom.version() == null || !followable(bom.version())) {
                problems.add(coordinates + ": the import of " + bom.groupId() + ":" + bom.artifactId()
                        + " has no version to read it at");
                continue;
            }
            // a failure to read it (requests stopped) fails the profile, which must not go without its versions
            imports.add(imported(bom.groupId(), bom.artifactId(), bom.version(), depth + 1));
        }
        return new Management(own, List.copyOf(imports));
    }

    /// The dependency management of an imported BOM: its own and that of the BOMs it imports in turn.
    private Management imported(String groupId, String artifactId, String version, int depth) throws IOException {
        String coordinates = groupId + ":" + artifactId + ":" + version;
        Management cached = imported.get(coordinates);
        if (cached != null) {
            return cached;
        }
        // an import cycle adds nothing more
        imported.put(coordinates, Management.NONE);
        try {
            List<PomFile> lineage = lineage(groupId, artifactId, version);
            Management management = lineage.isEmpty() ? Management.NONE
                    : management(model(lineage), coordinates, depth);
            imported.put(coordinates, management);
            return management;
        } catch (IOException e) {
            imported.remove(coordinates);
            throw e;
        }
    }

    /// The pom and its parents, the pom first; empty when the pom cannot be read (a parent that cannot be read ends the
    /// lineage, as a problem).
    private List<PomFile> lineage(String groupId, String artifactId, String version) throws IOException {
        List<PomFile> lineage = new ArrayList<>();
        Optional<PomFile> pom = fetch(groupId, artifactId, version);
        while (pom.isPresent() && lineage.size() <= MAX_PARENTS) {
            lineage.add(pom.get());
            PomFile.Coordinates parent = pom.get().parent();
            if (parent == null) {
                break;
            }
            String parentVersion = parent.version();
            if (parentVersion != null && parentVersion.contains("${")) {
                try {
                    parentVersion = interpolate(parentVersion, Map.of(), pom.get().properties());
                } catch (IOException e) {
                    problems.add(groupId + ":" + artifactId + ":" + version + ": parent " + parent + ": "
                            + e.getMessage());
                    break;
                }
            }
            if (parent.groupId() == null || parent.artifactId() == null || parentVersion == null) {
                problems.add(groupId + ":" + artifactId + ":" + version + ": incomplete parent " + parent);
                break;
            }
            pom = fetch(parent.groupId(), parent.artifactId(), parentVersion);
        }
        return lineage;
    }

    private Optional<PomFile> fetch(String groupId, String artifactId, String version) throws IOException {
        String coordinates = groupId + ":" + artifactId + ":" + version;
        Optional<PomFile> cached = poms.get(coordinates);
        if (cached != null) {
            return cached;
        }
        Optional<PomFile> pom;
        Optional<byte[]> content = source.pom(groupId, artifactId, version);
        if (content.isEmpty()) {
            problems.add(coordinates + ": no pom in the repository");
            pom = Optional.empty();
        } else {
            try {
                pom = Optional.of(PomFile.parse(content.get()));
            } catch (IOException e) {
                problems.add(coordinates + ": " + e.getMessage());
                pom = Optional.empty();
            }
        }
        poms.put(coordinates, pom);
        return pom;
    }

    /// Replaces `${name}` references, recursively, from the model values first, then the properties.
    ///
    /// @throws IOException when a reference is undefined or the references form a cycle
    private static String interpolate(String value, Map<String, String> model, Map<String, String> properties)
            throws IOException {
        return interpolate(value, model, properties, new ArrayList<>());
    }

    private static String interpolate(String value, Map<String, String> model, Map<String, String> properties,
            List<String> resolving) throws IOException {
        if (value == null || !value.contains("${")) {
            return value;
        }
        Matcher matcher = REFERENCE.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (resolving.contains(name)) {
                throw new IOException("the property references form a cycle: " + String.join(" -> ", resolving)
                        + " -> " + name);
            }
            String replacement = model.containsKey(name) ? model.get(name) : properties.get(name);
            if (replacement == null) {
                throw new IOException("undefined property ${" + name + "} in " + value);
            }
            resolving.add(name);
            replacement = interpolate(replacement, model, properties, resolving);
            resolving.removeLast();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static void putIfNotNull(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /// The exclusions of a BOM's managed dependencies, by [BomParser#id] → `groupId:artifactId` patterns. Read from
    /// the published BOM as written, which since 2.0 is flattened: entries whose coordinates still have a property
    /// reference are left out.
    public static Map<String, List<String>> exclusions(byte[] bomPom, String bomVersion) throws IOException {
        Element project = Xml.parse(bomPom);
        Map<String, List<String>> result = new HashMap<>();
        for (Element element : Xml.path(project, "dependencyManagement", "dependencies")
                .map(d -> Xml.children(d, "dependency")).orElse(List.of())) {
            Dependency dependency = Dependency.parse(element);
            if (dependency.exclusions().isEmpty() || dependency.groupId() == null || dependency.artifactId() == null
                    || (dependency.groupId() + dependency.artifactId()).contains("${")) {
                continue;
            }
            result.put(BomParser.id(dependency.groupId(), dependency.artifactId(), dependency.type(),
                    dependency.classifier(), bomVersion), dependency.exclusions());
        }
        return result;
    }

    /// The scopes of a BOM's managed dependencies that give one, imports aside, by [BomParser#id]. Read from the
    /// published BOM as written, like [#exclusions(byte[], String)].
    public static Map<String, String> scopes(byte[] bomPom, String bomVersion) throws IOException {
        Element project = Xml.parse(bomPom);
        Map<String, String> result = new HashMap<>();
        for (Element element : Xml.path(project, "dependencyManagement", "dependencies")
                .map(d -> Xml.children(d, "dependency")).orElse(List.of())) {
            Dependency dependency = Dependency.parse(element);
            if (dependency.scope() == null || dependency.scope().equals("import") || dependency.groupId() == null
                    || dependency.artifactId() == null
                    || (dependency.groupId() + dependency.artifactId()).contains("${")) {
                continue;
            }
            result.put(BomParser.id(dependency.groupId(), dependency.artifactId(), dependency.type(),
                    dependency.classifier(), bomVersion), dependency.scope().strip());
        }
        return result;
    }

    /// A `<dependency>` as written, or interpolated ([#interpolated]).
    ///
    /// @param version    as written, or as interpolated: null when a reference in it cannot be resolved (only a
    ///                   dependency that the BOM does not manage needs it)
    /// @param exclusions its `<exclusions>` as `groupId:artifactId` patterns (`*` for any)
    record Dependency(String groupId, String artifactId, String version, String type, String classifier,
                      String scope, String optional, List<String> exclusions) {

        static Dependency parse(Element element) {
            List<String> exclusions = new ArrayList<>();
            for (Element exclusion : Xml.path(element, "exclusions").map(e -> Xml.children(e, "exclusion"))
                    .orElse(List.of())) {
                exclusions.add(Xml.childText(exclusion, "groupId").orElse("*") + ":"
                        + Xml.childText(exclusion, "artifactId").orElse("*"));
            }
            return new Dependency(text(element, "groupId"), text(element, "artifactId"), text(element, "version"),
                    text(element, "type"), text(element, "classifier"), text(element, "scope"),
                    text(element, "optional"), List.copyOf(exclusions));
        }

        /// Maven's key of a dependency as written: `groupId:artifactId:type:classifier`, type `jar` when absent.
        String key() {
            return groupId + ":" + artifactId + ":" + (type != null ? type : "jar") + ":"
                    + (classifier != null ? classifier : "");
        }

        /// What reaches a project that depends on the pom: compile and runtime dependencies that are not optional.
        boolean transitive() {
            return transitiveScope(scope) && !"true".equalsIgnoreCase(optional);
        }

        /// Whether a dependency of this scope (null for none, `compile`) reaches a project that depends on its pom.
        static boolean transitiveScope(String scope) {
            return scope == null || scope.equals("compile") || scope.equals("runtime");
        }

        /// This dependency with its references replaced; the version null when one of its references cannot be.
        ///
        /// @throws IOException when a reference outside the version cannot be replaced
        Dependency interpolated(Interpolation interpolation) throws IOException {
            List<String> resolved = new ArrayList<>();
            for (String exclusion : exclusions) {
                resolved.add(exclusion.contains("${") ? substitute(exclusion, interpolation) : exclusion);
            }
            String resolvedVersion;
            try {
                resolvedVersion = blankToNull(substitute(version, interpolation));
            } catch (IOException e) {
                resolvedVersion = null;
            }
            return new Dependency(substitute(groupId, interpolation), substitute(artifactId, interpolation),
                    resolvedVersion, substitute(type, interpolation),
                    blankToNull(substitute(classifier, interpolation)), substitute(scope, interpolation),
                    substitute(optional, interpolation), List.copyOf(resolved));
        }

        static String substitute(String value, Interpolation interpolation) throws IOException {
            if (value == null || !value.contains("${")) {
                return value;
            }
            Matcher matcher = REFERENCE.matcher(value);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(interpolation.value(matcher.group(1))));
            }
            matcher.appendTail(out);
            return out.toString();
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }

    /// The value of a `${name}` reference.
    @FunctionalInterface
    interface Interpolation {
        String value(String name) throws IOException;
    }

    /// What the profile reads from one pom: its coordinates, parent, relocation, properties, dependencies, managed
    /// dependencies and profiles, as written.
    record PomFile(String groupId, String artifactId, String version, Coordinates parent, Coordinates relocation,
                   Map<String, String> properties, List<Dependency> dependencies, List<Dependency> managed,
                   List<Profile> profiles) {

        record Coordinates(String groupId, String artifactId, String version) {

            @Override
            public String toString() {
                return groupId + ":" + artifactId + ":" + version;
            }
        }

        /// A profile and what makes it active in some build of a project that depends on the pom, without `-P` or
        /// properties: active by default (unless another profile of the pom is active), or by conditions that all
        /// hold ([#activates]).
        record Profile(boolean activeByDefault, boolean active, Map<String, String> properties,
                       List<Dependency> dependencies, List<Dependency> managed) {
        }

        static PomFile parse(byte[] xml) throws IOException {
            Element project = Xml.parse(xml);
            Coordinates parent = Xml.path(project, "parent")
                    .map(element -> new Coordinates(text(element, "groupId"), text(element, "artifactId"),
                            text(element, "version")))
                    .orElse(null);
            Coordinates relocation = Xml.path(project, "distributionManagement", "relocation")
                    .map(element -> new Coordinates(text(element, "groupId"), text(element, "artifactId"),
                            text(element, "version")))
                    .orElse(null);
            List<Profile> profiles = new ArrayList<>();
            for (Element profile : Xml.path(project, "profiles").map(p -> Xml.children(p, "profile"))
                    .orElse(List.of())) {
                Optional<Element> activation = Xml.path(profile, "activation");
                boolean byDefault = activation.flatMap(a -> Xml.childText(a, "activeByDefault"))
                        .filter("true"::equalsIgnoreCase).isPresent();
                boolean active = activation.map(PomFile::activates).orElse(false);
                profiles.add(new Profile(byDefault, active, properties(profile), dependencies(profile, "dependencies"),
                        dependencies(profile, "dependencyManagement", "dependencies")));
            }
            return new PomFile(text(project, "groupId"), text(project, "artifactId"), text(project, "version"),
                    parent, relocation, properties(project), dependencies(project, "dependencies"),
                    dependencies(project, "dependencyManagement", "dependencies"), List.copyOf(profiles));
        }

        /// The profiles in effect: those active by their conditions, else those active by default.
        List<Profile> activeProfiles() {
            List<Profile> active = profiles.stream().filter(Profile::active).toList();
            return active.isEmpty() ? profiles.stream().filter(Profile::activeByDefault).toList() : active;
        }

        /// Whether the conditions of an activation all hold in some build of an application: a JDK range that holds
        /// JDK 21, a property that is not defined (`!name`), an operating system (any: Quarkus runs on Linux, macOS
        /// and Windows, on several architectures, and each gets the profile of its own). A file condition does not
        /// hold, nor does an activation without conditions.
        private static boolean activates(Element activation) {
            Optional<String> jdk = Xml.childText(activation, "jdk");
            Optional<Element> property = Xml.path(activation, "property");
            boolean os = Xml.path(activation, "os").isPresent();
            if (Xml.path(activation, "file").isPresent() || (jdk.isEmpty() && property.isEmpty() && !os)) {
                return false;
            }
            boolean holds = jdk.map(range -> jdkHolds(range, JDK)).orElse(true);
            if (property.isPresent()) {
                String name = Xml.childText(property.get(), "name").orElse("");
                holds &= name.startsWith("!") && Xml.childText(property.get(), "value").isEmpty();
            }
            return holds;
        }

        private static Map<String, String> properties(Element owner) {
            Map<String, String> properties = new LinkedHashMap<>();
            Xml.path(owner, "properties").ifPresent(element -> {
                for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
                    if (node instanceof Element property) {
                        String name = property.getLocalName() != null ? property.getLocalName()
                                : property.getTagName();
                        properties.put(name, property.getTextContent().strip());
                    }
                }
            });
            return Collections.unmodifiableMap(properties);
        }

        /// The `<dependency>` elements under the given path of a project or profile.
        private static List<Dependency> dependencies(Element owner, String... path) {
            List<Dependency> dependencies = new ArrayList<>();
            for (Element element : Xml.path(owner, path).map(d -> Xml.children(d, "dependency"))
                    .orElse(List.of())) {
                dependencies.add(Dependency.parse(element));
            }
            return List.copyOf(dependencies);
        }
    }

    /// Whether a profile's `<jdk>` condition holds for a JDK version: a prefix (`17`, `1.8`), a negated prefix (`!1.8`)
    /// or a range of versions (`[11,)`, `(,1.8]`, `[9,17)`), the versions compared by their numbers.
    static boolean jdkHolds(String condition, String jdk) {
        String spec = condition.strip();
        if (spec.startsWith("!")) {
            return !jdkHolds(spec.substring(1), jdk);
        }
        if (!spec.startsWith("[") && !spec.startsWith("(")) {
            return jdk.startsWith(spec);
        }
        boolean lowerInclusive = spec.startsWith("[");
        boolean upperInclusive = spec.endsWith("]");
        String[] bounds = spec.substring(1, spec.length() - 1).split(",", -1);
        String lower = bounds[0].strip();
        String upper = bounds.length > 1 ? bounds[1].strip() : lower;
        if (!lower.isEmpty()) {
            int c = compareJdk(jdk, lower);
            if (c < 0 || (c == 0 && !lowerInclusive)) {
                return false;
            }
        }
        if (!upper.isEmpty()) {
            int c = compareJdk(jdk, upper);
            return c < 0 || (c == 0 && upperInclusive);
        }
        return true;
    }

    private static int compareJdk(String a, String b) {
        String[] x = a.split("[._-]");
        String[] y = b.split("[._-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(number(i < x.length ? x[i] : "0"), number(i < y.length ? y[i] : "0"));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static int number(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String text(Element parent, String localName) {
        return Xml.childText(parent, localName).orElse(null);
    }
}
