package io.quarkiverse.almanac.api;

import io.quarkiverse.almanac.classes.ClassesReader;
import io.quarkiverse.almanac.config.VersionJars;
import io.quarkiverse.almanac.maven.MavenRepo;
import io.quarkiverse.almanac.maven.RemoteJar;
import io.quarkiverse.almanac.model.ApiSnapshot;
import io.quarkiverse.almanac.model.ArtifactClasses;
import io.quarkiverse.almanac.model.BomSnapshot;
import io.quarkiverse.almanac.model.ClassesSnapshot;
import io.quarkiverse.almanac.model.ManagedDep;
import io.quarkiverse.almanac.model.MemberApi;
import io.quarkiverse.almanac.util.Log;
import io.quarkiverse.almanac.util.Parallel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.zip.CRC32;

/// Extracts the API of releases ([ApiReader]) from the class files of their `io.quarkus` jars in [ApiScope], reading
/// each distinct class file once and as few bytes from the repository as it can. A class file is known by its entry
/// name, CRC-32 and size, from its jar's central directory: it is parsed once, and the result serves every release
/// whose jar has the same file. The bytes of the others come, the first that has them:
///
/// 1. from the jar cached whole, in the work folder's cache or one of the other caches;
/// 2. from the entry cached in `.entries/` by one of them;
/// 3. from another cached version of the same artifact that has the same class file, found through that version's
///    cached central directory;
/// 4. from the repository, into the work folder's cache ([RemoteJar#readEntries]): a jar of at most 2 MB whole, a
///    larger one with a few range requests.
///
/// A release is all or nothing: a class file that cannot be read, or whose bytes do not match the central directory,
/// fails it. [#plan] tells, from the caches alone and without a request, what [#extract] would read; both take the
/// releases in the order they are given, each knowing the class files of those before. Thread-safe; the jars of a
/// release are read in parallel.
///
/// The plan reads no jar that a cache holds whole: such a jar costs no request, whatever its class files. It reads
/// the central directories of the others, whose class files it knows when an earlier release planned in the same run
/// had them in such a jar, or a cached version of the same jar has them. A class file that moved from a jar cached
/// whole to one that is not counts as one to fetch: the plan may only overstate the requests.
public final class ApiExtractor {

    /// A class file as the central directory of its jar records it.
    record Key(String entry, long crc, long size) {
    }

    /// A cached version of an artifact that holds the bytes of a class file: the cache (0 for the work folder's, then
    /// the others in order) and the version.
    private record Source(int cache, String version) {
    }

