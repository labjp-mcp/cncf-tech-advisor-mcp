package io.mcp.cncf.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.mcp.cncf.client.LandscapeConfig;
import io.mcp.cncf.client.LandscapeSource;
import io.mcp.cncf.client.LandscapeUnavailableException;
import io.mcp.cncf.service.CncfDataRefreshService.Outcome;
import io.mcp.cncf.testing.LandscapeStub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cache in front of the download, with a clock the test moves and a source that counts.
 *
 * <p>This is the regression suite for the denial of service the read tools used to be:
 * every {@code search_cncf} downloaded the 3.8 MB {@code full.json}, because the freshness
 * check existed but gated nothing. Each test states how many downloads a sequence of calls
 * may cost, and the number is what matters.
 */
class CncfDataRefreshServiceCacheTest {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration BACKOFF = Duration.ofSeconds(30);
    private static final Duration FORCE_INTERVAL = Duration.ofSeconds(20);

    /** A clock that only moves when told to. */
    private static final class ManualClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-20T10:00:00Z"));

        void advance(Duration d) {
            now.updateAndGet(t -> t.plus(d));
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    /** Counts fetches; answers what it is told to. */
    private static final class CountingSource implements LandscapeSource {
        final AtomicInteger fetches = new AtomicInteger();
        final AtomicReference<String> body = new AtomicReference<>(LandscapeStub.fixture("landscape-current.json"));
        volatile Optional<String> etag = Optional.empty();
        volatile boolean answerNotModified;
        volatile RuntimeException failure;
        volatile Duration latency = Duration.ZERO;
        final AtomicReference<Optional<String>> lastEtagSeen = new AtomicReference<>(Optional.empty());

        @Override
        public Fetch fetch(Optional<String> knownEtag) {
            fetches.incrementAndGet();
            lastEtagSeen.set(knownEtag);
            if (!latency.isZero()) {
                try {
                    Thread.sleep(latency.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failure != null) {
                throw failure;
            }
            if (answerNotModified) {
                return Fetch.unchanged(knownEtag);
            }
            return Fetch.of(body.get(), etag);
        }
    }

    private static LandscapeConfig config() {
        return new LandscapeConfig() {
            @Override public String baseUrl() { return "http://127.0.0.1:1"; }
            @Override public Duration connectTimeout() { return Duration.ofSeconds(1); }
            @Override public Duration requestTimeout() { return Duration.ofSeconds(1); }
            @Override public int maxBytes() { return 1 << 20; }
            @Override public Duration cacheTtl() { return TTL; }
            @Override public Duration failureBackoff() { return BACKOFF; }
            @Override public Duration minForceInterval() { return FORCE_INTERVAL; }
        };
    }

    private final ManualClock clock = new ManualClock();
    private final CountingSource source = new CountingSource();
    private final CncfDataRefreshService service = new CncfDataRefreshService(source, config(), clock);

    @Test
    @DisplayName("regression: a hundred reads inside the TTL cost exactly one download")
    void readsInsideTtlDoNotDownload() {
        for (int i = 0; i < 100; i++) {
            assertThat(service.currentProjects()).isNotEmpty();
            clock.advance(Duration.ofSeconds(1));
        }

        assertThat(source.fetches).as("downloads for 100 reads").hasValue(1);
        assertThat(service.isDataFresh()).isTrue();
    }

    @Test
    @DisplayName("once the TTL elapses the next read downloads once, and the ones after it do not")
    void readAfterTtlDownloadsOnce() {
        service.currentProjects();
        clock.advance(TTL);
        service.currentProjects();
        service.currentProjects();

        assertThat(source.fetches).hasValue(2);
    }

    @Test
    @DisplayName("a failed download is not retried before the backoff, and the loaded catalogue is served meanwhile")
    void failureBacksOff() {
        service.currentProjects();
        clock.advance(TTL);
        source.failure = new LandscapeUnavailableException("The CNCF Landscape could not be reached.");

        for (int i = 0; i < 10; i++) {
            assertThat(service.currentProjects()).as("the stale catalogue is still served").isNotEmpty();
            clock.advance(Duration.ofSeconds(1));
        }
        assertThat(source.fetches).as("one failed attempt, then silence for the backoff").hasValue(2);
        assertThat(service.getLastError()).isEqualTo("The CNCF Landscape could not be reached.");

        clock.advance(BACKOFF);
        source.failure = null;
        service.currentProjects();
        assertThat(source.fetches).hasValue(3);
        assertThat(service.getLastError()).isNull();
    }

    @Test
    @DisplayName("a forced refresh inside the interval is throttled without a request; after it, it goes out")
    void forcedRefreshIsThrottled() {
        assertThat(service.forceRefresh()).isEqualTo(Outcome.UPDATED);
        for (int i = 0; i < 50; i++) {
            assertThat(service.forceRefresh()).isEqualTo(Outcome.THROTTLED);
        }
        assertThat(source.fetches).hasValue(1);

        clock.advance(FORCE_INTERVAL);
        assertThat(service.forceRefresh()).as("same bytes again").isEqualTo(Outcome.UNCHANGED);
        assertThat(source.fetches).hasValue(2);
    }

    @Test
    @DisplayName("a forced refresh sends the known validator and a 304 confirms the catalogue")
    void forcedRefreshIsConditional() {
        source.etag = Optional.of("\"v1\"");
        assertThat(service.forceRefresh()).isEqualTo(Outcome.UPDATED);
        Instant firstLoad = service.getLastRefresh();

        clock.advance(FORCE_INTERVAL);
        source.answerNotModified = true;
        assertThat(service.forceRefresh()).isEqualTo(Outcome.UNCHANGED);

        assertThat(source.lastEtagSeen.get()).contains("\"v1\"");
        assertThat(service.getLastRefresh()).isAfter(firstLoad);
        assertThat(service.getCurrentProjects()).isNotEmpty();
    }

    @Test
    @DisplayName("callers that find the catalogue due at the same moment share one download")
    void concurrentCallersShareOneDownload() throws Exception {
        source.latency = Duration.ofMillis(300);
        int threads = 32;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> sizes = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                sizes.add(pool.submit(() -> {
                    go.await();
                    return service.currentProjects().size();
                }));
            }
            go.countDown();
            for (Future<Integer> size : sizes) {
                assertThat(size.get(30, TimeUnit.SECONDS)).as("every caller gets the catalogue").isPositive();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(source.fetches).as("downloads for 32 simultaneous first callers").hasValue(1);
    }

    @Test
    @DisplayName("a defect in the parser reaches the caller as a fixed sentence, never as the exception's text")
    void unexpectedFailureIsNotRelayed() {
        source.failure = new IllegalStateException("NullPointerException at /srv/internal/path:42 token=abc");

        assertThat(service.forceRefresh()).isEqualTo(Outcome.FAILED);
        assertThat(service.getLastError())
                .isEqualTo(CncfDataRefreshService.UNPROCESSABLE)
                .doesNotContain("/srv").doesNotContain("token");
        assertThat(service.getCurrentProjects()).isEmpty();
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> hostileBodies() {
        String deep = "[".repeat(100_000) + "]".repeat(100_000);
        String item = "{\"name\":\"X\",\"category\":\"C\",\"repositories\":[{\"url\":\"https://github.com/x/x\",\"primary\":true}]}";
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("100k nested arrays", deep, Outcome.FAILED),
                org.junit.jupiter.params.provider.Arguments.of("truncated JSON", "{\"items\":[{\"name\":\"K", Outcome.FAILED),
                org.junit.jupiter.params.provider.Arguments.of("JSON that is a string", "\"hello\"", Outcome.FAILED),
                org.junit.jupiter.params.provider.Arguments.of("items is an object", "{\"items\":{\"a\":1}}", Outcome.FAILED),
                org.junit.jupiter.params.provider.Arguments.of("items is null", "{\"items\":null}", Outcome.FAILED),
                org.junit.jupiter.params.provider.Arguments.of("items of wrong types", "{\"items\":[null,1,\"x\",[],true]}", Outcome.FAILED),
                org.junit.jupiter.params.provider.Arguments.of("name is an object, category an array",
                        "{\"items\":[{\"name\":{\"a\":1},\"category\":[1]}]}", Outcome.FAILED),
                org.junit.jupiter.params.provider.Arguments.of("repositories entries null", "{\"items\":[{\"name\":\"X\",\"category\":\"C\",\"repositories\":[null]}]}", Outcome.UPDATED),
                org.junit.jupiter.params.provider.Arguments.of("github_data is an array", "{\"github_data\":[1,2],\"items\":[" + item + "]}", Outcome.UPDATED),
                org.junit.jupiter.params.provider.Arguments.of("stars negative", "{\"github_data\":{\"https://github.com/x/x\":{\"stars\":-5}},\"items\":[" + item + "]}", Outcome.UPDATED),
                org.junit.jupiter.params.provider.Arguments.of("stars beyond long", "{\"github_data\":{\"https://github.com/x/x\":{\"stars\":1e400}},\"items\":[" + item + "]}", Outcome.UPDATED),
                org.junit.jupiter.params.provider.Arguments.of("stars a string, contributors a string",
                        "{\"github_data\":{\"https://github.com/x/x\":{\"stars\":\"many\",\"contributors\":\"lots\"}},\"items\":[" + item + "]}", Outcome.UPDATED),
                org.junit.jupiter.params.provider.Arguments.of("latest_commit.ts garbage", "{\"github_data\":{\"https://github.com/x/x\":{\"latest_commit\":{\"ts\":\"yesterday\"}}},\"items\":[" + item + "]}", Outcome.UPDATED),
                org.junit.jupiter.params.provider.Arguments.of("number keys and a 1 MB string field",
                        "{\"items\":[" + item.replace("\"name\":\"X\"", "\"name\":\"" + "n".repeat(1 << 20) + "\"") + "]}", Outcome.UPDATED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hostileBodies")
    @DisplayName("pentest: a malformed or hostile body is a controlled failure or a clamped catalogue, never an exception")
    void hostileBodiesAreHandled(String name, String body, Outcome expected) {
        source.body.set(body);

        Outcome outcome = service.forceRefresh();

        assertThat(outcome).as(name).isEqualTo(expected);
        if (expected == Outcome.FAILED) {
            assertThat(service.getLastError()).as(name).isIn(
                    CncfDataRefreshService.NOT_JSON, CncfDataRefreshService.NO_PROJECTS);
            assertThat(service.getCurrentProjects()).isEmpty();
        } else {
            assertThat(service.getCurrentProjects()).hasSize(1);
            var meta = service.getCurrentProjects().get(0).metadata();
            assertThat(meta.stars()).isBetween(0.0, (double) Integer.MAX_VALUE);
            assertThat(meta.contributorCount()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("an unchanged body is not re-parsed and leaves the catalogue and the error state alone")
    void unchangedBodyIsNotReparsed() {
        assertThat(service.forceRefresh()).isEqualTo(Outcome.UPDATED);
        List<?> loaded = service.getCurrentProjects();

        clock.advance(FORCE_INTERVAL);
        assertThat(service.forceRefresh()).as("same bytes: nothing to update").isEqualTo(Outcome.UNCHANGED);

        assertThat(service.getCurrentProjects()).as("the same list instance, not a re-parse").isSameAs(loaded);
        assertThat(service.getLastError()).isNull();
    }
}
