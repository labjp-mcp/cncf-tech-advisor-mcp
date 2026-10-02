package io.mcp.cncf.tool.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Structured payload of {@code refresh_cncf_data}. Carries only server-side state, no
 * upstream text; see {@link CncfSearchResult} for why it carries {@code @RegisterForReflection}.
 */
@RegisterForReflection(targets = { CncfRefreshStatus.class })
public record CncfRefreshStatus(
        @JsonPropertyDescription("What the refresh did: 'updated' (new catalogue downloaded), "
                + "'unchanged' (upstream confirmed the loaded catalogue is current) or 'throttled' "
                + "(refused: the previous attempt was too recent; the loaded catalogue is served)") String outcome,
        @JsonPropertyDescription("Projects in the catalogue after the refresh") int projectCount,
        @JsonPropertyDescription("Timestamp (ISO-8601, UTC) of the last time upstream confirmed the catalogue") String lastRefresh,
        @JsonPropertyDescription("Timestamp (ISO-8601, UTC) after which the read tools will consult upstream "
                + "again on their own") String cacheExpiresAt) {
}
