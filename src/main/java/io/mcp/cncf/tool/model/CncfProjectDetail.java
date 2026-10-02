package io.mcp.cncf.tool.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Structured payload of {@code get_cncf_project}. All strings are sanitized upstream text;
 * see {@link CncfSearchResult} for why the record carries {@code @RegisterForReflection}.
 */
@RegisterForReflection(targets = { CncfProjectDetail.class })
public record CncfProjectDetail(
        @JsonPropertyDescription("Project name") String name,
        @JsonPropertyDescription("Landscape category") String category,
        @JsonPropertyDescription("Landscape subcategory, empty when none") String subcategory,
        @JsonPropertyDescription("Project description, truncated when long") String description,
        @JsonPropertyDescription("CNCF maturity: graduated, incubating, sandbox, or empty for "
                + "non-CNCF landscape members") String maturity,
        @JsonPropertyDescription("GitHub stars reported by the landscape") long stars,
        @JsonPropertyDescription("Contributor count reported by the landscape") int contributors,
        @JsonPropertyDescription("Latest released version, empty when unknown") String latestVersion,
        @JsonPropertyDescription("License identifier, empty when unknown") String license,
        @JsonPropertyDescription("Whether the repository had a commit in the last 90 days; null "
                + "when the landscape reports no commit date") Boolean activelyMaintained,
        @JsonPropertyDescription("Homepage URL (scheme, host and path only), empty when absent "
                + "or malformed") String homepageUrl,
        @JsonPropertyDescription("Repository URL (scheme, host and path only), empty when absent "
                + "or malformed") String repoUrl,
        @JsonPropertyDescription("Tags derived from maturity, category and landscape flags") List<String> tags) {
}