    /// What extracting one release reads ([#plan]).
    ///
    /// @param classFiles      the release's class files in scope
    /// @param known           of those not in a jar cached whole, those read for an earlier release
    /// @param local           those whose bytes the caches hold in the jar of the release (whole, or in `.entries/`)
    /// @param elsewhere       those whose bytes the caches hold in another version of the jar
    /// @param toFetch         those to fetch
    /// @param unknown         those of jars whose central directory no cache holds
    /// @param wholeJars       jars downloaded whole
    /// @param wholeBytes      their size
    /// @param rangedReads     range requests
    /// @param rangedBytes     the bytes they fetch
    /// @param uncachedIndexes jars whose central directory no cache holds: each needs a request for its end, and at
    ///                        least one more for its classes, which [#requests()] counts
    public record Plan(String version, int classFiles, int known, int local, int elsewhere, int toFetch, int unknown,
            int wholeJars, long wholeBytes, int rangedReads, long rangedBytes, int uncachedIndexes) {

        /// The requests to send: whole jars, range requests, and two for each jar without a cached central directory.
        public int requests() {
            return wholeJars + rangedReads + 2 * uncachedIndexes;
        }

        /// The bytes those requests fetch (the jars without a cached central directory left out).
        public long bytes() {
            return wholeBytes + rangedBytes;
        }

        /// The sum of two plans, under another name.
        public Plan plus(String name, Plan other) {
            return new Plan(name, classFiles + other.classFiles, known + other.known, local + other.local,
                    elsewhere + other.elsewhere, toFetch + other.toFetch, unknown + other.unknown,
                    wholeJars + other.wholeJars,
                    wholeBytes + other.wholeBytes, rangedReads + other.rangedReads, rangedBytes + other.rangedBytes,
                    uncachedIndexes + other.uncachedIndexes);
        }

        /// A plan of nothing.
        public static Plan empty(String name) {
            return new Plan(name, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /// The outcome of [#extract].
    ///
    /// @param snapshot   the release's API, null when it would need requests while offline
    /// @param notLocal   the jars whose central directory or class files only the repository has, when offline
    /// @param classFiles the class files in scope
    /// @param read       of them, those read now (the others were read for an earlier release)
    /// @param fetched    of those read, those fetched from the repository
    public record Extraction(ApiSnapshot snapshot, List<String> notLocal, int classFiles, int read, int fetched) {
    }

    /// One jar of a release: its class files in scope and where their bytes come from.
    private static final class JarWork {
        private final String artifactId;
        private final String version;
        private final List<String> entries;
        /// The jar in the work folder's cache, then in the other caches; dropped once read, with the jar's bytes.
        private List<RemoteJar> caches;
        /// The first of them whose central directory is cached, null when none is.
        private RemoteJar indexed;
        /// For a plan: a cache holds the jar whole, so its class files are all local and were not looked at.
        private boolean whole;
        private final Map<String, Key> keys = new HashMap<>();
        private int known;
        /// Entry → the jar of the release's version that holds its bytes.
        private final Map<String, RemoteJar> local = new LinkedHashMap<>();
        /// Entry → another version that holds its bytes.
        private final Map<String, Source> elsewhere = new LinkedHashMap<>();
        private final List<String> fetch = new ArrayList<>();

        JarWork(String artifactId, String version, List<String> entries, List<RemoteJar> caches) {
            this.artifactId = artifactId;
            this.version = version;
            this.entries = entries;
            this.caches = caches;
        }

        @Override
        public String toString() {
            return artifactId + ":" + version;
        }
    }

    private final List<MavenRepo> repos;
    private final int parallelism;
    /// The parsed class files, by key.
    private final Map<Key, ApiReader.Parsed> parsed = new ConcurrentHashMap<>();
    /// The class files that the releases planned so far read.
    private final Set<Key> planned = ConcurrentHashMap.newKeySet();
    /// Per artifactId, the class files that its cached versions hold: built on first need, from local files only.
    private final Map<String, Map<Key, Source>> otherVersions = new ConcurrentHashMap<>();
    private final Set<String> warnedBundles = ConcurrentHashMap.newKeySet();
    private final Map<String, String> strings = new ConcurrentHashMap<>();
    private final Map<MemberApi, MemberApi> members = new ConcurrentHashMap<>();

    /// @param repo        the repository, cached in the work folder: what is fetched goes there
    /// @param others      the same repository over other caches, which are only read
    /// @param parallelism how many jars of a release are read at the same time
    public ApiExtractor(MavenRepo repo, List<MavenRepo> others, int parallelism) {
        List<MavenRepo> all = new ArrayList<>();
        all.add(repo);
        all.addAll(others);
        this.repos = List.copyOf(all);
        this.parallelism = Math.max(1, parallelism);
    }

    /// What [#extract] would read for a release, after the releases planned before it: no request is sent, nothing
    /// is written.
    ///
    /// @throws IOException when the release cannot be extracted: a class of its classes snapshot that its jar does not
    ///                     have, a jar its BOM does not manage, an unreadable cache
    public Plan plan(String version, ClassesSnapshot classes, BomSnapshot bom) throws IOException {
        List<JarWork> jars = analyze(version, classes, bom, planned::contains, true);
        int known = 0;
        int local = 0;
        int elsewhere = 0;
        int toFetch = 0;
        int unknown = 0;
        int wholeJars = 0;
        long wholeBytes = 0;
        int rangedReads = 0;
        long rangedBytes = 0;
        int uncachedIndexes = 0;
        for (JarWork jar : jars) {
            if (jar.whole) {
                local += jar.entries.size();
                continue;
            }
            if (jar.indexed == null) {
                uncachedIndexes++;
                unknown += jar.entries.size();
                continue;
            }
            known += jar.known;
            local += jar.local.size();
            elsewhere += jar.elsewhere.size();
            toFetch += jar.fetch.size();
            if (!jar.fetch.isEmpty()) {
                RemoteJar work = jar.caches.getFirst();
                if (!work.indexCached()) {
                    work.adoptIndex(jar.indexed);
                }
                RemoteJar.Fetch fetch = work.plan(jar.fetch);
                if (fetch.whole()) {
                    wholeJars++;
                    wholeBytes += fetch.bytes();
                } else {
                    rangedReads += fetch.requests();
                    rangedBytes += fetch.bytes();
                }
            }
            planned.addAll(jar.keys.values());
        }
        int classFiles = jars.stream().mapToInt(jar -> jar.entries.size()).sum();
        return new Plan(version, classFiles, known, local, elsewhere, toFetch, unknown, wholeJars, wholeBytes,
                rangedReads, rangedBytes, uncachedIndexes);
    }

    /// Extracts the API of a release. Offline, a release whose central directories or class files are not all cached
    /// is not extracted: the result names its jars that only the repository has.
    ///
    /// @throws IOException when a request fails, or the release cannot be extracted: a class file that cannot be read
    ///                     or parsed, or whose bytes do not match its central directory, a class of its classes
    ///                     snapshot that its jar does not have, a jar its BOM does not manage
    public Extraction extract(String version, ClassesSnapshot classes, BomSnapshot bom, boolean offline)
            throws IOException {
        List<JarWork> jars = analyze(version, classes, bom, parsed::containsKey, false);
        int classFiles = jars.stream().mapToInt(jar -> jar.entries.size()).sum();
        List<String> notLocal = new ArrayList<>();
        for (JarWork jar : jars) {
            if (jar.indexed == null || !jar.fetch.isEmpty()) {
                notLocal.add(jar.artifactId);
            }
        }
        if (offline && !notLocal.isEmpty()) {
            return new Extraction(null, notLocal, classFiles, 0, 0);
        }
        // a jar whose central directory no cache has: its end is fetched first
        List<JarWork> indexed = Parallel.map(jars, parallelism, jar -> {
            JarWork work = jar.indexed != null ? jar
                    : analyzeJar(jar.artifactId, jar.version, jar.entries, parsed::containsKey, true, false);
            if (work.indexed == null) {
                throw new IOException(work + " does not exist, but the release's classes list " + work.entries.size()
                        + " classes of it");
            }
            return work;
        });
        List<int[]> counts = Parallel.map(indexed, parallelism, this::read);
        SortedMap<String, Map<String, ApiReader.Parsed>> byJar = new TreeMap<>();
        for (JarWork jar : indexed) {
            Map<String, ApiReader.Parsed> classFilesOfJar = new HashMap<>();
            for (String entry : jar.entries) {
                ApiReader.Parsed known = parsed.get(jar.keys.get(entry));
                if (known == null) {
                    throw new IOException(jar + ": " + entry + " was not read");
                }
                classFilesOfJar.put(ClassesReader.className(entry), known);
            }
            byJar.put(jar.artifactId, classFilesOfJar);
        }
        int read = counts.stream().mapToInt(count -> count[0]).sum();
        int fetched = counts.stream().mapToInt(count -> count[1]).sum();
        return new Extraction(ApiReader.snapshot(version, byJar), List.of(), classFiles, read, fetched);
    }

    /// The jars of a release with their class files in scope, and where the bytes of each come from.
    ///
    /// @param planning whether a jar cached whole is left as it is (see [#analyzeJar])
    private List<JarWork> analyze(String version, ClassesSnapshot classes, BomSnapshot bom, Predicate<Key> known,
            boolean planning) throws IOException {
        Map<String, String> jarVersions = new HashMap<>();
        for (ManagedDep dep : bom.deps()) {
            if (VersionJars.isQuarkusJar(dep)) {
                jarVersions.put(dep.id().substring(VersionJars.GROUP_ID.length() + 1), dep.v());
            }
        }
        List<String[]> jars = new ArrayList<>();
        Map<String, List<String>> entriesOf = new HashMap<>();
        for (ArtifactClasses artifact : classes.artifacts()) {
            warnAboutBundles(version, artifact);
            List<String> entries = new ArrayList<>();
            for (String name : artifact.classes()) {
                if (ApiScope.covers(artifact.id(), name)) {
                    entries.add(ClassesReader.entryName(name));
                }
            }
            if (entries.isEmpty()) {
                continue;
            }
            String jarVersion = jarVersions.get(artifact.id());
            if (jarVersion == null) {
                throw new IOException(version + ": the classes list " + artifact.id() + ", which the release's BOM "
                        + "does not manage");
            }
            jars.add(new String[] {artifact.id(), jarVersion});
            entriesOf.put(artifact.id(), entries);
        }
        return Parallel.map(jars, parallelism, jar -> analyzeJar(jar[0], jar[1], entriesOf.get(jar[0]), known,
                false, planning));
    }

    /// Where the bytes of the class files of one jar come from: the first cache that holds the jar whole has them all;
    /// without one, the first cache with its central directory tells their keys, and each one's bytes come from the
    /// `.entries/` of a cache, another cached version, or the repository.
    ///
    /// @param fetchIndex whether the jar's central directory may be fetched when no cache holds it
    /// @param planning   whether a jar cached whole is left at that, its central directory unread
    private JarWork analyzeJar(String artifactId, String version, List<String> entries, Predicate<Key> known,
            boolean fetchIndex, boolean planning) throws IOException {
        List<RemoteJar> caches = new ArrayList<>();
        for (MavenRepo repo : repos) {
            caches.add(repo.jar(VersionJars.GROUP_ID, artifactId, version));
        }
        JarWork jar = new JarWork(artifactId, version, entries, caches);
        RemoteJar whole = null;
        for (RemoteJar cached : caches) {
            if (cached.wholeCached()) {
                whole = cached;
                break;
            }
        }
        if (whole != null && planning) {
            jar.whole = true;
            return jar;
        }
        jar.indexed = whole;
        for (int c = 0; jar.indexed == null && c < caches.size(); c++) {
            if (caches.get(c).indexCached() && caches.get(c).exists()) {
                jar.indexed = caches.get(c);
            }
        }
        if (jar.indexed == null && fetchIndex && caches.getFirst().exists()) {
            jar.indexed = caches.getFirst();
        }
        if (jar.indexed == null) {
            return jar;
        }
        // which cache holds the bytes of which entry: the whole jar, else the .entries/ of each, one listing per cache;
        // a cache with entries but no index of its own (entries fetched with the index of another cache, before that
        // index was kept with them) reads them with the index found
        List<Set<String>> held = new ArrayList<>();
        if (whole != null) {
            caches = List.of(whole);
            held.add(Set.copyOf(entries));
        } else {
            for (RemoteJar cached : caches) {
                if (!cached.indexCached() && cached.hasCachedEntries()) {
                    cached.adoptIndex(jar.indexed);
                }
                held.add(cached.cachedEntries(entries));
            }
        }
        for (String entry : entries) {
            Optional<RemoteJar.EntryKey> entryKey = jar.indexed.key(entry);
            if (entryKey.isEmpty()) {
                throw new IOException(jar + " has no " + entry + ", which the release's classes list");
            }
            Key key = new Key(entry, entryKey.get().crc(), entryKey.get().size());
            jar.keys.put(entry, key);
            if (known.test(key)) {
                jar.known++;
                continue;
            }
            RemoteJar holder = null;
            for (int c = 0; holder == null && c < held.size(); c++) {
                if (held.get(c).contains(entry)) {
                    holder = caches.get(c);
                }
            }
            if (holder != null) {
                jar.local.put(entry, holder);
                continue;
            }
            Source source = otherVersions(artifactId).get(key);
            if (source != null) {
                jar.elsewhere.put(entry, source);
            } else {
                jar.fetch.add(entry);
            }
        }
        return jar;
    }

    /// Reads and parses the class files of a jar that no earlier release had.
    ///
    /// @return how many class files were read, and how many of them fetched
    private int[] read(JarWork jar) throws IOException {
        int read = 0;
        List<String> fetch = new ArrayList<>(jar.fetch);
        for (Map.Entry<String, RemoteJar> entry : jar.local.entrySet()) {
            Optional<byte[]> bytes = entry.getValue().readCached(entry.getKey());
            if (bytes.isEmpty()) {
                // a cached entry of the wrong size or CRC: the repository has the right one
                fetch.add(entry.getKey());
                continue;
            }
            remember(jar, entry.getKey(), bytes.get());
            read++;
        }
        Map<Source, List<String>> bySource = new LinkedHashMap<>();
        jar.elsewhere.forEach((entry, source) -> bySource.computeIfAbsent(source, s -> new ArrayList<>()).add(entry));
        for (Map.Entry<Source, List<String>> source : bySource.entrySet()) {
            RemoteJar other = withIndex(source.getKey().cache(), jar.artifactId, source.getKey().version());
            if (other == null) {
                throw new IOException(jar + ": no cache has the index of " + jar.artifactId + ":"
                        + source.getKey().version() + " any longer");
            }
            for (String entry : source.getValue()) {
                byte[] bytes = other.readCached(entry).orElseThrow(() -> new IOException(other + ": " + entry
                        + " is no longer in the cache"));
                remember(jar, entry, bytes);
                read++;
            }
        }
        if (!fetch.isEmpty()) {
            RemoteJar work = jar.caches.getFirst();
            if (!work.indexCached()) {
                work.adoptIndex(jar.indexed);
            }
            Map<String, byte[]> fetched = work.readEntries(fetch);
            for (String entry : fetch) {
                byte[] bytes = fetched.get(entry);
                if (bytes == null) {
                    throw new IOException(jar + ": " + entry + " could not be read");
                }
                remember(jar, entry, bytes);
                read++;
            }
        }
        // the jar's bytes are no longer needed: the others of the release need the memory
        jar.caches = null;
        jar.indexed = null;
        jar.local.clear();
        return new int[] {read, fetch.size()};
    }

    /// Parses a class file whose bytes match its central directory, and keeps the result by its key. A class file that
    /// declares another class than its entry names is not API.
    private void remember(JarWork jar, String entry, byte[] bytes) throws IOException {
        Key key = jar.keys.get(entry);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        if (bytes.length != key.size() || crc.getValue() != key.crc()) {
            throw new IOException(jar + ": " + entry + " does not match its central directory (" + bytes.length
                    + " bytes, CRC " + Long.toHexString(crc.getValue()) + ")");
        }
        ApiReader.Parsed result;
        try {
            result = ApiReader.parse(bytes);
        } catch (IllegalArgumentException e) {
            throw new IOException(jar + ": " + entry + ": " + e.getMessage(), e);
        } catch (Error e) {
            // a class file the parser cannot cope with at all (a recursion as deep as the bytes say): it fails its
            // release, named, like any unreadable class file, and the run goes on
            throw new IOException(jar + ": " + entry + ": not a readable class file (" + e + ")", e);
        }
        String expected = entry.substring(0, entry.length() - ".class".length());
        if (!result.name().equals(expected)) {
            Log.warn("api: %s: %s declares the class %s; it is not API", jar, entry, result.name());
            result = new ApiReader.Parsed(expected, null, false, null, List.of());
        }
        parsed.putIfAbsent(key, shared(result));
    }

    /// The parsed class file with shared strings and member records: class files of many releases differ in a few
    /// members only.
    private ApiReader.Parsed shared(ApiReader.Parsed result) {
        List<MemberApi> sharedMembers = new ArrayList<>(result.members().size());
        for (MemberApi member : result.members()) {
            sharedMembers.add(members.computeIfAbsent(member, m -> new MemberApi(string(m.id()), string(m.v()))));
        }
        return new ApiReader.Parsed(string(result.name()), result.outer() == null ? null : string(result.outer()),
                result.api(), result.value() == null ? null : string(result.value()), List.copyOf(sharedMembers));
    }

    private String string(String text) {
        return strings.computeIfAbsent(text, t -> t);
    }

    /// The class files in scope that the cached versions of an artifact hold, whole or in `.entries/`, by key: built
    /// once per artifactId, from local files only.
    private Map<Key, Source> otherVersions(String artifactId) throws IOException {
        Map<Key, Source> index = otherVersions.get(artifactId);
        if (index != null) {
            return index;
        }
        Map<Key, Source> built = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (int c = 0; c < repos.size(); c++) {
            for (String version : repos.get(c).cachedJarVersions(VersionJars.GROUP_ID, artifactId)) {
                RemoteJar jar = withIndex(c, artifactId, version);
                if (jar == null || !jar.exists()) {
                    continue;
                }
                List<String> inScope = new ArrayList<>();
                for (String entry : jar.entryNames()) {
                    String name = ClassesReader.className(entry);
                    if (name != null && ApiScope.covers(artifactId, name)) {
                        inScope.add(entry);
                    }
                }
                for (String entry : jar.cachedEntries(inScope)) {
                    RemoteJar.EntryKey key = jar.key(entry).orElseThrow();
                    built.putIfAbsent(new Key(names.computeIfAbsent(entry, e -> e), key.crc(), key.size()),
                            new Source(c, version));
                }
            }
        }
        Map<Key, Source> previous = otherVersions.putIfAbsent(artifactId, built);
        return previous != null ? previous : built;
    }

    /// A version of a jar in one cache, with an index to read its cached entries with: its own, or, for entries
    /// without one (fetched with the index of another cache, before that index was kept with them), the index of the
    /// same jar in another cache, adopted in memory; null when no cache has the index.
    private RemoteJar withIndex(int cache, String artifactId, String version) throws IOException {
        RemoteJar jar = repos.get(cache).jar(VersionJars.GROUP_ID, artifactId, version);
        if (jar.indexCached()) {
            return jar;
        }
        for (MavenRepo repo : repos) {
            RemoteJar other = repo.jar(VersionJars.GROUP_ID, artifactId, version);
            if (other.indexCached() && other.exists()) {
                jar.adoptIndex(other);
                return jar;
            }
        }
        return null;
    }

    /// Warns, once per artifactId, about a jar outside [ApiScope]'s list with many classes outside `io.quarkus` or in
    /// shaded packages: a jar that bundles other projects' classes, which the scope may need to leave out.
    private void warnAboutBundles(String version, ArtifactClasses artifact) {
        if (ApiScope.isNamed(artifact.id())) {
            return;
        }
        int foreign = ApiScope.foreignClasses(artifact.classes());
        if (foreign > ApiScope.BUNDLE_WARNING && warnedBundles.add(artifact.id())) {
            Log.warn("api %s: %s has %d classes outside io.quarkus or in shaded packages, likely copies of another "
                    + "project's: a new bundle that ApiScope may need to leave out", version, artifact.id(), foreign);
        }
    }
}
