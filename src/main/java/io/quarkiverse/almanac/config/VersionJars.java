package io.quarkiverse.almanac.config;

import io.quarkiverse.almanac.maven.BomParser;
import io.quarkiverse.almanac.maven.MavenRepo;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.Parallel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/// The `io.quarkus` jars of one Quarkus release: every `io.quarkus` entry of `quarkus-bom` with type `jar` and no
/// classifier, at the version given by that BOM entry. This is the input of every [ConfigReader] and of the extension
/// metadata reader.
///
/// Indexing (see [#index()]) fetches the central directory of every jar in parallel, bounded by the HTTP concurrency,
/// and resolves Maven [relocations][#relocations()]. All query methods index on first use; calling [#index()] up front
/// only makes the cost explicit. Instances are thread-safe.
///
/// The BOM's third-party jars are available as [#libraries()]; they are not indexed or probed.
public final class VersionJars {

    /// Group whose jars are considered.
    public static final String GROUP_ID = "io.quarkus";

    private static final String CLASS_SUFFIX = ".class";

    /// Finds the `groupId:artifactId` an artifact was relocated to, if any.
    @FunctionalInterface
    public interface RelocationResolver {
        Optional<String> relocationOf(JarSource jar) throws IOException;
    }

    private record Indexed(List<JarSource> existing, Map<String, String> relocations) {
    }

    private record Probe(JarSource jar, boolean exists, String relocation) {
    }

    private final String quarkusVersion;
    private final List<JarSource> candidates;
    private final List<JarSource> libraries;
    private final RelocationResolver relocationResolver;
    private final int parallelism;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Indexed indexed;
    private volatile Map<String, JarSource> classIndex;

    /// @param quarkusVersion     the release
    /// @param candidates         the jars listed by the BOM, existing or not
    /// @param relocationResolver consulted for candidates whose jar is missing or holds no classes
    /// @param parallelism        maximum number of jars indexed or read at the same time
    public VersionJars(String quarkusVersion, List<? extends JarSource> candidates,
            RelocationResolver relocationResolver, int parallelism) {
        this(quarkusVersion, candidates, List.of(), relocationResolver, parallelism);
    }

    /// @param quarkusVersion     the release
    /// @param candidates         the {@value #GROUP_ID} jars listed by the BOM, existing or not
    /// @param libraries          the third-party jars listed by the BOM, existing or not (see [#libraries()])
    /// @param relocationResolver consulted for candidates whose jar is missing or holds no classes
    /// @param parallelism        maximum number of jars indexed or read at the same time
    public VersionJars(String quarkusVersion, List<? extends JarSource> candidates,
            List<? extends JarSource> libraries, RelocationResolver relocationResolver, int parallelism) {
        this.quarkusVersion = quarkusVersion;
        this.candidates = candidates.stream().sorted(Comparator.comparing(JarSource::artifactId))
                .map(JarSource.class::cast).toList();
        this.libraries = libraries.stream()
                .sorted(Comparator.comparing(JarSource::groupId).thenComparing(JarSource::artifactId))
                .map(JarSource.class::cast).toList();
        this.relocationResolver = relocationResolver;
        this.parallelism = parallelism;
    }

    /// The jars of a release as listed by its BOM snapshot, read from the given repository: the {@value #GROUP_ID} jars
    /// and, as [#libraries()], the third-party ones.
    public static VersionJars of(BomSnapshot bom, MavenRepo repo) {
        List<JarSource> jars = new ArrayList<>();
        List<JarSource> libraries = new ArrayList<>();
        for (ManagedDep dep : bom.deps()) {
            String[] parts = dep.id().split(":");
            if (isQuarkusJar(dep)) {
                jars.add(new RemoteJarSource(repo.jar(GROUP_ID, parts[1], dep.v())));
            } else if (parts.length == 2 && isUsableVersion(dep.v())) {
                // lazy: no request until a reader opens it
                libraries.add(new RemoteJarSource(repo.jar(parts[0], parts[1], dep.v())));
            } else if (parts.length == 2) {
                // e.g. org.jboss:jdk-misc:${version.jdk-misc} in the 3.2.10 - 3.2.12 BOMs
                Log.debug("%s: %s has no usable version (%s), skipped", bom.version(), dep.id(), dep.v());
            }
        }
        RelocationResolver relocations = jar -> {
            Optional<MavenRepo.Download> pom = repo.artifact(jar.groupId(), jar.artifactId(), jar.version(), "pom");
            return pom.isEmpty() ? Optional.empty() : BomParser.relocation(pom.get().content());
        };
        return new VersionJars(bom.version(), jars, libraries, relocations, repo.http().concurrency());
    }

    /// Whether a BOM entry is one of the {@value #GROUP_ID} jars [#of] reads: groupId {@value #GROUP_ID}, type `jar`
    /// and no classifier, which an id of exactly two parts has. Subgroups such as `io.quarkus.arc` are not part of it.
    public static boolean isQuarkusJar(ManagedDep dep) {
        String[] parts = dep.id().split(":");
        return parts.length == 2 && parts[0].equals(GROUP_ID);
    }

