package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.ScratchDir;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Zip reading over range requests, against zips built locally and an in-memory fetcher (no network).
class RemoteJarTest {

    private static final String STORED_MARKER = "STORED-CONTENT-MARKER";

    private ScratchDir dir;

    @BeforeEach
    void createDir() throws IOException {
        dir = ScratchDir.create();
    }

    @AfterEach
    void deleteDir() throws InterruptedException {
        dir.close();
    }

    /// [RangeFetcher] over a byte array that counts requests; a null array behaves like HTTP 404.
    private static final class BytesFetcher implements RangeFetcher {
        private final byte[] data;
        private int tails;
        private int ranges;
        private int alls;
        private long rangeBytes;

        BytesFetcher(byte[] data) {
            this.data = data;
        }

        @Override
        public Optional<Tail> tail(int length) {
            tails++;
            if (data == null) {
                return Optional.empty();
            }
            return Optional.of(new Tail(Arrays.copyOfRange(data, Math.max(0, data.length - length), data.length),
                    data.length));
        }

        @Override
        public byte[] range(long offset, int length) {
            ranges++;
            rangeBytes += length;
            return Arrays.copyOfRange(data, (int) offset, (int) offset + length);
        }

        @Override
        public byte[] all() {
            alls++;
            return data.clone();
        }

        int requests() {
            return tails + ranges + alls;
        }
    }

    @Test
    void wholeFileInTailIsCachedAsTheFullJar() throws IOException {
        Map<String, byte[]> entries = sampleEntries(3);
        byte[] zip = zip("small.jar", entries);
        BytesFetcher fetcher = new BytesFetcher(zip);
        Path cacheFile = dir.resolve("cache/small.jar");
        RemoteJar jar = jar(fetcher, cacheFile, RemoteJar.Options.DEFAULT);

        assertTrue(jar.exists());
        assertEquals(entries.keySet(), jar.entryNames());
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            assertArrayEquals(entry.getValue(), jar.read(entry.getKey()).orElseThrow(), entry.getKey());
        }
        assertEquals(Optional.empty(), jar.read("no/such/Entry.class"));
        assertEquals(1, fetcher.tails);
        assertEquals(1, fetcher.requests());
        assertArrayEquals(zip, Files.readAllBytes(cacheFile));

