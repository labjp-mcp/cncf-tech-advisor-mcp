package io.mcp.cncf.tool;

import java.util.Map;

import io.mcp.cncf.testing.CachingLandscapeProfile;
import io.mcp.cncf.testing.LandscapeStub;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two controls that make the server usable against a hostile or merely busy client,
 * proven over the wire with the shipped defaults: a sequence of tool calls costs one
 * upstream download, and a caller over the per-minute limit is refused.
 *
 * <p>One test method on purpose. The limiter counts by remote address, which is the same
 * for every test in this JVM, and the cache is application state: splitting the sequence
 * across methods would make each one depend on the order the runner picked.
 */
@QuarkusTest
@TestProfile(CachingLandscapeProfile.class)
class CncfCachingProtocolTest {

    @Test
    @DisplayName("regression: repeated reads cost one download; a forced refresh is throttled; the limit is enforced")
    void readsAreCachedAndCallsAreLimited() {
        LandscapeStub.reset();
        LandscapeStub.serveCurrentLandscape();
        McpStreamableTestClient client = McpAssured.newConnectedStreamableClient();

        // Calls 1-3: two searches and a detail, dispatched concurrently by McpAssured, so
        // this also covers three simultaneous first callers. Before the fix each was a full
        // download.
        client.when()
                .toolsCall("search_cncf", Map.of("query", "kube"), r -> {
                    assertThat(r.isError()).isFalse();
                    String text = r.content().get(0).asText().text();
                    assertThat(text).contains("<<<UNTRUSTED_CNCF_CONTENT:").contains("Kubernetes");
                })
                .toolsCall("search_cncf", Map.of("query", "mesh"), r -> assertThat(r.isError()).isFalse())
                .toolsCall("get_cncf_project", Map.of("projectName", "Envoy"), r -> assertThat(r.isError()).isFalse())
                .thenAssertResults();
        LandscapeStub.server().verify(exactly(1), getRequestedFor(urlEqualTo(LandscapeStub.DATA_PATH)));

        // Call 4: a forced refresh seconds after the load is refused without a request.
        client.when()
                .toolsCall("refresh_cncf_data", Map.of(), r -> {
                    assertThat(r.isError()).isFalse();
                    JsonObject s = new JsonObject(Json.encode(r.structuredContent()));
                    assertThat(s.getString("outcome")).isEqualTo("throttled");
                    assertThat(s.getInteger("projectCount")).isEqualTo(6);
                    assertThat(r.content().get(0).asText().text()).startsWith("Refresh refused");
                })
                .thenAssertResults();
        LandscapeStub.server().verify(exactly(1), getRequestedFor(urlEqualTo(LandscapeStub.DATA_PATH)));

        // Calls 5-6 fit the limit; call 7 is refused with a message that says how to behave.
        // One chain per call: McpAssured dispatches the calls of a chain concurrently (the
        // audit log shows three executor threads for the first chain above), so the order
        // in which they reach the limiter is not the order they are written in.
        for (int call = 5; call <= CachingLandscapeProfile.CALLS_PER_MINUTE; call++) {
            int n = call;
            client.when()
                    .toolsCall("list_cncf_categories", Map.of(), r -> assertThat(r.isError()).as("call " + n).isFalse())
                    .thenAssertResults();
        }
        client.when()
                .toolsCall("list_cncf_categories", Map.of(), r -> {
                    assertThat(r.isError()).as("call " + (CachingLandscapeProfile.CALLS_PER_MINUTE + 1)).isTrue();
                    assertThat(r.content().get(0).asText().text())
                            .startsWith("Rate limit exceeded")
                            .contains(String.valueOf(CachingLandscapeProfile.CALLS_PER_MINUTE));
                })
                .thenAssertResults();
        LandscapeStub.server().verify(exactly(1), getRequestedFor(urlEqualTo(LandscapeStub.DATA_PATH)));
    }
}
