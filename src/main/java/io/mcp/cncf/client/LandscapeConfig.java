package io.mcp.cncf.client;

import java.time.Duration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * How the landscape is fetched and how long a fetched catalogue is trusted.
 *
 * <p>Every value here is a bound on something an upstream, a client or an agent loop could
 * otherwise make unbounded: the size of one download, the time one download may hold a
 * worker thread, and how often the server is willing to download at all.
 */
@ConfigMapping(prefix = "cncf.landscape")
public interface LandscapeConfig {

    /**
     * Site root of the landscape; the data path is fixed in code
     * ({@link LandscapeHttp#DATA_PATH}). Tests point this at a WireMock.
     */
    @WithDefault("https://landscape.cncf.io")
    @WithName("base-url")
    String baseUrl();

    /** Time allowed to establish the TCP/TLS connection. */
    @WithDefault("PT15S")
    @WithName("connect-timeout")
    Duration connectTimeout();

    /** Deadline for the whole exchange -- headers and body -- not per read. */
    @WithDefault("PT60S")
    @WithName("request-timeout")
    Duration requestTimeout();

    /**
     * Largest body accepted, compressed or decompressed, in bytes. {@code full.json} was
     * 3.8 MB uncompressed in September 2026; 16 MiB leaves room for growth and still refuses
     * an upstream that streams without end.
     */
    @WithDefault("16777216")
    @WithName("max-bytes")
    int maxBytes();

    /**
     * How long a loaded catalogue is served without asking upstream again. The landscape
     * publishes {@code Cache-Control: max-age=3600} and is regenerated about daily, so an
     * hour costs nothing in freshness. Zero disables the cache (every read tool downloads),
     * which is what the tests use to see each call reach the stub.
     */
    @WithDefault("PT1H")
    @WithName("cache-ttl")
    Duration cacheTtl();

    /**
     * How long after a failed download the read tools serve what they have (or report the
     * failure) without trying again. Without it an unreachable upstream would be retried on
     * every tool call, and a busy agent loop would hammer a site that is already down.
     */
    @WithDefault("PT30S")
    @WithName("failure-backoff")
    Duration failureBackoff();

    /**
     * Least time between two forced refreshes ({@code refresh_cncf_data}). A forced refresh
     * is a conditional request that usually costs a 304 and no body, but it is still one
     * request to a third party per tool call, and nothing legitimate needs it more often
     * than this.
     */
    @WithDefault("PT30S")
    @WithName("min-force-interval")
    Duration minForceInterval();
}
