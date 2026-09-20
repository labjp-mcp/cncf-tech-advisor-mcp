package io.mcp.cncf.testing;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * The production caching and rate-limiting behaviour, against the stub: the cache TTL,
 * the failure backoff and the forced-refresh interval keep their shipped defaults, and the
 * rate limit is small enough for a test to hit. Boots a second application, so only the
 * tests that need exactly this use it.
 */
public class CachingLandscapeProfile implements QuarkusTestProfile {

    /** Calls per minute per caller under this profile. */
    public static final int CALLS_PER_MINUTE = 6;

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "cncf.landscape.base-url", LandscapeStub.url(),
                "cncf.landscape.connect-timeout", "PT1S",
                "cncf.landscape.request-timeout", "PT2S",
                "mcp.rate-limit.calls-per-minute", String.valueOf(CALLS_PER_MINUTE));
    }
}
