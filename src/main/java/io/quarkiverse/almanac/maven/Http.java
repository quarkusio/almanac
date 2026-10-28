package io.quarkiverse.almanac.maven;

import io.quarkiverse.almanac.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

/// Polite HTTP client for Maven Central: HTTP/2, redirects followed, at most `concurrency` requests in flight and,
/// through its [Pacer], one at a time with a random pause between them.
///
/// - Every exchange ends after 60 s without data (until the response headers, then between two parts of the body)
///   and after 15 minutes in all, so that a stalled response cannot hold the pacer's turn.
/// - Up to 6 attempts. A server error (5xx) is retried after 1, 2, 4, 8, 16 s, or its `Retry-After`. A 429 makes
///   the next request of every thread, and of every command on the same cache, wait for its `Retry-After`, at least
///   30 s, doubled for each 429 in a row. A network failure (no connection, no answer, a broken exchange) makes them
///   wait 10 s, doubled for each failure in a row up to 10 minutes: an outage costs few requests, and a short one
///   (or a laptop's sleep) does not end a long run.
/// - A 403 (the repository refusing this client), 3 429s in a row, a 429 asking to wait longer than 10 minutes or
///   one that outlasts the retries, 10 server errors in a row, and network failures that go on for an hour (10 in a
///   row at least) stop all further requests: they fail at once with [StoppedException], which is never retried, and
///   so do the requests of every other command on the same cache, through the pacer's stop marker: for an hour after
///   a 403, for the `Retry-After` but at least 15 minutes after 429s, for 10 minutes after failures.
/// - Requests to Maven Central need a pause of at least [#CENTRAL_MIN_PAUSE] (see [#checkPause]).
///
/// In offline mode every request fails.
public final class Http implements AutoCloseable {

    public static final String USER_AGENT = "quarkus-almanac/0.1 (+https://github.com/quarkusio/almanac)";

    /// The shortest pause between two requests to Maven Central: the shortest of the default pauses.
    public static final Duration CENTRAL_MIN_PAUSE = Duration.ofMillis(500);

    /// The hosts of Maven Central: its repository under both names, its search and its portal.
    private static final Set<String> CENTRAL_HOSTS = Set.of("repo1.maven.org", "repo.maven.apache.org",
            "search.maven.org", "central.sonatype.com");
    private static final int MAX_ATTEMPTS = 6;
    /// 429 answers in a row that stop the requests.
    static final int MAX_TOO_MANY_REQUESTS = 3;
    /// Server errors (5xx) in a row that stop the requests.
    static final int MAX_SERVER_ERRORS = 10;
    /// Network failures in a row that stop the requests, once they have gone on for [Timing#outageLimit].
    static final int MAX_NETWORK_FAILURES = 10;
    /// How long a 403 stops the other commands on the same cache.
    static final Duration REFUSAL_STOP = Duration.ofHours(1);
    /// How long 429 answers stop them at least.
    static final Duration TOO_MANY_REQUESTS_STOP = Duration.ofMinutes(15);
    /// How long server errors or network failures stop them.
    static final Duration FAILURES_STOP = Duration.ofMinutes(10);
    /// The longest `Retry-After` taken as given.
    private static final Duration MAX_RETRY_AFTER = Duration.ofDays(1);
    private static final Duration PROGRESS_INTERVAL = Duration.ofMinutes(1);

    /// A completed exchange; non-2xx statuses are returned, not thrown.
    public record Response(int status, byte[] body, HttpHeaders headers) {

        public Optional<String> header(String name) {
            return headers.firstValue(name);
        }
    }

    /// Thrown, without retries, for every request once requests stopped (see the class comment), and by work that
    /// must not finish with what a refused request left out.
    public static final class StoppedException extends IOException {

        private static final long serialVersionUID = 1L;

        public StoppedException(String message) {
            super(message);
        }

        public StoppedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /// Timeouts and waits; tests shorten them.
    ///
    /// @param timeout       the longest silence: until the response headers, then between two parts of the body
    /// @param exchangeLimit the longest exchange, however steadily the body arrives
    /// @param retryDelay    the wait before retrying after a server error, doubled for each further attempt
    /// @param slowDownDelay the shortest wait of the next request after a 429, doubled for each 429 in a row
    /// @param outageDelay   the wait of the next request after a network failure, doubled for each failure in a row
    ///                      up to [Pacer#MAX_WAIT]
    /// @param outageLimit   how long network failures in a row may go on (on the wall clock, which a laptop's sleep
    ///                      advances too) before they stop the requests
    record Timing(Duration timeout, Duration exchangeLimit, Duration retryDelay, Duration slowDownDelay,
            Duration outageDelay, Duration outageLimit) {

        static final Timing DEFAULT = new Timing(Duration.ofSeconds(60), Duration.ofMinutes(15), Duration.ofSeconds(1),
                Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofHours(1));
    }

