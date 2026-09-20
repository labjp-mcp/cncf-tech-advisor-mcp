package io.mcp.cncf.model;

import io.mcp.cncf.config.SearchConstants;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Essential CNCF data models for MCP server. Only what the tools use.
 */
public final class CncfModel {

    /**
     * Core CNCF project record.
     */
    public record CncfProject(
        String id,
        String name,
        String category,
        String subcategory,
        String description,
        String homepageUrl,
        String repoUrl,
        String maturity,
        List<String> tags,
        ProjectMetadata metadata
    ) {
        public CncfProject {
            Objects.requireNonNull(id, "Project ID cannot be null");
            Objects.requireNonNull(name, "Project name cannot be null");
            Objects.requireNonNull(category, "Project category cannot be null");

            if (id.isBlank()) throw new IllegalArgumentException("Project ID cannot be empty");
            if (name.isBlank()) throw new IllegalArgumentException("Project name cannot be empty");
            if (category.isBlank()) throw new IllegalArgumentException("Project category cannot be empty");
        }

        public boolean isPopular() {
            return metadata != null && metadata.stars() >= 1000;
        }

        public boolean isGraduated() {
            return SearchConstants.MATURITY_GRADUATED.equalsIgnoreCase(maturity);
        }
    }

    /**
     * Project metadata.
     */
    public record ProjectMetadata(
        String creationDate,
        String acceptanceDate,
        String graduationDate,
        String latestVersion,
        String license,
        String organization,
        List<String> maintainers,
        List<String> companies,
        double stars,
        double forks,
        String contributors,
        String openIssues,
        String crdbBacked,
        String endUserSupport,
        String repoUrl,
        String homepage,
        Instant lastCommitDate,
        int contributorCount
    ) {
        public ProjectMetadata {
            if (stars < 0) throw new IllegalArgumentException("Stars cannot be negative");
            if (forks < 0) throw new IllegalArgumentException("Forks cannot be negative");
            if (contributorCount < 0) throw new IllegalArgumentException("Contributor count cannot be negative");
            Objects.requireNonNull(maintainers, "Maintainers cannot be null");
            Objects.requireNonNull(companies, "Companies cannot be null");
        }

        public boolean isActivelyMaintained() {
            return lastCommitDate != null &&
                   lastCommitDate.isAfter(Instant.now().minusSeconds(90 * 24 * 60 * 60));
        }
    }

    /**
     * Search query. The constructor's checks are the ones whose messages reach the model
     * verbatim (they are this server's own text), so they say what to change.
     */
    public record SearchQuery(
        String keyword,
        String category,
        String tag,
        String maturityLevel,
        int limit
    ) {
        public SearchQuery {
            if (limit <= 0 || limit > SearchConstants.MAX_SEARCH_RESULTS) {
                throw new IllegalArgumentException("Limit must be between 1 and " + SearchConstants.MAX_SEARCH_RESULTS);
            }
            if (keyword != null && keyword.length() < SearchConstants.MIN_QUERY_LENGTH) {
                throw new IllegalArgumentException("Keyword must be at least " + SearchConstants.MIN_QUERY_LENGTH + " characters");
            }
            // The published inputSchema says maxLength; nothing on the classpath enforces it,
            // so the bound is applied here. A 100 KB keyword is not a search, it is a load.
            if (keyword != null && keyword.length() > SearchConstants.MAX_QUERY_LENGTH) {
                throw new IllegalArgumentException("Keyword must be at most " + SearchConstants.MAX_QUERY_LENGTH + " characters");
            }
            if (category != null && category.length() > SearchConstants.MAX_QUERY_LENGTH) {
                throw new IllegalArgumentException("Category must be at most " + SearchConstants.MAX_QUERY_LENGTH + " characters");
            }
        }
    }

    /**
     * Search result.
     */
    public record SearchResult(
        CncfProject project,
        double relevanceScore,
        String matchedField,
        SearchQuery originalQuery
    ) {
        public SearchResult {
            if (relevanceScore < 0 || relevanceScore > 100) {
                throw new IllegalArgumentException("Relevance score must be between 0 and 100");
            }
            Objects.requireNonNull(project, "Project cannot be null");
        }
    }

    private CncfModel() {
        // Utility class
    }
}
