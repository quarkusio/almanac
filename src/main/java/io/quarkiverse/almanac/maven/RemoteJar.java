package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.util.AtomicFiles;
import io.quarkiverse.almanac.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipException;

/// A jar in a remote repository, indexed and read lazily with range requests and cached on disk.
///
/// - The first access fetches the last [Options#tailSize()] bytes. When that is the whole file it is cached
///   as the full jar; otherwise the end-of-central-directory record is parsed and the central directory (from the
///   tail, or fetched separately) is cached.
/// - Reading an entry of a jar of at most [Options#fullJarMax()] bytes downloads and caches the full jar;
///   larger jars read the entry's local header and data with one range request (plus slack for the local extra
///   field), inflate it, verify its CRC-32 and cache the entry. [#readEntries] reads several entries with few requests.
/// - ZIP64 archives are downloaded in full. A 404 is cached as a marker file.
///
/// Cache files live next to `cacheFile` (the full jar's path): `.404`, `.cd` and `.entries/`. A cached jar is indexed
/// from its end, and read whole only when an entry is read. A jar can [adopt][#adoptIndex] the index of another cache;
/// once it writes an entry next to it, it keeps that index as its `.cd`, so that later reads find the entry. Instances
/// are thread-safe.
public final class RemoteJar {

    /// @param tailSize   bytes fetched from the end of the file on first access
    /// @param fullJarMax jars up to this size are downloaded whole when an entry is read
    public record Options(int tailSize, long fullJarMax) {
        public static final int DEFAULT_TAIL_SIZE = 64 * 1024;
        public static final long DEFAULT_FULL_JAR_MAX = 2 * 1024 * 1024;
        public static final Options DEFAULT = new Options(DEFAULT_TAIL_SIZE, DEFAULT_FULL_JAR_MAX);
    }

    /// What reading entries would request, from the index alone ([#plan]).
    ///
    /// @param requests the requests [#readEntries] sends for them
    /// @param bytes    the bytes those requests fetch
    /// @param whole    whether the jar is downloaded whole
    public record Fetch(int requests, long bytes, boolean whole) {
        public static final Fetch NONE = new Fetch(0, 0, false);
    }

    /// The CRC-32 and the uncompressed size of an entry, as the central directory records them.
    public record EntryKey(long crc, long size) {
    }

    /// Extra bytes requested after an entry's data to cover a local extra field larger than the central one.
    private static final int LOCAL_EXTRA_SLACK = 1024;
    /// [#readEntries] reads entries closer than this with one request, the bytes between them included.
    static final int MAX_GAP = 64 * 1024;
    /// [#readEntries] sends at most this many range requests, joining the closest runs of entries.
    static final int MAX_RUNS = 8;
    /// Bytes read from the end of a cached jar to index it: the end records with the longest comment.
    private static final int LOCAL_TAIL = 0xFFFF + 22 + 20 + 56;
    private static final int CD_CACHE_MAGIC = 0x41434431; // "ACD1"

    private record Index(boolean exists, long size, Map<String, ZipIndex.Entry> entries) {
        static final Index MISSING = new Index(false, 0, Map.of());
    }

    /// Entries read with one range request: from the first one's local header to the last one's data, plus slack.
    private record Run(long start, long end, List<ZipIndex.Entry> entries) {
        long length() {
            return end - start;
        }
    }

    private final String groupId;
    private final String artifactId;
    private final String version;
    private final RangeFetcher fetcher;
    private final Path cacheFile;
    private final Options options;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Index index;
    private volatile byte[] content;
    /// The jar of the other cache whose index this one adopted, until this cache keeps the index as its own `.cd`.
    private volatile RemoteJar adoptedFrom;

