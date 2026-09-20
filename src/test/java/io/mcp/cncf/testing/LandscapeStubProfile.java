package io.mcp.cncf.testing;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Points the landscape REST client at {@link LandscapeStub} instead of {@code landscape.cncf.io}.
 *
 * <p>One profile for every {@code @QuarkusTest}, so the application boots once for the whole
 * suite. The timeouts are short on purpose: the production values (15 s connect, 60 s read)
 * are right for the real site and wrong for a test that wants to see a timeout happen.
 */
public class LandscapeStubProfile implements QuarkusTestProfile {

    /** The read timeout the client runs with under this profile, in milliseconds. */
    public static final int READ_TIMEOUT_MS = 1000;

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.rest-client.cncf-landscape-api.url", LandscapeStub.url(),
                "quarkus.rest-client.cncf-landscape-api.connect-timeout", "1000",
                "quarkus.rest-client.cncf-landscape-api.read-timeout", String.valueOf(READ_TIMEOUT_MS));
    }
}
