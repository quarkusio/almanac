package io.quarkiverse.almanac.maven;

import com.sun.net.httpserver.HttpServer;
import io.quarkiverse.almanac.ScratchDir;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// [Http] against a local server: the pacer spaces real requests out, timeouts end stalled exchanges, and refusals,
/// 429 answers and server errors stop the requests, also those of the other commands on the same cache.
class HttpTest {

    /// Waits shortened to milliseconds.
    private static final Http.Timing FAST = timing(Duration.ofSeconds(5), Duration.ofSeconds(30),
            Duration.ofMillis(50), Duration.ofMillis(10), Duration.ofHours(1));

    private ScratchDir dir;
    private ExecutorService handlers;
    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<Long> arrivals = new CopyOnWriteArrayList<>();
    private final AtomicBoolean answeredOnce = new AtomicBoolean();
    /// Ends the stalled answers.
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void start() throws IOException {
        dir = ScratchDir.create();
        handlers = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            arrivals.add(System.nanoTime());
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            boolean first = answeredOnce.compareAndSet(false, true);
            if (path.startsWith("/stall-once") && first || path.startsWith("/stall-always")) {
                // the headers and a part of the body, then nothing until the test ends
                exchange.sendResponseHeaders(200, 20);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(new byte[5]);
                    out.flush();
                    release.await();
                    out.write(new byte[15]);
                } catch (IOException | InterruptedException e) {
                    // the client gave up
                }
                return;
            }
            if (path.startsWith("/trickle") && first) {
                // a byte every 50 ms, never a silence, for 5 s
                exchange.sendResponseHeaders(200, 100);
                try (OutputStream out = exchange.getResponseBody()) {
                    for (int i = 0; i < 100; i++) {
                        out.write('x');
                        out.flush();
                        Thread.sleep(50);
                    }
                } catch (IOException | InterruptedException e) {
                    // the client gave up
                }
                return;
            }
            int status = status(path, first);
            byte[] body = ("status " + status).getBytes(StandardCharsets.UTF_8);
            if (status == 429) {
                exchange.getResponseHeaders().add("Retry-After", path.startsWith("/busy-long") ? "3600" : "0");
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    /// The answer to a request; `first` is true for the first request the server got.
    private static int status(String path, boolean first) {
        if (path.startsWith("/blocked")) {
            return 403;
        }
        if (path.startsWith("/first-blocked")) {
            return first ? 403 : 200;
        }
        if (path.startsWith("/busy")) {
            return 429;
        }
        if (path.startsWith("/first-busy")) {
            return first ? 429 : 200;
        }
        return path.startsWith("/failing") ? 503 : 200;
    }

    @AfterEach
    void stop() throws InterruptedException {
        release.countDown();
        server.stop(0);
        handlers.shutdownNow();
        dir.close();
    }

    /// Timeouts and waits, with 1 ms before retrying after a server error.
    private static Http.Timing timing(Duration timeout, Duration exchangeLimit, Duration slowDownDelay,
            Duration outageDelay, Duration outageLimit) {
        return new Http.Timing(timeout, exchangeLimit, Duration.ofMillis(1), slowDownDelay, outageDelay, outageLimit);
    }

    private URI uri(String path) {
        return URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + path);
    }

    /// A server that accepts connections and resets them at once: a network failure, as the client sees it.
    private static ServerSocket hangingUp() throws IOException {
        ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().start(() -> {
            while (true) {
                try (Socket accepted = socket.accept()) {
                    accepted.setSoLinger(true, 0);
                } catch (IOException e) {
                    return;
                }
            }
        });
        return socket;
    }

    private static URI uri(ServerSocket socket, String path) {
        return URI.create("http://" + socket.getInetAddress().getHostAddress() + ":" + socket.getLocalPort() + path);
    }

    private Path lockFile() {
        return dir.resolve("cache").resolve(Pacer.LOCK_FILE);
    }

    /// The end of the stop the marker next to the lock file records, in epoch milliseconds.
    private long stopUntil() throws IOException {
        return Long.parseLong(Files.readAllLines(lockFile().resolveSibling(Pacer.STOP_FILE)).getFirst());
    }