    private record Exchange(HttpResponse<byte[]> response, long nanos) {
    }

    private final HttpClient client;
    private final Semaphore permits;
    private final int concurrency;
    private final boolean offline;
    private final Pacer pacer;
    private final Timing timing;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private final AtomicLong lastProgress = new AtomicLong(System.nanoTime());
    private final AtomicInteger tooManyRequests = new AtomicInteger();
    private final AtomicInteger serverErrors = new AtomicInteger();
    private final AtomicInteger networkFailures = new AtomicInteger();
    /// When the first of the network failures in a row happened, in epoch milliseconds.
    private final AtomicLong outageStart = new AtomicLong();
    /// Why requests stopped, null while they may go out.
    private volatile String stopped;

    /// A client that pauses between its own requests ([Pacer.Pause#DEFAULT]).
    public Http(int concurrency, boolean offline) {
        this(concurrency, offline, Pacer.local(Pacer.Pause.DEFAULT));
    }

    public Http(int concurrency, boolean offline, Pacer pacer) {
        this(concurrency, offline, pacer, Timing.DEFAULT);
    }

    Http(int concurrency, boolean offline, Pacer pacer, Timing timing) {
        this.concurrency = Math.max(1, concurrency);
        this.offline = offline;
        this.pacer = pacer;
        this.timing = timing;
        this.permits = new Semaphore(this.concurrency, true);
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timing.timeout())
                .build();
    }

    /// Whether a URI points to Maven Central (`repo1.maven.org`, `repo.maven.apache.org`, `search.maven.org`,
    /// `central.sonatype.com`), in any case and also as a fully qualified name with its final dot
    /// (`repo1.maven.org.`).
    public static boolean isMavenCentral(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        String name = host.toLowerCase(Locale.ROOT);
        return CENTRAL_HOSTS.contains(name.endsWith(".") ? name.substring(0, name.length() - 1) : name);
    }

    /// Refuses a pause too short for Maven Central, whose requests need at least [#CENTRAL_MIN_PAUSE] between them;
    /// any pause, none included, is fine for another repository.
    ///
    /// @throws IllegalArgumentException for a shorter pause when `repository` is Maven Central
    public static void checkPause(Pacer.Pause pause, URI repository) {
        if (isMavenCentral(repository) && pause.min().compareTo(CENTRAL_MIN_PAUSE) < 0) {
            throw new IllegalArgumentException(tooShort(pause, repository));
        }
    }

    public int concurrency() {
        return concurrency;
    }

    public boolean offline() {
        return offline;
    }

    public Pacer pacer() {
        return pacer;
    }

    /// Number of requests sent so far: every attempt, retries and failed exchanges included.
    public long requestCount() {
        return requests.get();
    }

    /// Body bytes received so far (as transferred, i.e. before gzip decoding).
    public long bytesReceived() {
        return bytesReceived.get();
    }

    /// Whether requests stopped: every request since failed with [StoppedException], and so will every later one.
    public boolean stopped() {
        return stopped != null;
    }

    /// Why requests stopped; empty while they may go out.
    public Optional<String> stopReason() {
        return Optional.ofNullable(stopped);
    }

    /// GET of a whole resource; gzip transfer encoding is accepted and decoded transparently.
    public Response get(URI uri) throws IOException {
        return send(request(uri).header("Accept-Encoding", "gzip").build(), null);
    }

    /// GET of a byte range, e.g. `bytes=-65536` or `bytes=100-199`. A server that ignores ranges answers
    /// `200` with the whole body; callers must handle both.
    public Response getRange(URI uri, String range) throws IOException {
        return send(request(uri).header("Range", range).build(), range);
    }

    @Override
    public void close() {
        client.close();
    }

    private HttpRequest.Builder request(URI uri) throws IOException {
        if (offline) {
            throw new IOException("offline mode: not fetching " + uri);
        }
        refuseTooShortPause(uri);
        return HttpRequest.newBuilder(uri).timeout(timing.timeout()).header("User-Agent", USER_AGENT).GET();
    }

