package io.mcp.cncf.tool;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimiterTest {

    @SuppressWarnings("unchecked")
    private static Instance<HttpServerRequest> noRequest() {
        Instance<HttpServerRequest> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(false);
        return instance;
    }

    private static Instance<HttpServerRequest> requestFrom(String host) {
        return requestFromMutable(new java.util.concurrent.atomic.AtomicReference<>(host));
    }

    @SuppressWarnings("unchecked")
    private static Instance<HttpServerRequest> requestFromMutable(java.util.concurrent.atomic.AtomicReference<String> host) {
        Instance<HttpServerRequest> instance = mock(Instance.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        SocketAddress address = mock(SocketAddress.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(request);
        when(request.remoteAddress()).thenReturn(address);
        when(address.hostAddress()).thenAnswer(inv -> host.get());
        return instance;
    }

    @Test
    @DisplayName("pentest: a flood of distinct addresses cannot grow the map past its cap; newcomers share one bucket")
    void addressFloodStaysBounded() {
        var host = new java.util.concurrent.atomic.AtomicReference<String>();
        RateLimiter limiter = new RateLimiter(requestFromMutable(host), 2);

        for (int i = 0; i < RateLimiter.MAX_TRACKED_CALLERS + 5_000; i++) {
            host.set("10." + (i >> 16 & 255) + "." + (i >> 8 & 255) + "." + (i & 255));
            limiter.tryAcquire();
        }

        assertThat(limiter.trackedCallers()).isLessThanOrEqualTo(RateLimiter.MAX_TRACKED_CALLERS + 1);
        // The overflow bucket has absorbed 5,000 calls against a limit of 2: refused.
        host.set("192.0.2.1");
        assertThat(limiter.tryAcquire()).as("a newcomer during the flood shares the overflow bucket").isFalse();
        // An address tracked before the cap keeps its own window.
        host.set("10.0.0.1");
        assertThat(limiter.tryAcquire()).as("second call of a tracked address, limit 2").isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("allows exactly the limit within a window and refuses the next call")
    void allowsExactlyTheLimit() {
        RateLimiter limiter = new RateLimiter(noRequest(), 3);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).as("fourth call in the window").isFalse();
    }

    @Test
    @DisplayName("zero disables the limiter")
    void zeroDisables() {
        RateLimiter limiter = new RateLimiter(noRequest(), 0);
        for (int i = 0; i < 1_000; i++) {
            assertThat(limiter.tryAcquire()).isTrue();
        }
    }

    @Test
    @DisplayName("stdio and HTTP callers are separate buckets; an unresolved address is not the stdio bucket")
    void callerKeys() {
        assertThat(new RateLimiter(noRequest(), 10).callerKey()).isEqualTo("local");
        assertThat(new RateLimiter(requestFrom("10.0.0.7"), 10).callerKey()).isEqualTo("addr:10.0.0.7");
        assertThat(new RateLimiter(requestFrom(null), 10).callerKey())
                .as("no host must not fall through to the trusted local bucket")
                .isEqualTo("addr:unresolved");
    }

    @Test
    @DisplayName("the count is exact under concurrency: exactly the limit succeeds, never one more")
    void countIsExactUnderConcurrency() throws Exception {
        int limit = 50;
        int threads = 16;
        int perThread = 20;
        RateLimiter limiter = new RateLimiter(noRequest(), limit);
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> tasks = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                tasks.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < perThread; i++) {
                        if (limiter.tryAcquire()) {
                            allowed.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> task : tasks) {
                task.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(allowed).hasValue(limit);
    }
}