    /// `epochMillis` is about `expected` from now (within a minute).
    private static void assertAbout(Duration expected, long epochMillis) {
        long expectedMillis = Instant.now().plus(expected).toEpochMilli();
        assertTrue(Math.abs(epochMillis - expectedMillis) < 60_000, "expected about " + expected + " from now, got "
                + (epochMillis - System.currentTimeMillis()) + " ms");
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    @Test
    void requestsArePausedApart() throws IOException {
        try (Http http = new Http(4, false, Pacer.local(Pacer.Pause.parse("0.2-0.3")))) {
            for (int i = 0; i < 3; i++) {
                assertEquals(200, http.get(uri("/ok/" + i)).status());
            }
            assertEquals(3, http.requestCount());
            assertTrue(http.pacer().paused().toMillis() >= 2 * 180, "paused " + http.pacer().paused());
        }
        for (int i = 1; i < arrivals.size(); i++) {
            long gapMillis = (arrivals.get(i) - arrivals.get(i - 1)) / 1_000_000;
            assertTrue(gapMillis >= 180, "requests " + (i - 1) + " and " + i + " only " + gapMillis + " ms apart");
        }
    }

    @Test
    void aRefusalStopsAllFurtherRequests() throws IOException {
        try (Http http = new Http(4, false, Pacer.none())) {
            assertEquals(200, http.get(uri("/ok/before")).status());
            assertEquals(403, http.get(uri("/blocked/a.jar")).status());

            Http.StoppedException stopped =
                    assertThrows(Http.StoppedException.class, () -> http.get(uri("/ok/after")));
            assertTrue(stopped.getMessage().contains("HTTP 403 for " + uri("/blocked/a.jar")), stopped.getMessage());
            assertThrows(Http.StoppedException.class, () -> http.getRange(uri("/ok/range"), "bytes=-10"));
            assertEquals(2, http.requestCount());
            assertTrue(http.stopped());
            assertTrue(http.stopReason().orElseThrow().startsWith("HTTP 403 for "), http.stopReason().toString());
        }
        assertEquals(List.of("/ok/before", "/blocked/a.jar"), paths, "nothing was sent after the 403");
    }

    @Test
    void aRefusalWithEightThreadsWaitingLetsNoOtherRequestOutInAnyCommand() throws Exception {
        List<Object> outcomes = new CopyOnWriteArrayList<>();
        long start = System.nanoTime();
        try (Http http = new Http(8, false, Pacer.shared(Pacer.Pause.parse("0.5"), lockFile()), FAST)) {
            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int id = t;
                threads.add(Thread.ofVirtual().start(() -> {
                    try {
                        outcomes.add(http.get(uri("/first-blocked/" + id)).status());
                    } catch (IOException e) {
                        outcomes.add(e);
                    }
                }));
            }
            for (Thread thread : threads) {
                thread.join();
            }
            assertTrue(http.stopped());
        }
        assertEquals(1, paths.size(), "the server got one request: " + paths);
        assertEquals(1, outcomes.stream().filter(outcome -> Integer.valueOf(403).equals(outcome)).count(),
                outcomes.toString());
        assertEquals(7, outcomes.stream().filter(Http.StoppedException.class::isInstance).count(),
                outcomes.toString());
        // seven pauses of 0.5 s would take 3.5 s
        assertTrue(millisSince(start) < 2500, "the refused requests waited: " + millisSince(start) + " ms");

        // the other commands on this cache learn it from the stop marker, which holds for an hour
        Path stopFile = lockFile().resolveSibling(Pacer.STOP_FILE);
        assertAbout(Http.REFUSAL_STOP, stopUntil());
        assertTrue(Files.readAllLines(stopFile).get(1).startsWith("HTTP 403 for " + uri("/first-blocked/")));
        String nextStart = Files.readString(lockFile());
        try (Http other = new Http(1, false, Pacer.shared(Pacer.Pause.parse("0.5"), lockFile()), FAST)) {
            Http.StoppedException refused =
                    assertThrows(Http.StoppedException.class, () -> other.get(uri("/ok/other-command")));
            assertTrue(refused.getMessage().contains(stopFile.toRealPath() + ": delete that file"),
                    refused.getMessage());
            assertTrue(other.stopped());
            assertEquals(0, other.requestCount());
        }
        assertEquals(nextStart, Files.readString(lockFile()), "a refusal sends nothing, so it sets no next start");
        assertEquals(1, paths.size());

        // deleting the marker ends the stop for the commands that start afterwards
        Files.delete(stopFile);
        try (Http again = new Http(1, false, Pacer.shared(Pacer.Pause.parse("0.01"), lockFile()), FAST)) {
            assertEquals(200, again.get(uri("/ok/again")).status());
        }
    }