        BytesFetcher second = new BytesFetcher(zip);
        assertArrayEquals(entries.get("META-INF/stored.txt"),
                jar(second, cacheFile, RemoteJar.Options.DEFAULT).read("META-INF/stored.txt").orElseThrow());
        assertEquals(0, second.requests());
    }

    @Test
    void centralDirectoryOutsideTheTailIsFetchedThenEntriesAreReadByRange() throws IOException {
        Map<String, byte[]> entries = sampleEntries(40);
        BytesFetcher fetcher = new BytesFetcher(zip("big.jar", entries));
        Path cacheFile = dir.resolve("cache/big.jar");
        RemoteJar.Options rangedReads = new RemoteJar.Options(128, 0);
        RemoteJar jar = jar(fetcher, cacheFile, rangedReads);

        assertEquals(entries.keySet(), jar.entryNames());
        assertEquals(1, fetcher.tails);
        assertEquals(1, fetcher.ranges);

        assertArrayEquals(entries.get("pkg/Entry7.class"), jar.read("pkg/Entry7.class").orElseThrow());
        assertArrayEquals(entries.get("META-INF/stored.txt"), jar.read("META-INF/stored.txt").orElseThrow());
        assertEquals(3, fetcher.ranges);
        assertEquals(0, fetcher.alls);
        assertFalse(Files.exists(cacheFile));

        BytesFetcher second = new BytesFetcher(fetcher.data);
        RemoteJar cached = jar(second, cacheFile, rangedReads);
        assertArrayEquals(entries.get("pkg/Entry7.class"), cached.read("pkg/Entry7.class").orElseThrow());
        assertEquals(0, second.requests());
        assertArrayEquals(entries.get("pkg/Entry8.class"), cached.read("pkg/Entry8.class").orElseThrow());
        assertEquals(1, second.requests());
    }

    @Test
    void smallJarIsDownloadedWholeOnFirstRead() throws IOException {
        Map<String, byte[]> entries = sampleEntries(40);
        BytesFetcher fetcher = new BytesFetcher(zip("medium.jar", entries));
        Path cacheFile = dir.resolve("cache/medium.jar");
        RemoteJar jar = jar(fetcher, cacheFile, new RemoteJar.Options(128, 1024 * 1024));

        assertTrue(jar.contains("pkg/Entry3.class"));
        assertEquals(2, fetcher.requests());
        assertArrayEquals(entries.get("pkg/Entry3.class"), jar.read("pkg/Entry3.class").orElseThrow());
        assertArrayEquals(entries.get("pkg/Entry4.class"), jar.read("pkg/Entry4.class").orElseThrow());
        assertEquals(1, fetcher.alls);
        assertEquals(3, fetcher.requests());
        assertArrayEquals(fetcher.data, Files.readAllBytes(cacheFile));
    }

    @Test
    void corruptEntryFailsTheCrcCheck() throws IOException {
        byte[] zip = zip("corrupt.jar", sampleEntries(2));
        int marker = indexOf(zip, STORED_MARKER.getBytes(StandardCharsets.UTF_8));
        zip[marker] ^= 0x20;
        RemoteJar jar = jar(new BytesFetcher(zip), dir.resolve("cache/corrupt.jar"), RemoteJar.Options.DEFAULT);

        ZipException error = assertThrows(ZipException.class, () -> jar.read("META-INF/stored.txt"));
        assertTrue(error.getMessage().contains("CRC"), error.getMessage());
        assertTrue(jar.read("pkg/Entry1.class").isPresent());
    }

    @Test
    void missingJarIsRememberedWithAMarker() throws IOException {
        Path cacheFile = dir.resolve("cache/missing.jar");
        BytesFetcher fetcher = new BytesFetcher(null);
        RemoteJar jar = jar(fetcher, cacheFile, RemoteJar.Options.DEFAULT);

        assertFalse(jar.exists());
        assertTrue(jar.entryNames().isEmpty());
        assertEquals(Optional.empty(), jar.read("META-INF/MANIFEST.MF"));
        assertTrue(Files.exists(dir.resolve("cache/missing.jar.404")));

        BytesFetcher second = new BytesFetcher(null);
        assertFalse(jar(second, cacheFile, RemoteJar.Options.DEFAULT).exists());
        assertEquals(0, second.requests());
    }

    @Test
    void zip64ArchivesAreDownloadedWhole() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 65_540; i++) {
            entries.put("e" + i, new byte[0]);
        }
        entries.put("last.txt", "last".getBytes(StandardCharsets.UTF_8));
        BytesFetcher fetcher = new BytesFetcher(zip("zip64.jar", entries));
        RemoteJar jar = jar(fetcher, dir.resolve("cache/zip64.jar"), new RemoteJar.Options(1024, 0));

        assertEquals(65_541, jar.entryNames().size());
        assertEquals(1, fetcher.alls);
        assertArrayEquals("last".getBytes(StandardCharsets.UTF_8), jar.read("last.txt").orElseThrow());
        assertEquals(2, fetcher.requests());
    }

    @Test
    void readEntriesReadsNearbyEntriesWithOneRangeRequestAndCachesThem() throws IOException {
        Map<String, byte[]> entries = incompressibleEntries(30, 10_000);
        BytesFetcher fetcher = new BytesFetcher(zip("ranges.jar", entries));
        Path cacheFile = dir.resolve("cache/ranges.jar");
        RemoteJar jar = jar(fetcher, cacheFile, new RemoteJar.Options(128, 0));
        assertEquals(entries.keySet(), jar.entryNames());
        assertEquals(1, fetcher.tails);
        int indexRequests = fetcher.ranges;

        // 0, 1, 2 and 5 are less than 64 KB apart: one request; 14 is farther: another
        List<String> wanted = List.of(name(5), name(0), name(14), name(1), name(2), "pkg/NoSuchEntry.class");
        RemoteJar.Fetch plan = jar.plan(wanted);
        assertEquals(new RemoteJar.Fetch(2, plan.bytes(), false), plan);
        Map<String, byte[]> read = jar.readEntries(wanted);
        assertEquals(2, fetcher.ranges - indexRequests);
        assertEquals(plan.bytes(), fetcher.rangeBytes - indexRangeBytes(fetcher, indexRequests));
        assertEquals(List.of(name(5), name(0), name(14), name(1), name(2)), List.copyOf(read.keySet()));
        for (String name : read.keySet()) {
            assertArrayEquals(entries.get(name), read.get(name), name);
        }
        assertEquals(0, fetcher.alls);
        assertFalse(Files.exists(cacheFile), "not downloaded whole");

        // cached in .entries/: read again without request, also by another instance and without its own index
        BytesFetcher second = new BytesFetcher(fetcher.data);
        RemoteJar again = jar(second, cacheFile, new RemoteJar.Options(128, 0));
        assertTrue(again.indexCached());
        assertFalse(again.wholeCached());
        assertTrue(again.entryCached(name(14)));
        assertFalse(again.entryCached(name(13)));
        assertArrayEquals(entries.get(name(14)), again.readCached(name(14)).orElseThrow());
        assertEquals(Optional.empty(), again.readCached(name(13)));
        assertEquals(RemoteJar.Fetch.NONE, again.plan(List.of(name(0), name(14))));
        assertEquals(5, again.readEntries(wanted).size());
        assertEquals(0, second.requests());
        assertEquals(Optional.of(new RemoteJar.EntryKey(crc(entries.get(name(3))), 10_000)), again.key(name(3)));
    }

    @Test
    void readEntriesJoinsTheClosestRunsToSendAtMostEight() throws IOException {
        Map<String, byte[]> entries = incompressibleEntries(65, 10_000);
        BytesFetcher fetcher = new BytesFetcher(zip("far.jar", entries));
        RemoteJar jar = jar(fetcher, dir.resolve("cache/far.jar"), new RemoteJar.Options(128, 0));
        jar.entryNames();
        int indexRequests = fetcher.ranges;
        // nine entries 70 KB apart: nine runs, the closest two joined
        List<String> wanted = new ArrayList<>();
        for (int i = 0; i <= 64; i += 8) {
            wanted.add(name(i));
        }
        RemoteJar.Fetch plan = jar.plan(wanted);
        assertEquals(8, plan.requests());
        Map<String, byte[]> read = jar.readEntries(wanted);
        assertEquals(8, fetcher.ranges - indexRequests);
        assertEquals(9, read.size());
        wanted.forEach(name -> assertArrayEquals(entries.get(name), read.get(name), name));
    }

    @Test
    void readEntriesDownloadsTheJarWholeWhenItsRunsCoverHalfOfIt() throws IOException {
        Map<String, byte[]> entries = incompressibleEntries(30, 10_000);
        BytesFetcher fetcher = new BytesFetcher(zip("most.jar", entries));
        Path cacheFile = dir.resolve("cache/most.jar");
        RemoteJar jar = jar(fetcher, cacheFile, new RemoteJar.Options(128, 0));
        List<String> wanted = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            wanted.add(name(i));
        }
        jar.entryNames();
        int before = fetcher.requests();
        assertEquals(new RemoteJar.Fetch(1, fetcher.data.length, true), jar.plan(wanted));
        Map<String, byte[]> read = jar.readEntries(wanted);
        assertEquals(1, fetcher.requests() - before);
        assertEquals(1, fetcher.alls);
        assertArrayEquals(fetcher.data, Files.readAllBytes(cacheFile));
        wanted.forEach(name -> assertArrayEquals(entries.get(name), read.get(name), name));
        // whole now: everything else is read without request
        assertTrue(jar.wholeCached());
        assertEquals(RemoteJar.Fetch.NONE, jar.plan(List.of(name(29))));
        assertArrayEquals(entries.get(name(29)), jar.readEntries(List.of(name(29))).get(name(29)));
        assertEquals(1, fetcher.requests() - before);
    }

    @Test
    void readEntriesDownloadsASmallJarWholeAndTakesTheIndexOfAnotherCache() throws IOException {
        Map<String, byte[]> entries = incompressibleEntries(10, 1_000);
        byte[] zip = zip("small-many.jar", entries);
        // another cache holds the central directory: this one needs no request for it
        BytesFetcher other = new BytesFetcher(zip);
        RemoteJar elsewhere = jar(other, dir.resolve("other/small-many.jar"), new RemoteJar.Options(128, 0));
        assertEquals(entries.keySet(), elsewhere.entryNames());
        BytesFetcher fetcher = new BytesFetcher(zip);
        Path cacheFile = dir.resolve("cache/small-many.jar");
        RemoteJar jar = jar(fetcher, cacheFile, new RemoteJar.Options(128, 1024 * 1024));
        assertFalse(jar.indexCached());
        jar.adoptIndex(elsewhere);
        assertTrue(jar.indexCached());
        assertEquals(new RemoteJar.Fetch(1, zip.length, true), jar.plan(List.of(name(3))));
        assertArrayEquals(entries.get(name(3)), jar.readEntries(List.of(name(3))).get(name(3)));
        assertEquals(1, fetcher.alls);
        assertEquals(1, fetcher.requests());
        assertArrayEquals(zip, Files.readAllBytes(cacheFile));
        assertThrows(IllegalArgumentException.class, () -> jar(fetcher, dir.resolve("none/x.jar"),
                RemoteJar.Options.DEFAULT).adoptIndex(jar(new BytesFetcher(zip), dir.resolve("none/y.jar"),
                RemoteJar.Options.DEFAULT)));
    }

    @Test
    void readEntriesChecksTheCrcOfEveryEntry() throws IOException {
        byte[] zip = zip("bad.jar", sampleEntries(2));
        zip[indexOf(zip, STORED_MARKER.getBytes(StandardCharsets.UTF_8))] ^= 0x20;
        RemoteJar jar = jar(new BytesFetcher(zip), dir.resolve("cache/bad.jar"), new RemoteJar.Options(128, 0));
        ZipException error = assertThrows(ZipException.class, () -> jar.readEntries(List.of("pkg/Entry0.class",
                "META-INF/stored.txt")));
        assertTrue(error.getMessage().contains("CRC"), error.getMessage());
    }

    @Test
    void aLocalExtraFieldLargerThanTheSlackCostsOneMoreRequest() throws IOException {
        byte[] big = new byte[100_000];
        new Random(7).nextBytes(big);
        byte[] small = "small entry".getBytes(StandardCharsets.UTF_8);
        // the second entry's local header has a 3,000-byte extra field that its central one lacks
        byte[] zip = storedZip(List.of("big.bin", "small.txt"), List.of(big, small), List.of(0, 3_000));
        BytesFetcher fetcher = new BytesFetcher(zip);
        RemoteJar jar = jar(fetcher, dir.resolve("cache/extra.jar"), new RemoteJar.Options(256, 0));
        jar.entryNames();
        int before = fetcher.requests();
        assertEquals(1, jar.plan(List.of("small.txt")).requests());
        assertArrayEquals(small, jar.readEntries(List.of("small.txt")).get("small.txt"));
        assertEquals(2, fetcher.requests() - before);
        assertArrayEquals(big, jar(new BytesFetcher(zip), dir.resolve("cache/extra2.jar"),
                new RemoteJar.Options(256, 0)).readEntries(List.of("big.bin")).get("big.bin"));
    }

    @Test
    void entriesReadWithAnAdoptedIndexKeepThatIndexWithThem() throws IOException {
        Map<String, byte[]> entries = incompressibleEntries(30, 10_000);
        byte[] zip = zip("adopted.jar", entries);
        RemoteJar elsewhere = jar(new BytesFetcher(zip), dir.resolve("other/adopted.jar"), new RemoteJar.Options(128,
                0));
        elsewhere.entryNames();
        Path otherCd = dir.resolve("other/adopted.jar.cd");
        assertTrue(Files.isRegularFile(otherCd));

        BytesFetcher fetcher = new BytesFetcher(zip);
        Path cacheFile = dir.resolve("cache/adopted.jar");
        Path cd = dir.resolve("cache/adopted.jar.cd");
        RemoteJar jar = jar(fetcher, cacheFile, new RemoteJar.Options(128, 0));
        jar.adoptIndex(elsewhere);
        // a plan writes nothing
        assertEquals(1, jar.plan(List.of(name(5))).requests());
        assertFalse(Files.exists(cd));
        assertArrayEquals(entries.get(name(5)), jar.readEntries(List.of(name(5))).get(name(5)));
        assertEquals(1, fetcher.requests());
        assertArrayEquals(Files.readAllBytes(otherCd), Files.readAllBytes(cd), "the index read with, kept here");
        assertFalse(Files.exists(cacheFile));

        // so another run finds the entry here, without the other cache
        BytesFetcher second = new BytesFetcher(zip);
        RemoteJar again = jar(second, cacheFile, new RemoteJar.Options(128, 0));
        assertTrue(again.indexCached());
        assertTrue(again.entryCached(name(5)));
        assertArrayEquals(entries.get(name(5)), again.readCached(name(5)).orElseThrow());
        assertEquals(0, second.requests());
    }

    @Test
    void entriesWithoutAnIndexOfTheirOwnAreReadWithOneAdopted() throws IOException {
        Map<String, byte[]> entries = incompressibleEntries(30, 10_000);
        byte[] zip = zip("orphan.jar", entries);
        RemoteJar elsewhere = jar(new BytesFetcher(zip), dir.resolve("other/orphan.jar"), new RemoteJar.Options(128,
                0));
        elsewhere.entryNames();
        // entries cached here, whose central directory is gone (as an earlier version of the code left them)
        Path cacheFile = dir.resolve("cache/orphan.jar");
        jar(new BytesFetcher(zip), cacheFile, new RemoteJar.Options(128, 0)).readEntries(List.of(name(3), name(4)));
        Files.delete(dir.resolve("cache/orphan.jar.cd"));

        BytesFetcher fetcher = new BytesFetcher(zip);
        RemoteJar jar = jar(fetcher, cacheFile, new RemoteJar.Options(128, 0));
        assertFalse(jar.indexCached());
        assertTrue(jar.hasCachedEntries());
        assertEquals(Set.of(), jar.cachedEntries(List.of(name(3), name(4))));
        assertEquals(Optional.empty(), jar.readCached(name(3)));
        jar.adoptIndex(elsewhere);
        assertEquals(Set.of(name(3), name(4)), jar.cachedEntries(List.of(name(3), name(4), name(5))));
        assertTrue(jar.entryCached(name(4)));
        assertArrayEquals(entries.get(name(3)), jar.readCached(name(3)).orElseThrow());
        assertEquals(RemoteJar.Fetch.NONE, jar.plan(List.of(name(3), name(4))));
        assertEquals(0, fetcher.requests());
        assertFalse(Files.exists(dir.resolve("cache/orphan.jar.cd")), "reading cached entries writes nothing");
    }

    @Test
    void aJarCachedWholeIsIndexedFromItsEnd() throws IOException {
        Map<String, byte[]> entries = incompressibleEntries(30, 10_000);
        byte[] zip = zip("cached.jar", entries);
        Path cacheFile = dir.resolve("cache/cached.jar");
        Files.createDirectories(cacheFile.getParent());
        Files.write(cacheFile, zip);
        BytesFetcher fetcher = new BytesFetcher(zip);
        RemoteJar jar = jar(fetcher, cacheFile, new RemoteJar.Options(128, 0));
        assertTrue(jar.indexCached());
        assertTrue(jar.wholeCached());
        assertEquals(entries.keySet(), jar.entryNames());
        assertEquals(zip.length, jar.size());
        assertTrue(jar.entryCached(name(7)));
        assertArrayEquals(entries.get(name(7)), jar.readCached(name(7)).orElseThrow());
        assertArrayEquals(entries.get(name(8)), jar.read(name(8)).orElseThrow());
        assertEquals(0, fetcher.requests());
    }

    private static String name(int i) {
        return "pkg/E" + i + ".class";
    }

    /// `count` entries of `size` random bytes each, which deflate keeps at about their size.
    private static Map<String, byte[]> incompressibleEntries(int count, int size) {
        Random random = new Random(42);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            byte[] bytes = new byte[size];
            random.nextBytes(bytes);
            entries.put(name(i), bytes);
        }
        return entries;
    }

    /// The bytes of the range requests that indexing sent (the central directory), from the request count alone:
    /// indexing sends at most one, for the central directory outside the tail.
    private static long indexRangeBytes(BytesFetcher fetcher, int indexRequests) {
        if (indexRequests == 0) {
            return 0;
        }
        ByteBuffer data = ByteBuffer.wrap(fetcher.data).order(ByteOrder.LITTLE_ENDIAN);
        return data.getInt(fetcher.data.length - 22 + 12);
    }

    /// A zip of stored entries written by hand, whose local headers have extra fields of the given lengths and whose
    /// central headers have none.
    private static byte[] storedZip(List<String> names, List<byte[]> contents, List<Integer> localExtras) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream central = new ByteArrayOutputStream();
        for (int i = 0; i < names.size(); i++) {
            byte[] name = names.get(i).getBytes(StandardCharsets.UTF_8);
            byte[] data = contents.get(i);
            int offset = out.size();
            ByteBuffer local = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
            local.putInt(0x04034b50).putShort((short) 10).putShort((short) 0).putShort((short) 0).putShort((short) 0)
                    .putShort((short) 0).putInt((int) crc(data)).putInt(data.length).putInt(data.length)
                    .putShort((short) name.length).putShort((short) (int) localExtras.get(i));
            out.writeBytes(local.array());
            out.writeBytes(name);
            out.writeBytes(new byte[localExtras.get(i)]);
            out.writeBytes(data);
            ByteBuffer header = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(0x02014b50).putShort((short) 20).putShort((short) 10).putShort((short) 0)
                    .putShort((short) 0).putShort((short) 0).putShort((short) 0).putInt((int) crc(data))
                    .putInt(data.length).putInt(data.length).putShort((short) name.length).putShort((short) 0)
                    .putShort((short) 0).putShort((short) 0).putShort((short) 0).putInt(0).putInt(offset);
            central.writeBytes(header.array());
            central.writeBytes(name);
        }
        int centralOffset = out.size();
        out.writeBytes(central.toByteArray());
        ByteBuffer end = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
        end.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) names.size())
                .putShort((short) names.size()).putInt(central.size()).putInt(centralOffset).putShort((short) 0);
        out.writeBytes(end.array());
        return out.toByteArray();
    }

    private static long crc(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private static RemoteJar jar(RangeFetcher fetcher, Path cacheFile, RemoteJar.Options options) {
        return new RemoteJar("io.quarkus", "test", "1.0", fetcher, cacheFile, options);
    }

    /// One stored entry, one empty directory and `count` compressible deflated entries.
    private static Map<String, byte[]> sampleEntries(int count) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/", new byte[0]);
        entries.put("META-INF/stored.txt", (STORED_MARKER + " stored without compression").getBytes(
                StandardCharsets.UTF_8));
        for (int i = 0; i < count; i++) {
            entries.put("pkg/Entry" + i + ".class", ("class file " + i + " ").repeat(50).getBytes(
                    StandardCharsets.UTF_8));
        }
        return entries;
    }

    /// Writes a zip into the temp folder (entries ending in .txt are stored, the others deflated).
    private byte[] zip(String name, Map<String, byte[]> entries) throws IOException {
        Path file = dir.resolve(name);
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file));
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                if (entry.getKey().endsWith(".txt")) {
                    CRC32 crc = new CRC32();
                    crc.update(entry.getValue());
                    zipEntry.setMethod(ZipEntry.STORED);
                    zipEntry.setSize(entry.getValue().length);
                    zipEntry.setCompressedSize(entry.getValue().length);
                    zipEntry.setCrc(crc.getValue());
                }
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return Files.readAllBytes(file);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new AssertionError("marker not found");
    }
}
