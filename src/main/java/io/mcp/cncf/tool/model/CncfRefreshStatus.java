package io.mcp.cncf.tool.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Structured payload of {@code refresh_cncf_data}. Carries only server-side state, no
 * upstream text; see {@link CncfSearchResult} for why it carries {@code @RegisterForReflection}.
 */
@RegisterForReflection(targets = { CncfRefreshStatus.class })
public record CncfRefreshStatus(
        @JsonPropertyDescription("Whether the catalogue changed as a result of this refresh; false "
                + "when upstream data was identical to what was already loaded") boolean updated,
        @JsonPropertyDescription("Projects in the catalogue after the refresh") int projectCount,
        @JsonPropertyDescription("Timestamp (ISO-8601, UTC) of the last successful refresh") String lastRefresh,
        @JsonPropertyDescription("Whether the catalogue was refreshed within the last hour") boolean dataFresh) {
}
