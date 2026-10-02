package io.mcp.cncf.testing;

import io.quarkiverse.mcp.server.McpProtocolVersion;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;

/**
 * The two protocol paths a client can reach this server by, as an {@code @EnumSource} for
 * tests whose obligation holds on both. Same helper as mcp-redhat-kb's.
 *
 * <p>{@link #STATEFUL} performs the {@code initialize} handshake and is given a session;
 * {@link #STATELESS} sends its identity in {@code _meta} on every request and is given none.
 * The extension builds the response envelope separately for each, and McpAssured only takes
 * the stateless branch when it is built with the 2026-07-28 version -- a test that omits
 * {@code setProtocolVersion} runs the legacy path twice and still passes.
 */
public enum Era {
    STATEFUL {
        @Override
        public McpStreamableTestClient connect() {
            return McpAssured.newConnectedStreamableClient();
        }
    },
    STATELESS {
        @Override
        public McpStreamableTestClient connect() {
            return McpAssured.newStreamableClient()
                    .setProtocolVersion(McpProtocolVersion.V_2026_07_28)
                    .build()
                    .connect();
        }
    };

    /** Opens a client that speaks this era's protocol. */
    public abstract McpStreamableTestClient connect();
}