    /// @param fetcher   access to the remote bytes
    /// @param cacheFile where the full jar is (or would be) cached; the other cache files are its siblings
    public RemoteJar(String groupId, String artifactId, String version, RangeFetcher fetcher, Path cacheFile,
            Options options) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.fetcher = fetcher;
        this.cacheFile = cacheFile;
        this.options = options;
    }

    public String groupId() {
        return groupId;
    }

    public String artifactId() {
        return artifactId;
    }

    public String version() {
        return version;
    }

    /// False when the repository answers 404 for the jar.
    public boolean exists() throws IOException {
        return index().exists();
    }

    /// Names of all entries (directories end with `/`), in central directory order.
    public Set<String> entryNames() throws IOException {
        return index().entries().keySet();
    }

    public boolean contains(String entry) throws IOException {
        return index().entries().containsKey(entry);
    }

    /// The uncompressed, CRC-checked content of an entry; empty when the jar or the entry does not exist.
    public Optional<byte[]> read(String entry) throws IOException {
        Index idx = index();
        ZipIndex.Entry zipEntry = idx.entries().get(entry);
        if (zipEntry == null) {
            return Optional.empty();
        }
        byte[] whole = wholeJar(idx);
        return Optional.of(whole != null ? ZipIndex.extract(whole, 0, zipEntry) : readRanged(zipEntry, idx.size()));
    }

    /// Whether the index is known without a request: this cache holds the jar, its central directory or its 404
    /// marker, or the index was read or [adopted][#adoptIndex] already.
    public boolean indexCached() {
        return index != null || Files.exists(cacheFile) || Files.exists(sibling(".cd"))
                || Files.exists(sibling(".404"));
    }

    /// Whether this cache holds the whole jar, so that every entry is read without a request.
    public boolean wholeCached() {
        return content != null || Files.exists(cacheFile);
    }

    /// Takes the index of the same jar from another cache that holds it ([#indexCached()]), so that this one needs
    /// no request for it, and sees the entries this cache holds in `.entries/` without an index of its own. Nothing is
    /// written; what this jar downloads still goes to its own cache, and the first entry it writes there writes the
    /// adopted index as this cache's `.cd` too.
    ///
    /// @throws IllegalArgumentException when `other` is another jar, or its index would need a request
    public void adoptIndex(RemoteJar other) throws IOException {
        if (!other.groupId.equals(groupId) || !other.artifactId.equals(artifactId) || !other.version.equals(version)) {
            throw new IllegalArgumentException(other + " is not " + this);
        }
        if (!other.indexCached()) {
            throw new IllegalArgumentException("the other cache has no index of " + this);
        }
        Index adopted = other.index();
        lock.lock();
        try {
            if (index == null) {
                index = adopted;
                adoptedFrom = other.adoptedFrom != null ? other.adoptedFrom : other;
            }
        } finally {
            lock.unlock();
        }
    }

    /// Whether this cache holds entries of the jar in `.entries/`, with or without an index of its own.
    public boolean hasCachedEntries() {
        return Files.isDirectory(sibling(".entries"));
    }

    /// The size of the jar in bytes, 0 when it does not exist.
    public long size() throws IOException {
        return index().size();
    }

    /// The CRC-32 and size of an entry; empty when the jar or the entry does not exist.
    public Optional<EntryKey> key(String entry) throws IOException {
        ZipIndex.Entry zipEntry = index().entries().get(entry);
        return zipEntry == null ? Optional.empty() : Optional.of(new EntryKey(zipEntry.crc(), zipEntry.size()));
    }

    /// Whether this cache holds the content of an entry: the whole jar, or the entry (of the right size) in
    /// `.entries/`. False when the index itself is not cached, or the entry does not exist.
    public boolean entryCached(String entry) throws IOException {
        if (!indexCached()) {
            return false;
        }
        ZipIndex.Entry zipEntry = index().entries().get(entry);
        if (zipEntry == null) {
            return false;
        }
        if (wholeCached()) {
            return true;
        }
        Path file = entryFile(zipEntry);
        return Files.isRegularFile(file) && Files.size(file) == zipEntry.size();
    }

    /// Of the given entries, those whose content this cache holds, as [#entryCached] tells, with one listing of
    /// `.entries/` instead of a look per entry (the size of a cached entry is checked when it is read). Empty when the
    /// index is not cached.
    public Set<String> cachedEntries(Collection<String> entries) throws IOException {
        if (!indexCached()) {
            return Set.of();
        }
        Map<String, ZipIndex.Entry> index = index().entries();
        Set<String> cached = new LinkedHashSet<>();
        if (wholeCached()) {
            for (String entry : entries) {
                if (index.containsKey(entry)) {
                    cached.add(entry);
                }
            }
            return cached;
        }
        Path dir = sibling(".entries");
        if (!Files.isDirectory(dir)) {
            return cached;
        }
        Set<String> files = new HashSet<>();
        try (Stream<Path> listing = Files.list(dir)) {
            listing.forEach(file -> files.add(file.getFileName().toString()));
        }
        for (String entry : entries) {
            if (index.containsKey(entry) && files.contains(hash(entry))) {
                cached.add(entry);
            }
        }
        return cached;
    }

    /// The content of an entry when this cache holds it ([#entryCached]), CRC-checked; empty when only a request
    /// would get it, or the entry does not exist. Never sends a request.
    public Optional<byte[]> readCached(String entry) throws IOException {
        if (!indexCached()) {
            return Optional.empty();
        }
        ZipIndex.Entry zipEntry = index().entries().get(entry);
        if (zipEntry == null) {
            return Optional.empty();
        }
        byte[] whole = cachedWhole();
        if (whole != null) {
            return Optional.of(ZipIndex.extract(whole, 0, zipEntry));
        }
        return Optional.ofNullable(cachedEntry(zipEntry));
    }

    /// What [#readEntries] would request for these entries, from the index alone: nothing for entries this cache holds
    /// or that do not exist.
    ///
    /// @throws IllegalStateException when the index itself is not known without a request ([#indexCached()])
    public Fetch plan(Collection<String> entries) throws IOException {
        if (!indexCached()) {
            throw new IllegalStateException("the index of " + this + " is not cached");
        }
        if (wholeCached()) {
            return Fetch.NONE;
        }
        Index idx = index();
        List<ZipIndex.Entry> needed = new ArrayList<>();
        for (ZipIndex.Entry entry : existing(idx, entries)) {
            Path file = entryFile(entry);
            if (!Files.isRegularFile(file) || Files.size(file) != entry.size()) {
                needed.add(entry);
            }
        }
        if (needed.isEmpty()) {
            return Fetch.NONE;
        }
        if (idx.size() <= options.fullJarMax()) {
            return new Fetch(1, idx.size(), true);
        }
        List<Run> runs = runs(needed, idx.size());
        long bytes = runs.stream().mapToLong(Run::length).sum();
        return bytes * 2 >= idx.size() ? new Fetch(1, idx.size(), true) : new Fetch(runs.size(), bytes, false);
    }

    /// The uncompressed, CRC-checked contents of several entries, by name, with few requests; entries the jar does
    /// not have are left out (all of them when it does not exist).
    ///
    /// - The whole jar, when cached, or when it has at most [Options#fullJarMax()] bytes (downloaded and cached).
    /// - Otherwise each entry cached in `.entries/` is read from there, and the others with single-range requests:
    ///   sorted by local header offset, entries less than [#MAX_GAP] apart are read together, with slack for the local
    ///   extra field after the last one, and the closest of these runs are joined until there are at most
    ///   [#MAX_RUNS]. When the runs cover half of the jar or more, the jar is downloaded whole instead. Each entry is
    ///   inflated, CRC-checked and cached in `.entries/`; an entry whose local extra field outgrows the slack costs a
    ///   request of its own.
    public Map<String, byte[]> readEntries(Collection<String> entries) throws IOException {
        Index idx = index();
        List<ZipIndex.Entry> wanted = existing(idx, entries);
        Map<String, byte[]> contents = new LinkedHashMap<>();
        byte[] whole = wanted.isEmpty() ? null : cachedWhole();
        if (!wanted.isEmpty() && whole == null) {
            List<ZipIndex.Entry> needed = new ArrayList<>();
            for (ZipIndex.Entry entry : wanted) {
                byte[] cached = cachedEntry(entry);
                if (cached != null) {
                    contents.put(entry.name(), cached);
                } else {
                    needed.add(entry);
                }
            }
            List<Run> runs = needed.isEmpty() || idx.size() <= options.fullJarMax() ? List.of()
                    : runs(needed, idx.size());
            long bytes = runs.stream().mapToLong(Run::length).sum();
            if (!runs.isEmpty() && bytes * 2 < idx.size()) {
                readRuns(runs, contents);
            } else if (!needed.isEmpty()) {
                whole = downloadWhole(idx);
            }
        }
        // in the order asked for
        Map<String, byte[]> ordered = new LinkedHashMap<>();
        for (ZipIndex.Entry entry : wanted) {
            byte[] content = contents.get(entry.name());
            ordered.put(entry.name(), content != null ? content : ZipIndex.extract(whole, 0, entry));
        }
        return ordered;
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId + ":" + version;
    }

    private Index index() throws IOException {
        Index current = index;
        if (current != null) {
            return current;
        }
        lock.lock();
        try {
            if (index == null) {
                index = loadIndex();
            }
            return index;
        } finally {
            lock.unlock();
        }
    }

    private Index loadIndex() throws IOException {
        if (Files.exists(sibling(".404"))) {
            return Index.MISSING;
        }
        if (Files.exists(cacheFile)) {
            return indexOfFile(cacheFile);
        }
        Path cdCache = sibling(".cd");
        if (Files.exists(cdCache)) {
            return readCentralDirectoryCache(cdCache);
        }
        Optional<RangeFetcher.Tail> fetched = fetcher.tail(options.tailSize());
        if (fetched.isEmpty()) {
            AtomicFiles.write(sibling(".404"), new byte[0]);
            return Index.MISSING;
        }
        RangeFetcher.Tail tail = fetched.get();
        if (tail.isWholeFile()) {
            AtomicFiles.write(cacheFile, tail.bytes());
            content = tail.bytes();
            return indexOf(content);
        }
        ZipIndex.CentralDirectory cd = ZipIndex.findCentralDirectory(tail.bytes(), tail.offset());
        if (cd.zip64()) {
            Log.debug("%s uses ZIP64, downloading it whole", this);
            content = download(tail.fileSize());
            return indexOf(content);
        }
        byte[] cdBytes = cd.offset() >= tail.offset()
                ? slice(tail.bytes(), cd.offset() - tail.offset(), cd.size())
                : fetcher.range(cd.offset(), Math.toIntExact(cd.size()));
        Map<String, ZipIndex.Entry> entries = ZipIndex.parseCentralDirectory(cdBytes);
        writeCentralDirectoryCache(cdCache, tail.fileSize(), cdBytes);
        return new Index(true, tail.fileSize(), entries);
    }

    private static Index indexOf(byte[] jar) throws ZipException {
        ZipIndex.CentralDirectory cd = ZipIndex.findCentralDirectory(jar, 0);
        if (cd.offset() < 0) {
            throw new ZipException("unsupported zip layout (multi-disk or inconsistent ZIP64 records)");
        }
        return new Index(true, jar.length, ZipIndex.parseCentralDirectory(slice(jar, cd.offset(), cd.size())));
    }

    /// The index of a cached jar from its end: the end records, then the central directory, without reading the
    /// entries (a ZIP64 jar whose end records are not all in the tail is read whole).
    private static Index indexOfFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            int tailLength = (int) Math.min(size, LOCAL_TAIL);
            long tailOffset = size - tailLength;
            byte[] tail = read(channel, tailOffset, tailLength);
            ZipIndex.CentralDirectory cd = ZipIndex.findCentralDirectory(tail, tailOffset);
            if (cd.offset() < 0) {
                return indexOf(Files.readAllBytes(file));
            }
            byte[] cdBytes = cd.offset() >= tailOffset
                    ? slice(tail, cd.offset() - tailOffset, cd.size())
                    : read(channel, cd.offset(), Math.toIntExact(cd.size()));
            return new Index(true, size, ZipIndex.parseCentralDirectory(cdBytes));
        }
    }

    private static byte[] read(FileChannel channel, long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, offset + buffer.position()) < 0) {
                throw new EOFException("unexpected end of file at " + (offset + buffer.position()));
            }
        }
        return buffer.array();
    }

    /// The whole jar when it is (or should be) available locally, else null for ranged reads.
    private byte[] wholeJar(Index idx) throws IOException {
        byte[] whole = content;
        if (whole != null || (idx.size() > options.fullJarMax() && !Files.exists(cacheFile))) {
            return whole;
        }
        lock.lock();
        try {
            if (content == null) {
                content = Files.exists(cacheFile) ? Files.readAllBytes(cacheFile) : download(idx.size());
            }
            return content;
        } finally {
            lock.unlock();
        }
    }

    /// The whole jar when this cache holds it, else null; never a request.
    private byte[] cachedWhole() throws IOException {
        byte[] whole = content;
        if (whole != null || !Files.exists(cacheFile)) {
            return whole;
        }
        lock.lock();
        try {
            if (content == null) {
                content = Files.readAllBytes(cacheFile);
            }
            return content;
        } finally {
            lock.unlock();
        }
    }

    /// The whole jar, downloaded (and cached) unless this cache holds it already.
    private byte[] downloadWhole(Index idx) throws IOException {
        lock.lock();
        try {
            if (content == null) {
                content = Files.exists(cacheFile) ? Files.readAllBytes(cacheFile) : download(idx.size());
            }
            return content;
        } finally {
            lock.unlock();
        }
    }

    private byte[] download(long expectedSize) throws IOException {
        byte[] jar = fetcher.all();
        if (jar.length != expectedSize) {
            throw new IOException(this + ": expected " + expectedSize + " bytes, got " + jar.length);
        }
        AtomicFiles.write(cacheFile, jar);
        return jar;
    }

    private byte[] readRanged(ZipIndex.Entry entry, long fileSize) throws IOException {
        byte[] cached = cachedEntry(entry);
        if (cached != null) {
            return cached;
        }
        long start = entry.localHeaderOffset();
        long estimate = ZipIndex.LOCAL_HEADER_SIZE + entry.nameLength() + entry.extraLength()
                + entry.compressedSize() + LOCAL_EXTRA_SLACK;
        byte[] region = fetcher.range(start, Math.toIntExact(Math.min(estimate, fileSize - start)));
        long needed = ZipIndex.recordLength(region, start, entry);
        if (needed > region.length) {
            region = fetcher.range(start, Math.toIntExact(needed));
        }
        byte[] data = ZipIndex.extract(region, start, entry);
        AtomicFiles.write(entryFile(entry), data);
        keepAdoptedIndex();
        return data;
    }

    /// Reads the entries of each run with one range request (an entry whose record the run does not hold with one of
    /// its own), and caches each in `.entries/`.
    private void readRuns(List<Run> runs, Map<String, byte[]> contents) throws IOException {
        for (Run run : runs) {
            byte[] region = fetcher.range(run.start(), Math.toIntExact(run.length()));
            for (ZipIndex.Entry entry : run.entries()) {
                long needed = ZipIndex.recordLength(region, run.start(), entry);
                byte[] data;
                if (entry.localHeaderOffset() - run.start() + needed <= region.length) {
                    data = ZipIndex.extract(region, run.start(), entry);
                } else {
                    byte[] record = fetcher.range(entry.localHeaderOffset(), Math.toIntExact(needed));
                    data = ZipIndex.extract(record, entry.localHeaderOffset(), entry);
                }
                AtomicFiles.write(entryFile(entry), data);
                keepAdoptedIndex();
                contents.put(entry.name(), data);
            }
        }
    }

    /// Writes the central directory of an adopted index as this cache's `.cd`, copied from the other cache, once this
    /// cache holds an entry: without it, later reads of this cache would not see the entry. Nothing happens when this
    /// cache has the jar or its `.cd`, or the other one has no `.cd` to copy (a whole jar there leaves nothing to
    /// fetch here).
    private void keepAdoptedIndex() throws IOException {
        RemoteJar from = adoptedFrom;
        if (from == null) {
            return;
        }
        lock.lock();
        try {
            if (adoptedFrom == null) {
                return;
            }
            Path cd = sibling(".cd");
            Path other = from.sibling(".cd");
            if (!Files.exists(cacheFile) && !Files.exists(cd) && Files.isRegularFile(other)) {
                AtomicFiles.write(cd, Files.readAllBytes(other));
            }
            adoptedFrom = null;
        } finally {
            lock.unlock();
        }
    }

    /// The entries of these names that the jar has, each once, in the given order.
    private static List<ZipIndex.Entry> existing(Index idx, Collection<String> names) {
        Map<String, ZipIndex.Entry> found = new LinkedHashMap<>();
        for (String name : names) {
            ZipIndex.Entry entry = idx.entries().get(name);
            if (entry != null) {
                found.putIfAbsent(name, entry);
            }
        }
        return List.copyOf(found.values());
    }

    /// The single-range reads of entries: sorted by local header offset, entries less than [#MAX_GAP] apart in one
    /// run, the closest runs joined until there are at most [#MAX_RUNS], each run with slack after its last entry.
    private static List<Run> runs(List<ZipIndex.Entry> entries, long fileSize) {
        List<ZipIndex.Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingLong(ZipIndex.Entry::localHeaderOffset));
        List<Run> runs = new ArrayList<>();
        for (ZipIndex.Entry entry : sorted) {
            long start = entry.localHeaderOffset();
            long end = start + ZipIndex.LOCAL_HEADER_SIZE + entry.nameLength() + entry.extraLength()
                    + entry.compressedSize();
            Run last = runs.isEmpty() ? null : runs.getLast();
            if (last != null && start - last.end() < MAX_GAP) {
                List<ZipIndex.Entry> joined = new ArrayList<>(last.entries());
                joined.add(entry);
                runs.set(runs.size() - 1, new Run(last.start(), Math.max(last.end(), end), joined));
            } else {
                runs.add(new Run(start, end, List.of(entry)));
            }
        }
        while (runs.size() > MAX_RUNS) {
            int closest = 0;
            for (int i = 1; i + 1 < runs.size(); i++) {
                if (runs.get(i + 1).start() - runs.get(i).end() < runs.get(closest + 1).start()
                        - runs.get(closest).end()) {
                    closest = i;
                }
            }
            Run first = runs.get(closest);
            Run second = runs.remove(closest + 1);
            List<ZipIndex.Entry> joined = new ArrayList<>(first.entries());
            joined.addAll(second.entries());
            runs.set(closest, new Run(first.start(), Math.max(first.end(), second.end()), joined));
        }
        List<Run> withSlack = new ArrayList<>(runs.size());
        for (Run run : runs) {
            withSlack.add(new Run(run.start(), Math.min(fileSize, run.end() + LOCAL_EXTRA_SLACK), run.entries()));
        }
        return withSlack;
    }

    /// The content of an entry cached in `.entries/`, null when there is none of the right size and CRC.
    private byte[] cachedEntry(ZipIndex.Entry entry) throws IOException {
        Path cached = entryFile(entry);
        if (Files.isRegularFile(cached)) {
            byte[] data = Files.readAllBytes(cached);
            if (data.length == entry.size() && crc(data) == entry.crc()) {
                return data;
            }
        }
        return null;
    }

    private Path entryFile(ZipIndex.Entry entry) {
        return sibling(".entries").resolve(hash(entry.name()));
    }

    private Index readCentralDirectoryCache(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file); DataInputStream data = new DataInputStream(in)) {
            if (data.readInt() != CD_CACHE_MAGIC) {
                throw new IOException("corrupt central directory cache " + file);
            }
            long size = data.readLong();
            return new Index(true, size, ZipIndex.parseCentralDirectory(data.readAllBytes()));
        }
    }

    private static void writeCentralDirectoryCache(Path file, long fileSize, byte[] cd) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(cd.length + 12);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(CD_CACHE_MAGIC);
            out.writeLong(fileSize);
            out.write(cd);
        }
        AtomicFiles.write(file, bytes.toByteArray());
    }

    private Path sibling(String suffix) {
        return cacheFile.resolveSibling(cacheFile.getFileName() + suffix);
    }

    private static byte[] slice(byte[] bytes, long from, long length) throws ZipException {
        if (from < 0 || length < 0 || from + length > bytes.length) {
            throw new ZipException("central directory outside the available bytes");
        }
        return Arrays.copyOfRange(bytes, (int) from, (int) (from + length));
    }

    private static long crc(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private static String hash(String entryName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(entryName.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