    /// Whether a third-party version from a BOM can be part of a repository path: not empty and free of unresolved
    /// `${…}` placeholders and other characters that URIs do not allow.
    static boolean isUsableVersion(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c <= ' ' || "${}<>\"|\\^`%#?/".indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    /// The BOM-managed jars outside {@value #GROUP_ID} (type `jar`, no classifier, with a usable version), existing or
    /// not, sorted by groupId and artifactId. Unlike the {@value #GROUP_ID} jars they are neither indexed nor probed: a
    /// reader that needs one of their classes (e.g. a third-party enum used by a config property) opens the jars it
    /// chooses, each of which fetches its central directory on first use.
    public List<JarSource> libraries() {
        return libraries;
    }

    /// The Quarkus release these jars belong to.
    public String quarkusVersion() {
        return quarkusVersion;
    }

    /// Number of jars listed by the BOM, including the ones that do not exist.
    public int candidateCount() {
        return candidates.size();
    }

    /// Maximum number of jars indexed or read at the same time (the HTTP concurrency for real repositories).
    public int parallelism() {
        return parallelism;
    }

    /// Fetches the central directory of every listed jar in parallel and resolves relocations. Idempotent; called
    /// implicitly by the query methods.
    public void index() throws IOException {
        indexed();
    }

    /// The existing jars, sorted by artifactId.
    public List<JarSource> jars() throws IOException {
        return indexed().existing();
    }

    /// The existing jars that contain an entry with exactly this name, sorted by artifactId.
    public List<JarSource> jarsContaining(String entry) throws IOException {
        List<JarSource> result = new ArrayList<>();
        for (JarSource jar : jars()) {
            if (jar.contains(entry)) {
                result.add(jar);
            }
        }
        return result;
    }

    /// Reads one entry from every jar that contains it, in parallel.
    ///
    /// @return entry content per jar, in [#jarsContaining] order
    public Map<JarSource, byte[]> readAll(String entry) throws IOException {
        List<JarSource> containing = jarsContaining(entry);
        List<byte[]> contents = Parallel.map(containing, parallelism, jar -> jar.read(entry)
                .orElseThrow(() -> new IOException(entry + " disappeared from " + jar.artifactId())));
        Map<JarSource, byte[]> result = new LinkedHashMap<>();
        for (int i = 0; i < containing.size(); i++) {
            result.put(containing.get(i), contents.get(i));
        }
        return result;
    }

    /// The jar that contains a class.
    ///
    /// @param internalClassName JVM internal name, e.g. `io/quarkus/runtime/ApplicationConfig` or
    ///                          `io/quarkus/runtime/LogConfig$Level`
    /// @return the first jar (by artifactId) with that class; empty when no jar of the release has it
    public Optional<JarSource> jarOf(String internalClassName) throws IOException {
        return Optional.ofNullable(classIndex().get(internalClassName));
    }

    /// The class file bytes of a class of the release (see [#jarOf]); empty when no jar has it.
    public Optional<byte[]> classBytes(String internalClassName) throws IOException {
        Optional<JarSource> jar = jarOf(internalClassName);
        return jar.isEmpty() ? Optional.empty() : jar.get().read(internalClassName + CLASS_SUFFIX);
    }

    /// Relocations of listed artifacts: artifactId → `groupId:artifactId` declared by the artifact's pom
    /// (`<distributionManagement><relocation>`), sorted by artifactId. Only artifacts whose jar is missing or holds no
    /// classes are checked: relocated artifacts publish no jar or, in some releases (e.g. 3.9.x), an empty one. Such an
    /// empty jar still counts as existing in [#jars()].
    public Map<String, String> relocations() throws IOException {
        return indexed().relocations();
    }

    private Indexed indexed() throws IOException {
        Indexed current = indexed;
        if (current != null) {
            return current;
        }
        lock.lock();
        try {
            if (indexed == null) {
                indexed = probeAll();
            }
            return indexed;
        } finally {
            lock.unlock();
        }
    }

    private Indexed probeAll() throws IOException {
        List<Probe> probes = Parallel.map(candidates, parallelism, jar -> {
            boolean exists = jar.exists();
            // relocated artifacts publish no jar, or (3.9.x) an empty one: check the pom only in those cases
            boolean mayBeRelocated = !exists || jar.entryNames().stream().noneMatch(e -> e.endsWith(CLASS_SUFFIX));
            String relocation = mayBeRelocated ? relocationResolver.relocationOf(jar).orElse(null) : null;
            return new Probe(jar, exists, relocation);
        });
        List<JarSource> existing = new ArrayList<>();
        Map<String, String> relocations = new TreeMap<>();
        for (Probe probe : probes) {
            if (probe.exists()) {
                existing.add(probe.jar());
            } else if (probe.relocation() == null) {
                Log.debug("%s: %s:%s has no jar", quarkusVersion, probe.jar().artifactId(), probe.jar().version());
            }
            if (probe.relocation() != null) {
                relocations.put(probe.jar().artifactId(), probe.relocation());
            }
        }
        return new Indexed(List.copyOf(existing), Collections.unmodifiableMap(relocations));
    }

    private Map<String, JarSource> classIndex() throws IOException {
        Map<String, JarSource> current = classIndex;
        if (current != null) {
            return current;
        }
        lock.lock();
        try {
            if (classIndex == null) {
                classIndex = buildClassIndex();
            }
            return classIndex;
        } finally {
            lock.unlock();
        }
    }

    private Map<String, JarSource> buildClassIndex() throws IOException {
        Map<String, JarSource> index = new HashMap<>();
        int duplicates = 0;
        for (JarSource jar : jars()) {
            for (String entry : jar.entryNames()) {
                if (entry.endsWith(CLASS_SUFFIX) && !entry.startsWith("META-INF/")) {
                    String className = entry.substring(0, entry.length() - CLASS_SUFFIX.length());
                    if (index.putIfAbsent(className, jar) != null) {
                        duplicates++;
                    }
                }
            }
        }
        if (duplicates > 0) {
            Log.debug("%s: %d classes exist in more than one jar; the first jar by artifactId wins",
                    quarkusVersion, duplicates);
        }
        return Collections.unmodifiableMap(index);
    }
}