    @Test
    void aTooManyRequestsAnswerHoldsTheNextRequestOfEveryThreadAndCommand() throws Exception {
        Http.Timing timing = timing(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMillis(500),
                Duration.ofMillis(10), Duration.ofHours(1));
        List<Object> outcomes = new CopyOnWriteArrayList<>();
        try (Http http = new Http(4, false, Pacer.shared(Pacer.Pause.parse("0.02"), lockFile()), timing);
                Http other = new Http(1, false, Pacer.shared(Pacer.Pause.parse("0.02"), lockFile()), timing)) {
            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                int id = t;
                threads.add(Thread.ofVirtual().start(() -> {
                    try {
                        outcomes.add(http.get(uri("/first-busy/" + id)).status());
                    } catch (IOException e) {
                        outcomes.add(e);
                    }
                }));
            }
            while (paths.isEmpty()) {
                Thread.sleep(5);
            }
            // another command on the same cache, once the 429 is in
            assertEquals(200, other.get(uri("/ok/other-command")).status());
            for (Thread thread : threads) {
                thread.join();
            }
            assertFalse(http.stopped());
        }
        assertEquals(List.of(200, 200, 200, 200), outcomes);
        assertEquals(6, paths.size(), paths.toString());
        List<Long> sorted = arrivals.stream().sorted().toList();
        for (int i = 1; i < sorted.size(); i++) {
            long afterTheFirst = (sorted.get(i) - sorted.getFirst()) / 1_000_000;
            assertTrue(afterTheFirst >= 480, "request " + i + " came " + afterTheFirst + " ms after the 429");
        }
    }

    @Test
    void threeTooManyRequestsAnswersInARowStopTheRequests() throws IOException {
        try (Http http = new Http(1, false, Pacer.shared(Pacer.Pause.parse("0.01"), lockFile()), FAST)) {
            Http.StoppedException stopped = assertThrows(Http.StoppedException.class, () -> http.get(uri("/busy")));
            assertTrue(stopped.getMessage().contains("3 in a row"), stopped.getMessage());
            assertThrows(Http.StoppedException.class, () -> http.get(uri("/ok")));
        }
        assertEquals(List.of("/busy", "/busy", "/busy"), paths);
        // no Retry-After worth the name: the others stop for 15 minutes
        assertAbout(Http.TOO_MANY_REQUESTS_STOP, stopUntil());
    }

    @Test
    void aTooManyRequestsAnswerAskingForMoreThanTenMinutesStopsAtOnce() throws IOException {
        try (Http http = new Http(1, false, Pacer.shared(Pacer.Pause.parse("0.01"), lockFile()), FAST)) {
            Http.StoppedException stopped =
                    assertThrows(Http.StoppedException.class, () -> http.get(uri("/busy-long")));
            assertTrue(stopped.getMessage().contains("Retry-After 3600 s"), stopped.getMessage());
        }
        assertEquals(List.of("/busy-long"), paths);
        assertAbout(Duration.ofHours(1), stopUntil());
    }

    @Test
    void aStreamOfServerErrorsStopsTheRequests() throws IOException {
        try (Http http = new Http(1, false, Pacer.none(), FAST)) {
            assertEquals(503, http.get(uri("/failing/a")).status());
            // any other answer ends the series
            assertEquals(200, http.get(uri("/ok")).status());
            assertEquals(503, http.get(uri("/failing/b")).status());
            assertFalse(http.stopped());
            Http.StoppedException stopped =
                    assertThrows(Http.StoppedException.class, () -> http.get(uri("/failing/c")));
            assertTrue(stopped.getMessage().contains("10 server errors in a row"), stopped.getMessage());
            assertTrue(http.stopped());
            assertEquals(17, http.requestCount());
        }
        assertEquals(6 + 1 + 6 + 4, paths.size(), paths.toString());
    }

    @Test
    void networkFailuresDelayTheNextRequestsInsteadOfStoppingThem() throws Exception {
        Http.Timing timing = timing(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMillis(10),
                Duration.ofMillis(40), Duration.ofHours(1));
        try (ServerSocket hangingUp = hangingUp();
                Http http = new Http(1, false, Pacer.shared(Pacer.Pause.parse("0.01"), lockFile()), timing);
                Http other = new Http(1, false, Pacer.shared(Pacer.Pause.parse("0.01"), lockFile()), timing)) {
            URI gone = uri(hangingUp, "/gone");
            long start = System.nanoTime();
            IOException failure = assertThrows(IOException.class, () -> http.get(gone));
            long millis = millisSince(start);
            assertFalse(failure instanceof Http.StoppedException, failure.toString());
            assertTrue(failure.getMessage().contains("failed after 6 attempts"), failure.getMessage());
            // 40 + 80 + 160 + 320 + 640 ms between the attempts, give or take the resolution of the clock
            assertTrue(millis >= 1240 - 5 * 16, "6 attempts in " + millis + " ms");
            assertEquals(6, http.requestCount(), "failed exchanges count as requests");
            assertFalse(http.stopped());
            // another command on the same cache waits too: 1280 ms after the sixth failure
            long otherStart = System.nanoTime();
            assertEquals(200, other.get(uri("/ok/other-command")).status());
            assertTrue(millisSince(otherStart) >= 1000, "the other command waited " + millisSince(otherStart) + " ms");
            assertEquals(200, http.get(uri("/ok/after")).status(), "the client still works");
        }
    }

    @Test
    void networkFailuresStopTheRequestsOnlyWhenManyGoOnForLong() throws Exception {
        try (ServerSocket hangingUp = hangingUp()) {
            URI gone = uri(hangingUp, "/gone");
            // many failures in a row, but within moments: no stop before they have gone on for an hour
            try (Http http = new Http(1, false, Pacer.none(), timing(Duration.ofSeconds(5), Duration.ofSeconds(30),
                    Duration.ofMillis(10), Duration.ZERO, Duration.ofHours(1)))) {
                for (int i = 0; i < 2; i++) {
                    IOException failure = assertThrows(IOException.class, () -> http.get(gone));
                    assertFalse(failure instanceof Http.StoppedException, failure.toString());
                }
                assertEquals(12, http.requestCount());
                assertFalse(http.stopped(), "12 failures in a row are no reason to stop within the hour");
            }
            // failures for longer than the limit, but few of them: no stop either
            try (Http http = new Http(1, false, Pacer.none(), timing(Duration.ofSeconds(5), Duration.ofSeconds(30),
                    Duration.ofMillis(10), Duration.ofMillis(20), Duration.ofMillis(1)))) {
                assertThrows(IOException.class, () -> http.get(gone));
                assertFalse(http.stopped(), "6 failures in a row are no reason to stop, however long they took");
            }
            try (Http http = new Http(1, false, Pacer.none(), timing(Duration.ofSeconds(5), Duration.ofSeconds(30),
                    Duration.ofMillis(10), Duration.ofMillis(1), Duration.ofMillis(300)))) {
                IOException first = assertThrows(IOException.class, () -> http.get(gone));
                assertFalse(first instanceof Http.StoppedException, first.toString());
                assertFalse(http.stopped(), "6 failures in 31 ms are no reason to stop");
                // 1 + 2 + ... + 256 ms after the first failure, the tenth one has gone on for longer than 300 ms
                Http.StoppedException stopped = assertThrows(Http.StoppedException.class, () -> http.get(gone));
                assertTrue(stopped.getMessage().contains("10 network failures in a row"), stopped.getMessage());
                assertEquals(10, http.requestCount());
            }
        }
    }

    @Test
    void aStalledResponseIsCutOffAndLetsTheOtherRequestsGo() throws Exception {
        Http.Timing timing = timing(Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofMillis(10),
                Duration.ofMillis(10), Duration.ofHours(1));
        AtomicReference<Object> stalled = new AtomicReference<>();
        AtomicReference<Object> other = new AtomicReference<>();
        try (Http http = new Http(2, false, Pacer.shared(Pacer.Pause.parse("0.05"), lockFile()), timing)) {
            Thread stalling = Thread.ofVirtual().start(() -> stalled.set(status(http, uri("/stall-once"))));
            Thread meanwhile = null;
            try {
                while (paths.isEmpty()) {
                    Thread.sleep(5);
                }
                // the server holds its first answer until the end of the test: unless that exchange is cut off, it
                // keeps the turn and no other request goes out
                long start = System.nanoTime();
                meanwhile = Thread.ofVirtual().start(() -> other.set(status(http, uri("/ok/meanwhile"))));
                meanwhile.join(10_000);
                assertEquals(200, other.get(), "the other request, after " + millisSince(start) + " ms");
                stalling.join(10_000);
                assertEquals(200, stalled.get(), "the second attempt gets the answer");
            } finally {
                release.countDown();
                stalling.join(10_000);
                if (meanwhile != null) {
                    meanwhile.join(10_000);
                }
            }
            assertFalse(http.stopped(), "a timeout is a network failure, not a stop");
            assertEquals(3, http.requestCount());
        }
        assertEquals(List.of("/ok/meanwhile", "/stall-once", "/stall-once"), paths.stream().sorted().toList());
    }

    @Test
    void aResponseThatStallsAtEveryAttemptFailsWithATimeout() throws IOException {
        Http.Timing timing = timing(Duration.ofMillis(400), Duration.ofSeconds(30), Duration.ofMillis(10),
                Duration.ofMillis(10), Duration.ofHours(1));
        try (Http http = new Http(1, false, Pacer.none(), timing)) {
            try {
                IOException failure = assertThrows(IOException.class, () -> http.get(uri("/stall-always")));
                assertInstanceOf(HttpTimeoutException.class, failure.getCause(), failure.toString());
                assertTrue(failure.getMessage().contains("failed after 6 attempts"), failure.getMessage());
                assertTrue(failure.getMessage().contains("no data for 0.4 s"), failure.getMessage());
                assertFalse(http.stopped());
            } finally {
                release.countDown();
            }
        }
        assertEquals(6, paths.size(), paths.toString());
    }

    /// The status of a GET, or the exception it threw.
    private static Object status(Http http, URI uri) {
        try {
            return http.get(uri).status();
        } catch (IOException | RuntimeException e) {
            return e;
        }
    }

    @Test
    void anExchangeEndsAfterItsLimitHoweverSteadilyTheBodyArrives() throws IOException {
        // the silence limit is far above the gaps between the body's bytes, so only the exchange limit cuts it off
        Http.Timing timing = timing(Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofMillis(10),
                Duration.ofMillis(10), Duration.ofHours(1));
        long start = System.nanoTime();
        try (Http http = new Http(1, false, Pacer.none(), timing)) {
            Http.Response response = http.get(uri("/trickle"));
            assertEquals(200, response.status());
            assertEquals("status 200", new String(response.body(), StandardCharsets.UTF_8));
        }
        assertEquals(List.of("/trickle", "/trickle"), paths);
        // the trickle alone lasts 5 s
        assertTrue(millisSince(start) >= 1000 && millisSince(start) < 4500, millisSince(start) + " ms");
    }

    @Test
    void anInterruptedExchangeStillSetsTheNextStart() throws Exception {
        try (Http http = new Http(1, false, Pacer.shared(Pacer.Pause.parse("0.2"), lockFile()), FAST)) {
            assertEquals(200, http.get(uri("/ok/warm")).status());
            long before = Long.parseLong(Files.readString(lockFile()).strip());
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread waiting = Thread.ofPlatform().start(() -> {
                try {
                    http.get(uri("/stall-always"));
                } catch (Throwable e) {
                    failure.set(e);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            try {
                while (paths.size() < 2) {
                    Thread.sleep(5);
                }
                Thread.sleep(100);
                waiting.interrupt();
                waiting.join(10_000);
            } finally {
                release.countDown();
                waiting.join(10_000);
            }
            assertInstanceOf(InterruptedIOException.class, failure.get());
            assertTrue(interrupted.get(), "the interrupt stays set");
            long after = Long.parseLong(Files.readString(lockFile()).strip());
            assertTrue(after > before, "the interrupted request set the next start: " + before + " then " + after);
            assertFalse(http.stopped());
        }
    }

    @Test
    void aLockFileThatCannotBeUsedStopsTheRequestsAtOnce() throws Exception {
        // a folder where the lock file should be
        Files.createDirectories(lockFile());
        long start = System.nanoTime();
        try (Http http = new Http(1, false, Pacer.shared(Pacer.Pause.parse("0.05"), lockFile()))) {
            Http.StoppedException stopped = assertThrows(Http.StoppedException.class, () -> http.get(uri("/ok")));
            assertInstanceOf(Pacer.LockFileException.class, stopped.getCause());
            assertThrows(Http.StoppedException.class, () -> http.get(uri("/ok/again")));
            assertTrue(http.stopped());
        }
        // retried like a network failure, it took 31 s
        assertTrue(millisSince(start) < 2000, millisSince(start) + " ms");
        assertEquals(List.of(), paths);
    }

    @Test
    void mavenCentralNeedsAPause() throws IOException {
        URI central = URI.create("https://repo1.maven.org/maven2/");
        for (String pause : List.of("0", "0-0.5", "0-2")) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> Http.checkPause(Pacer.Pause.parse(pause), central));
            assertTrue(refused.getMessage().contains("at least 0.001 s"), refused.getMessage());
        }
        Http.checkPause(Pacer.Pause.parse("0.001"), central);
        Http.checkPause(Pacer.Pause.DEFAULT, URI.create("https://repo.maven.apache.org/maven2"));
        assertThrows(IllegalArgumentException.class,
                () -> Http.checkPause(Pacer.Pause.NONE, URI.create("https://REPO.maven.apache.org/maven2/")));
        Http.checkPause(Pacer.Pause.NONE, URI.create("https://maven.example.org/releases/"));
        assertTrue(Http.isMavenCentral(URI.create("https://repo1.maven.org:443/maven2/")));
        // the fully qualified name, with its final dot, is the same host
        assertTrue(Http.isMavenCentral(URI.create("https://repo1.maven.org./maven2/")));
        assertThrows(IllegalArgumentException.class,
                () -> Http.checkPause(Pacer.Pause.NONE, URI.create("https://repo1.maven.org./maven2/")));
        assertTrue(Http.isMavenCentral(URI.create("https://search.maven.org/solrsearch/select")));
        assertTrue(Http.isMavenCentral(URI.create("https://central.sonatype.com/artifact/io.quarkus/quarkus-bom")));
        assertFalse(Http.isMavenCentral(URI.create("https://repo1.maven.org.example.com/maven2/")));
        assertFalse(Http.isMavenCentral(uri("/maven2/")));

        // any other caller is stopped before its first request to Maven Central (this check sends nothing)
        URI pom = central.resolve("io/quarkus/quarkus-bom/3.0.0.Final/quarkus-bom-3.0.0.Final.pom");
        try (Http http = new Http(1, false, Pacer.none())) {
            assertThrows(Http.StoppedException.class, () -> http.refuseTooShortPause(pom));
            assertTrue(http.stopped());
            assertEquals(0, http.requestCount());
        }
        try (Http http = new Http(1, false, Pacer.local(Pacer.Pause.parse("2-3")))) {
            http.refuseTooShortPause(pom);
            assertFalse(http.stopped());
        }
    }

    @Test
    void aRequestToMavenCentralWithTooShortAPauseStopsTheClientBeforeThePacerIsAsked() throws IOException {
        // a folder where the lock file should be: the pacer could not send the request either, so this test never
        // reaches Maven Central, whatever the client does
        Files.createDirectories(lockFile());
        URI central = URI.create("https://repo1.maven.org/maven2/io/quarkus/quarkus-bom/maven-metadata.xml");
        Pacer.Pause tooShort = new Pacer.Pause(Duration.ofNanos(500_000), Duration.ofNanos(500_000));
        try (Http http = new Http(1, false, Pacer.shared(tooShort, lockFile()), FAST)) {
            Http.StoppedException stopped = assertThrows(Http.StoppedException.class, () -> http.get(central));
            assertTrue(stopped.getMessage().contains("is too short for Maven Central"), stopped.getMessage());
            assertTrue(http.stopped());
            assertThrows(Http.StoppedException.class, () -> http.getRange(central, "bytes=-10"));
            // and so is every later request, to any repository
            assertThrows(Http.StoppedException.class, () -> http.get(uri("/ok")));
            assertEquals(0, http.requestCount());
        }
        assertEquals(List.of(), paths);
    }
}