    /// Stops the requests at the first one to Maven Central when the pacer's pause is too short for it: the commands
    /// check `--pause` up front ([#checkPause]), this catches every other caller before anything is sent.
    void refuseTooShortPause(URI uri) throws StoppedException {
        if (isMavenCentral(uri) && pacer.pause().min().compareTo(CENTRAL_MIN_PAUSE) < 0) {
            String reason = tooShort(pacer.pause(), uri);
            if (stop(reason)) {
                Log.error("%s: no requests are sent", reason);
            }
            checkNotStopped(uri);
        }
    }

    private Response send(HttpRequest request, String range) throws IOException {
        for (int attempt = 1; ; attempt++) {
            Duration wait = Duration.ZERO;
            try {
                Response response = sendOnce(request, range, attempt);
                int status = response.status();
                if (!isRetryable(status) || attempt == MAX_ATTEMPTS) {
                    return response;
                }
                // a 429 delayed the next request through the pacer already; a stop fails the next attempt at once
                if (status != 429) {
                    wait = retryAfter(response.headers()).map(Http::atMostMaxWait).orElse(backoff(attempt));
                }
                Log.debug("HTTP %d for %s, retry %d/%d in %s", status, request.uri(), attempt, MAX_ATTEMPTS - 1,
                        seconds(wait));
            } catch (IOException e) {
                if (e instanceof StoppedException || Thread.currentThread().isInterrupted()) {
                    throw e;
                }
                if (attempt == MAX_ATTEMPTS) {
                    throw new IOException("GET " + request.uri() + " failed after " + attempt + " attempts: " + e, e);
                }
                // a network failure delayed the next request through the pacer already
                Log.debug("%s for %s, retry %d/%d", e, request.uri(), attempt, MAX_ATTEMPTS - 1);
            }
            sleep(wait);
        }
    }

