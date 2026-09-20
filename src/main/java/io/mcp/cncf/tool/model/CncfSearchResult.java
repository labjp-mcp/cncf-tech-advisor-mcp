package io.mcp.cncf.tool.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Structured payload of {@code search_cncf}, returned alongside the text rendering.
 *
 * <p>Every string field holds text that already went through {@code ContentSanitizer}: the
 * record is built from cleaned values and the prose is rendered from the record, so the
 * structured channel cannot carry raw upstream text the text channel does not.
 *
 * <p>Registered for reflection explicitly, nested records included. quarkus-mcp-server
 * 2.0.0 registers a tool's return type and parameters for the native image but not the
 * classes named in {@code @OutputSchema(from = ...)}, and the schema generator works by
 * reflection: without the metadata it sees no fields and publishes {@code {"type":"object"}}
 * -- a valid schema, an empty one, and no error anywhere. Every record added to this
 * package needs the same treatment.
 */
@RegisterForReflection(targets = { CncfSearchResult.class, CncfSearchResult.Project.class })
public record CncfSearchResult(
        @JsonPropertyDescription("Number of projects returned in this response") int count,
        @JsonPropertyDescription("Total projects in the loaded landscape catalogue") int catalogueSize,
        @JsonPropertyDescription("Matching projects, most relevant first") List<Project> projects) {

    /**
     * One search hit. Deliberately narrow: the full detail is fetched via get_cncf_project.
     */
    public record Project(
            @JsonPropertyDescription("Project name; pass this to get_cncf_project") String name,
            @JsonPropertyDescription("Landscape category") String category,
            @JsonPropertyDescription("Landscape subcategory, empty when none") String subcategory,
            @JsonPropertyDescription("Short description, truncated") String description,
            @JsonPropertyDescription("CNCF maturity: graduated, incubating, sandbox, or empty for "
                    + "non-CNCF landscape members") String maturity,
            @JsonPropertyDescription("GitHub stars reported by the landscape") long stars,
            @JsonPropertyDescription("Homepage URL (scheme, host and path only), empty when "
                    + "absent or malformed") String homepageUrl,
            @JsonPropertyDescription("Repository URL (scheme, host and path only), empty when "
                    + "absent or malformed") String repoUrl,
            @JsonPropertyDescription("Relevance score 0-100 computed by this server") double relevanceScore) {
    }
}
