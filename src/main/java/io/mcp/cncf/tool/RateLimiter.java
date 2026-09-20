package io.mcp.cncf.tool;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Caps how many tool calls a single caller may make per minute.
 *
 * <p>Ported from mcp-redhat-kb's {@code RateLimiter}, minus the identity and credential
 * signals this server does not have: there is no authentication, so on HTTP the caller is
 * its remote address, and on stdio there is exactly one local caller. The limit is per
 * caller rather than global so that one runaway agent loop on a shared HTTP deployment
 * does not starve the others.
 *
 * <p>The window is fixed, not sliding: a caller can fit up to twice the limit across a
 * window boundary. That is enough to stop a runaway loop, which is this limiter's job; it
 * is not a defence against a deliberate attacker with many addresses, and the download
 * cache in {@code CncfDataRefreshService} is what keeps such an attacker from costing
 * anything upstream. Within a single window the count is exact under concurrent calls,
 * including across a reset: see {@link Window}.
 *
 * <p>The windows live in a plain map with a hard cap rather than in Caffeine, which kb
 * uses. Caffeine chooses its cache node class by name at runtime and a native image that
 * has not registered that exact class fails the first call with
 * {@code ClassNotFoundException} -- the pentest hit it twice (once with the bare artifact,
 * once with {@code quarkus-cache}, which registers only the node classes its own configured
 * caches need). A map needs nothing from the image. The cap is kept by sweeping idle
 * windows when the map is full and, if it is still full, counting new callers in one shared
 * overflow bucket: under a flood of distinct addresses memory stays bounded and the
 * newcomers share a limit, which is the conservative failure.
 */
@ApplicationScoped
public class RateLimiter {

    /** Length of the counting window. */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** Most callers tracked individually; beyond it, new callers share {@link #OVERFLOW}. */
    static final int MAX_TRACKED_CALLERS = 10_000;

    /** Key used when there is no HTTP request at all: stdio, one local user. */
    private static final String SINGLE_LOCAL_CALLER = "local";

    /**
     * Stands in for an HTTP caller whose address has no host component. Distinct from
     * {@link #SINGLE_LOCAL_CALLER} on purpose: such callers share a bucket with each other,
     * but must not land in the stdio one, which is treated as a single trusted user.
     */
    private static final String ADDRESS_UNRESOLVED = "unresolved";

    /** Shared bucket for callers that arrive while the map is at its cap. */
    static final String OVERFLOW = "addr:overflow";

    /**
     * Calls made in the current window, and when that window started, as one immutable
     * value swapped by compare-and-set: with the two apart, a call arriving between the
     * expiry check and the counter reset was silently dropped, giving a free call at every
     * window boundary.
     */
    private static final class Window {

        private record Count(Instant startedAt, int calls) {
        }

        private final AtomicReference<Count> state = new AtomicReference<>(new Count(Instant.now(), 0));

        /** @return true when the call fits within the limit */
        boolean tryAcquire(int limit) {
            while (true) {
                Count current = state.get();
                Instant now = Instant.now();
                Count next = now.isAfter(current.startedAt().plus(WINDOW))
                        ? new Count(now, 1)
                        : new Count(current.startedAt(), current.calls() + 1);
                if (state.compareAndSet(current, next)) {
                    return next.calls() <= limit;
                }
            }
        }

        /** A window nobody has touched for two window lengths would restart its count anyway. */
        boolean isIdle(Instant now) {
            return now.isAfter(state.get().startedAt().plus(WINDOW.multipliedBy(2)));
        }
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicBoolean sweeping = new AtomicBoolean();

    private final Instance<HttpServerRequest> request;
    private final int callsPerMinute;

    @Inject
    public RateLimiter(Instance<HttpServerRequest> request,
            @ConfigProperty(name = "mcp.rate-limit.calls-per-minute", defaultValue = "120") int callsPerMinute) {
        this.request = request;
        this.callsPerMinute = callsPerMinute;
    }

    /**
     * Registers a call by the current caller.
     *
     * @return true when the call is allowed; false when the caller is over its limit
     */
    public boolean tryAcquire() {
        if (callsPerMinute <= 0) {
            return true;
        }
        return windowFor(callerKey()).tryAcquire(callsPerMinute);
    }

    public int callsPerMinute() {
        return callsPerMinute;
    }

    /** How many callers are tracked individually right now; for the tests. */
    int trackedCallers() {
        return windows.size();
    }

    private Window windowFor(String key) {
        Window existing = windows.get(key);
        if (existing != null) {
            return existing;
        }
        if (windows.size() >= MAX_TRACKED_CALLERS) {
            sweepIdle();
            if (windows.size() >= MAX_TRACKED_CALLERS) {
                return windows.computeIfAbsent(OVERFLOW, k -> new Window());
            }
        }
        return windows.computeIfAbsent(key, k -> new Window());
    }

    /** Drops idle windows; one thread sweeps at a time, the others simply proceed. */
    private void sweepIdle() {
        if (!sweeping.compareAndSet(false, true)) {
            return;
        }
        try {
            Instant now = Instant.now();
            windows.entrySet().removeIf(e -> !OVERFLOW.equals(e.getKey()) && e.getValue().isIdle(now));
        } finally {
            sweeping.set(false);
        }
    }

    /** The remote address on HTTP; a constant on stdio. */
    String callerKey() {
        return remoteAddress().orElse(SINGLE_LOCAL_CALLER);
    }

    /**
     * The caller's address, as Vert.x reports it.
     *
     * <p>Behind a reverse proxy this is the proxy's address for every caller, which
     * collapses them into one bucket. The fix is not to read {@code X-Forwarded-For} here:
     * any client can send that header, so trusting it would let a caller mint a fresh quota
     * per request. {@code quarkus.http.proxy.proxy-address-forwarding=true} makes Vert.x
     * resolve {@code remoteAddress()} from the forwarded headers, and belongs to the
     * deployment that knows a trusted proxy is in front.
     */
    private Optional<String> remoteAddress() {
        try {
            if (request.isResolvable()) {
                SocketAddress address = request.get().remoteAddress();
                String host = address == null ? null : address.hostAddress();
                return Optional.of("addr:" + (host == null ? ADDRESS_UNRESOLVED : host));
            }
        } catch (RuntimeException e) {
            // No active HTTP request, as on stdio: no address to resolve in the first place.
        }
        return Optional.empty();
    }
}