    private Response sendOnce(HttpRequest request, String range, int attempt) throws IOException {
        URI uri = request.uri();
        checkNotStopped(uri);
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while waiting for a connection slot");
        }
        Exchange exchange;
        try {
            exchange = pacer.run(stop -> ready(uri, stop), turn -> exchange(request, turn, attempt));
        } catch (Pacer.LockFileException e) {
            // without its files the pacer cannot keep the pauses between commands: no request may go out
            if (stop(e.getMessage())) {
                Log.error("%s: no more requests are sent", e.getMessage());
            }
            throw new StoppedException("not requesting " + uri + ": " + e.getMessage(), e);
        } finally {
            permits.release();
        }
        HttpResponse<byte[]> response = exchange.response();
        byte[] body = response.body();
        bytesReceived.addAndGet(body.length);
        Log.debug("GET %s%s -> %d (%d bytes, %d ms)", uri, range == null ? "" : " [" + range + "]",
                response.statusCode(), body.length, exchange.nanos() / 1_000_000);
        logProgress();
        if (response.headers().firstValue("Content-Encoding").filter("gzip"::equalsIgnoreCase).isPresent()) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
                body = gzip.readAllBytes();
            }
        }
        return new Response(response.statusCode(), body, response.headers());
    }

    /// In the pacer's turn, before its pause: no request once requests stopped, here or, as the pacer's stop marker
    /// says, in another command on the same cache.
    private void ready(URI uri, Pacer.Stop stop) throws StoppedException {
        if (stop != null && stop(stop.describe())) {
            Log.error("no requests are sent: %s", stop.describe());
        }
        checkNotStopped(uri);
    }

    /// One exchange, in the pacer's turn: counted, bounded in time, and its outcome taken into account (waits, stops)
    /// before the next request may start.
    private Exchange exchange(HttpRequest request, Pacer.Turn turn, int attempt) throws IOException {
        long start = System.nanoTime();
        requests.incrementAndGet();
        HttpResponse<byte[]> response;
        try {
            response = await(request);
        } catch (IOException e) {
            if (!Thread.currentThread().isInterrupted()) {
                networkFailure(turn, request.uri(), e);
            }
            throw e;
        }
        answered(turn, request.uri(), response, attempt);
        return new Exchange(response, System.nanoTime() - start);
    }

    /// Sends a request and waits for the whole response, at most [Timing#timeout] without progress and
    /// [Timing#exchangeLimit] in all; then the exchange is cancelled and fails with an [HttpTimeoutException].
    private HttpResponse<byte[]> await(HttpRequest request) throws IOException {
        Progress progress = new Progress();
        long start = progress.last();
        CompletableFuture<HttpResponse<byte[]>> response = client.sendAsync(request, progress.handler());
        try {
            while (true) {
                long now = System.nanoTime();
                long silenceLeft = timing.timeout().toNanos() - (now - progress.last());
                long exchangeLeft = timing.exchangeLimit().toNanos() - (now - start);
                if (silenceLeft <= 0 || exchangeLeft <= 0) {
                    response.cancel(true);
                    throw new HttpTimeoutException(silenceLeft <= 0
                            ? "no data for " + seconds(timing.timeout())
                            : "not complete after " + seconds(timing.exchangeLimit()));
                }
                try {
                    return response.get(Math.min(silenceLeft, exchangeLeft), TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    // look at the progress made meanwhile
                }
            }
        } catch (InterruptedException e) {
            response.cancel(true);
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted during GET " + request.uri());
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException cause) {
                throw cause;
            }
            throw new IOException("GET " + request.uri() + " failed: " + e.getCause(), e.getCause());
        }
    }

    /// Takes an answer into account: a 403 stops the requests; a 429 delays the next one, or stops them; a server
    /// error counts towards a stop; any other answer ends both series.
    private void answered(Pacer.Turn turn, URI uri, HttpResponse<?> response, int attempt) {
        int status = response.statusCode();
        networkFailures.set(0);
        if (status == 403) {
            stop(turn, REFUSAL_STOP, "HTTP 403 for " + uri, "the repository refuses this client");
            return;
        }
        if (status == 429) {
            tooManyRequests(turn, uri, retryAfter(response.headers()), attempt);
            return;
        }
        tooManyRequests.set(0);
        if (status / 100 == 5) {
            int inARow = serverErrors.incrementAndGet();
            if (inARow >= MAX_SERVER_ERRORS) {
                stop(turn, FAILURES_STOP, "HTTP " + status + " for " + uri + ", " + inARow
                        + " server errors in a row", "the repository keeps failing");
            }
            return;
        }
        serverErrors.set(0);
    }

    /// A 429: the next request of every thread and of every command on the same cache waits for the `Retry-After`,
    /// at least [Timing#slowDownDelay] doubled for each 429 in a row; 3 in a row, one that asks for longer than
    /// [Pacer#MAX_WAIT] and one that outlasts the retries stop the requests.
    private void tooManyRequests(Pacer.Turn turn, URI uri, Optional<Duration> retryAfter, int attempt) {
        int inARow = tooManyRequests.incrementAndGet();
        String status = "HTTP 429 for " + uri;
        String why = "the repository asks for fewer requests";
        Duration asked = retryAfter.orElse(Duration.ZERO);
        Duration stop = asked.compareTo(TOO_MANY_REQUESTS_STOP) > 0 ? asked : TOO_MANY_REQUESTS_STOP;
        if (asked.compareTo(Pacer.MAX_WAIT) > 0) {
            stop(turn, stop, status + " with Retry-After " + seconds(asked), why);
        } else if (inARow >= MAX_TOO_MANY_REQUESTS) {
            stop(turn, stop, status + ", " + inARow + " in a row", why);
        } else if (attempt == MAX_ATTEMPTS) {
            stop(turn, stop, status + " after " + attempt + " attempts", why);
        } else {
            Duration wait = doubled(timing.slowDownDelay(), inARow);
            wait = asked.compareTo(wait) > 0 ? asked : wait;
            turn.delayNext(wait);
            Log.warn("%s: %s; the next request waits %s", status, why, seconds(wait));
        }
    }

    /// A network failure (no connection, no answer, a broken exchange): the next request of every thread and of every
    /// command on the same cache waits, twice as long for each failure in a row, so that an outage costs few requests
    /// and only slows a long run down; failures that go on for [Timing#outageLimit] stop the requests.
    private void networkFailure(Pacer.Turn turn, URI uri, IOException e) {
        int inARow = networkFailures.incrementAndGet();
        long now = System.currentTimeMillis();
        if (inARow == 1) {
            outageStart.set(now);
        }
        Duration outage = Duration.ofMillis(now - outageStart.get());
        if (inARow >= MAX_NETWORK_FAILURES && outage.compareTo(timing.outageLimit()) >= 0) {
            stop(turn, FAILURES_STOP, "GET " + uri + ": " + e + ", " + inARow + " network failures in a row in "
                    + seconds(outage), "the repository cannot be reached");
            return;
        }
        Duration wait = doubled(timing.outageDelay(), inARow);
        turn.delayNext(wait);
        Log.warn("GET %s: %s; the next request waits %s (network failure %d in a row)", uri, e, seconds(wait),
                inARow);
    }

    private void checkNotStopped(URI uri) throws StoppedException {
        String reason = stopped;
        if (reason != null) {
            throw new StoppedException("not requesting " + uri + ": requests stopped after " + reason);
        }
    }

    /// Stops all further requests of this client and, through the pacer's stop marker, those of every other command
    /// on the same cache for `duration`.
    ///
    /// @param reason what stopped the requests, for messages and the stop marker
    /// @param why    what it means, for the log
    private void stop(Pacer.Turn turn, Duration duration, String reason, String why) {
        if (!stop(reason)) {
            return;
        }
        Optional<Pacer.Stop> shared = turn.stopAll(Instant.now().plus(duration), reason);
        Log.error("%s: %s, so no more requests are sent%s", reason, why, shared.map(stop -> "; no command on the "
                + "same cache sends any before " + stop.until() + " (recorded in " + stop.file() + ": delete that "
                + "file to allow requests earlier)").orElse(""));
    }

    /// Stops all further requests of this client; false when they had stopped already.
    private boolean stop(String reason) {
        synchronized (this) {
            if (stopped != null) {
                return false;
            }
            stopped = reason;
            return true;
        }
    }

    /// A progress line at most once a minute, for long runs.
    private void logProgress() {
        long now = System.nanoTime();
        long last = lastProgress.get();
        if (now - last >= PROGRESS_INTERVAL.toNanos() && lastProgress.compareAndSet(last, now)) {
            Log.info("HTTP: %d requests so far, %.1f MB received", requests.get(), bytesReceived.get() / 1e6);
        }
    }

    private Duration backoff(int attempt) {
        return doubled(timing.retryDelay(), attempt);
    }

    private static boolean isRetryable(int status) {
        return status == 429 || status / 100 == 5;
    }

    private static String tooShort(Pacer.Pause pause, URI uri) {
        return "a pause of " + pause + " s is too short for Maven Central (" + uri.getHost() + "), which needs at "
                + "least " + seconds(CENTRAL_MIN_PAUSE) + " between requests; shorter pauses, or none, are only for "
                + "other repositories";
    }

    /// `first`, doubled for each time after the first one, at most [Pacer#MAX_WAIT].
    private static Duration doubled(Duration first, int times) {
        return atMostMaxWait(first.multipliedBy(1L << Math.clamp(times - 1, 0, 20)));
    }

    private static Duration atMostMaxWait(Duration duration) {
        return duration.compareTo(Pacer.MAX_WAIT) > 0 ? Pacer.MAX_WAIT : duration;
    }

    /// `2 s`, `0.25 s`, `600 s`.
    private static String seconds(Duration duration) {
        return BigDecimal.valueOf(duration.toMillis(), 3).stripTrailingZeros().toPlainString() + " s";
    }

    /// The `Retry-After` of an answer, between zero and [#MAX_RETRY_AFTER].
    private static Optional<Duration> retryAfter(HttpHeaders headers) {
        return headers.firstValue("Retry-After").map(String::strip).flatMap(value -> {
            OptionalLong seconds = parseSeconds(value);
            if (seconds.isPresent()) {
                return Optional.of(Duration.ofSeconds(Math.min(seconds.getAsLong(), MAX_RETRY_AFTER.toSeconds())));
            }
            try {
                Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Optional.of(Duration.between(Instant.now(), at));
            } catch (DateTimeParseException e) {
                return Optional.empty();
            }
        }).map(d -> d.isNegative() ? Duration.ZERO : d.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : d);
    }

    private static OptionalLong parseSeconds(String value) {
        try {
            return OptionalLong.of(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    private static void sleep(Duration wait) throws InterruptedIOException {
        if (wait.isZero()) {
            return;
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted during retry backoff");
        }
    }

    /// When an exchange last made progress: its start, the response headers, then each part of the body.
    private static final class Progress {

        private final AtomicLong last = new AtomicLong(System.nanoTime());

        long last() {
            return last.get();
        }

        HttpResponse.BodyHandler<byte[]> handler() {
            return info -> {
                last.set(System.nanoTime());
                HttpResponse.BodySubscriber<byte[]> body = HttpResponse.BodySubscribers.ofByteArray();
                return new HttpResponse.BodySubscriber<>() {
                    @Override
                    public CompletionStage<byte[]> getBody() {
                        return body.getBody();
                    }

                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        body.onSubscribe(subscription);
                    }

                    @Override
                    public void onNext(List<ByteBuffer> item) {
                        last.set(System.nanoTime());
                        body.onNext(item);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        body.onError(throwable);
                    }

                    @Override
                    public void onComplete() {
                        body.onComplete();
                    }
                };
            };
        }
    }
}
