package io.mcp.cncf.testing;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Points the landscape client at {@link LandscapeStub} instead of {@code landscape.cncf.io}.
 *
 * <p>One profile for most {@code @QuarkusTest}s, so the application boots once for them.
 * The timeouts are short on purpose: the production values (15 s connect, 60 s for the
 * exchange) are right for the real site and wrong for a test that wants to see a timeout
 * happen. The cache TTL, the failure backoff and the forced-refresh interval are zero so
 * that every tool call reaches the stub and a test that swaps the stub's answer sees the
 * change on the next call; {@link CachingLandscapeProfile} is where the cache itself is
 * under test.
 */
public class LandscapeStubProfile implements QuarkusTestProfile {

    /** The whole-exchange deadline the client runs with under this profile, in milliseconds. */
    public static final int REQUEST_TIMEOUT_MS = 1000;

    /** The largest body the client accepts under this profile, in bytes. */
    public static final int MAX_BYTES = 256 * 1024;

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "cncf.landscape.base-url", LandscapeStub.url(),
                "cncf.landscape.connect-timeout", "PT1S",
                "cncf.landscape.request-timeout", "PT" + (REQUEST_TIMEOUT_MS / 1000.0) + "S",
                "cncf.landscape.max-bytes", String.valueOf(MAX_BYTES),
                "cncf.landscape.cache-ttl", "PT0S",
                "cncf.landscape.failure-backoff", "PT0S",
                "cncf.landscape.min-force-interval", "PT0S",
                // The suite makes hundreds of calls from one address inside a minute; the
                // limiter is exercised by RateLimiterTest and CachingLandscapeProfile.
                "mcp.rate-limit.calls-per-minute", "0");
    }
}
