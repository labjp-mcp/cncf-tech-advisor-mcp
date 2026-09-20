package io.mcp.cncf.service;

import io.mcp.cncf.client.CncfLandscapeClient;
import io.mcp.cncf.model.CncfModel.CncfProject;
import io.mcp.cncf.model.CncfModel.ProjectMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * Service for refreshing CNCF data with ETags for incremental updates.
 * Uses Java 25 virtual threads for efficient async operations.
 *
 * <p>Parses the layout landscape.cncf.io publishes today: each item lists its
 * {@code repositories}, and GitHub metrics live in a top-level {@code github_data} map keyed
 * by repository URL. The previous layout (metrics nested under the item as
 * {@code github_data}, a flat {@code repo_url}) is still read as a fallback.
 */
@ApplicationScoped
public class CncfDataRefreshService {

    private static final Logger LOG = Logger.getLogger(CncfDataRefreshService.class);

    @RestClient
    @Inject
    CncfLandscapeClient landscapeClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> lastETag = new AtomicReference<>();
    private final AtomicReference<Instant> lastRefresh = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<List<CncfProject>> cachedProjects = new AtomicReference<>(new ArrayList<>());
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicReference<Instant> lastErrorTime = new AtomicReference<>();

    // Java 25 Virtual Thread Executor
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Refreshes CNCF data using ETags for incremental updates.
     * This is the main data refresh method.
     *
     * @return True if data was updated, false if no changes
     */
    public boolean refreshData() {
        try {
            LOG.info("Starting CNCF data refresh...");
            long startTime = System.currentTimeMillis();

            // Get current data with ETag support
            String currentETag = lastETag.get();
            String landscapeData;

            // Note: Since we're using the basic REST client, we'll implement ETag logic manually
            // In a production environment, you might want to use a more advanced HTTP client
            landscapeData = landscapeClient.getFullLandscapeData();

            // A response that is not a usable catalogue is a failed refresh and is recorded
            // as one. These three branches used to return false without touching lastError,
            // which left refresh_cncf_data reporting "already current; nothing changed" with
            // zero projects after receiving HTML, an empty body or a JSON with no items --
            // the exact answer the SPA's index.html produced when the client path was wrong.
            if (landscapeData == null || landscapeData.trim().isEmpty()) {
                recordError("Received an empty response from the CNCF Landscape API");
                return false;
            }

            // Check if data has actually changed
            String newDataHash = Integer.toString(landscapeData.hashCode());
            if (newDataHash.equals(currentETag)) {
                LOG.debug("CNCF data unchanged, skipping refresh");
                return false;
            }

            // Parse and update data
            List<CncfProject> projects = parseLandscapeData(landscapeData);
            if (projects.isEmpty()) {
                recordError("No projects found in the CNCF Landscape data");
                return false;
            }

            // Update cached data
            cachedProjects.set(new ArrayList<>(projects));
            lastETag.set(newDataHash);
            lastRefresh.set(Instant.now());
            lastError.set(null);
            lastErrorTime.set(null);

            long duration = System.currentTimeMillis() - startTime;
            LOG.infof("CNCF data refresh completed in %dms: %d projects processed",
                     duration, projects.size());

            return true;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to refresh CNCF data: %s", e.getMessage());
            recordError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }

    private void recordError(String message) {
        LOG.warnf("CNCF data refresh failed: %s", message);
        lastError.set(message);
        lastErrorTime.set(Instant.now());
    }

    /**
     * Parses CNCF Landscape JSON data into CncfProject objects. A body that is not JSON at
     * all is reported, not swallowed: the caller records it as the reason the refresh
     * failed. Individual items that do not parse are skipped, as before.
     *
     * @param jsonData JSON data from CNCF Landscape
     * @return List of parsed CNCF projects
     */
    private List<CncfProject> parseLandscapeData(String jsonData) {
        List<CncfProject> projects = new ArrayList<>();

        JsonNode rootNode;
        try {
            rootNode = objectMapper.readTree(jsonData);
        } catch (JsonProcessingException e) {
            // getOriginalMessage() carries no source snippet, so nothing of the upstream
            // body is quoted back; the tool clips and sanitizes the message anyway.
            throw new IllegalStateException(
                    "CNCF Landscape response is not valid JSON: " + e.getOriginalMessage(), e);
        }
        JsonNode itemsNode = rootNode.path("items");
        JsonNode githubIndex = rootNode.path("github_data");

        if (itemsNode.isArray()) {
            for (JsonNode itemNode : itemsNode) {
                try {
                    CncfProject project = parseProjectNode(itemNode, githubIndex);
                    if (project != null) {
                        projects.add(project);
                    }
                } catch (Exception e) {
                    LOG.debugf("Failed to parse project item: %s", e.getMessage());
                }
            }
        }

        LOG.debugf("Parsed %d projects from CNCF Landscape data", projects.size());
        return projects;
    }

    /**
     * Parses a single project node from the CNCF Landscape data.
     *
     * @param projectNode JSON node for a single project
     * @param githubIndex top-level {@code github_data} map keyed by repository URL
     * @return Parsed CncfProject or null if invalid
     */
    private CncfProject parseProjectNode(JsonNode projectNode, JsonNode githubIndex) {
        try {
            String id = getNestedValue(projectNode, "id", "name");
            if (id == null) {
                return null;
            }

            String name = getNestedValue(projectNode, "name");
            String category = getNestedValue(projectNode, "category");
            String subcategory = getNestedValue(projectNode, "subcategory");
            String homepage = getNestedValue(projectNode, "homepage_url", "website");
            String repoUrl = primaryRepositoryUrl(projectNode);
            JsonNode github = githubData(projectNode, repoUrl, githubIndex);

            // The landscape's own description first; the repository's GitHub description
            // is the fallback for members that publish none.
            String description = getNestedValue(projectNode, "description");
            if (description == null) {
                description = getNestedValue(projectNode.path("summary"), "use_case");
            }
            if (description == null) {
                description = getNestedValue(github, "description");
            }

            String maturity = getNestedValue(projectNode, "maturity");
            String license = getNestedValue(github, "license");
            if (license == null) {
                license = getNestedValue(projectNode, "license");
            }
            String acceptanceDate = getNestedValue(projectNode, "accepted_at", "acceptance_date");
            String graduationDate = getNestedValue(projectNode, "graduated_at", "graduation_date");
            String latestVersion = latestVersion(projectNode, github);
            String org = getNestedValue(projectNode, "organization");
            String endUserSupport = getNestedValue(projectNode, "enduser_support");

            List<String> tags = extractTags(projectNode);

            int stars = github.path("stars").asInt(0);
            int forks = github.path("forks").asInt(0);
            int contributors = extractContributors(github);
            Instant lastCommitDate = extractTimestamp(github, "latest_commit", "last_commit_at");

            var metadata = new ProjectMetadata(
                "", // creationDate
                acceptanceDate != null ? acceptanceDate : "",
                graduationDate,
                latestVersion,
                license != null ? license : "",
                org,
                tags, // maintainers
                tags, // companies
                stars,
                forks,
                String.valueOf(contributors),
                "", // openIssues
                "", // crdbBacked
                endUserSupport != null ? endUserSupport : "",
                repoUrl != null ? repoUrl : "",
                homepage != null ? homepage : "",
                lastCommitDate,
                contributors
            );

            return new CncfProject(
                id,
                name != null ? name : "",
                category != null ? category : "",
                subcategory != null ? subcategory : "",
                description != null ? description : "",
                homepage != null ? homepage : "",
                repoUrl != null ? repoUrl : "",
                maturity != null ? maturity : "",
                tags,
                metadata
            );

        } catch (Exception e) {
            LOG.debugf("Error parsing project node: %s", e.getMessage());
            return null;
        }
    }

    /** The primary entry of {@code repositories}, else its first, else the legacy {@code repo_url}. */
    private String primaryRepositoryUrl(JsonNode projectNode) {
        JsonNode repositories = projectNode.path("repositories");
        if (repositories.isArray() && !repositories.isEmpty()) {
            for (JsonNode repo : repositories) {
                if (repo.path("primary").asBoolean(false)) {
                    String url = getNestedValue(repo, "url");
                    if (url != null) {
                        return url;
                    }
                }
            }
            String first = getNestedValue(repositories.get(0), "url");
            if (first != null) {
                return first;
            }
        }
        return getNestedValue(projectNode, "repo_url");
    }

    /** GitHub metrics for the project: the top-level index by repository URL, else the legacy nested node. */
    private JsonNode githubData(JsonNode projectNode, String repoUrl, JsonNode githubIndex) {
        if (repoUrl != null && githubIndex.isObject()) {
            JsonNode indexed = githubIndex.path(repoUrl);
            if (!indexed.isMissingNode()) {
                return indexed;
            }
        }
        return projectNode.path("github_data");
    }

    /** A flat {@code latest_version} if present, else the tag at the end of {@code latest_release.url}. */
    private String latestVersion(JsonNode projectNode, JsonNode github) {
        String flat = getNestedValue(projectNode, "latest_version");
        if (flat != null) {
            return flat;
        }
        String releaseUrl = getNestedValue(github.path("latest_release"), "url");
        if (releaseUrl == null) {
            return null;
        }
        int slash = releaseUrl.lastIndexOf('/');
        return slash >= 0 && slash < releaseUrl.length() - 1 ? releaseUrl.substring(slash + 1) : null;
    }

    /** {@code contributors.count} in the current layout, a bare number in the old one. */
    private int extractContributors(JsonNode github) {
        JsonNode contributors = github.path("contributors");
        if (contributors.isObject()) {
            return contributors.path("count").asInt(0);
        }
        return contributors.asInt(0);
    }

    /** An ISO-8601 timestamp at {@code <objectField>.ts} (current layout) or {@code <flatField>} (old). */
    private Instant extractTimestamp(JsonNode github, String objectField, String flatField) {
        try {
            String ts = getNestedValue(github.path(objectField), "ts");
            if (ts == null) {
                ts = getNestedValue(github, flatField);
            }
            return ts == null ? null : Instant.parse(ts);
        } catch (Exception e) {
            LOG.debugf("Error parsing %s timestamp: %s", objectField, e.getMessage());
            return null;
        }
    }

    /**
     * Extracts nested value from JSON node.
     *
     * @param node JSON node
     * @param paths Path names to try in order
     * @return Extracted value or null
     */
    private String getNestedValue(JsonNode node, String... paths) {
        for (String path : paths) {
            JsonNode valueNode = node.path(path);
            if (!valueNode.isMissingNode() && !valueNode.isNull() && valueNode.isValueNode()) {
                String value = valueNode.asText();
                if (!value.trim().isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * Extracts tags from project node.
     *
     * @param projectNode Project JSON node
     * @return List of tags
     */
    private List<String> extractTags(JsonNode projectNode) {
        List<String> tags = new ArrayList<>();

        // Add maturity level as tag
        String maturity = getNestedValue(projectNode, "maturity");
        if (maturity != null && !maturity.trim().isEmpty()) {
            tags.add(maturity.toLowerCase().trim());
        }

        // Add category as tag
        String category = getNestedValue(projectNode, "category");
        if (category != null && !category.trim().isEmpty()) {
            tags.add(category.toLowerCase().trim().replace(" ", "-"));
        }

        // Add landscape as tag
        String landscape = getNestedValue(projectNode, "landscape");
        if (landscape != null && !landscape.trim().isEmpty()) {
            tags.add(landscape.toLowerCase().trim());
        }

        // Add OSS tag if applicable
        String oss = getNestedValue(projectNode, "oss");
        if ("true".equalsIgnoreCase(oss)) {
            tags.add("open-source");
        }

        // Add CNCF tag if it's a CNCF project
        if (tags.contains("graduated") || tags.contains("incubating") || tags.contains("sandbox")) {
            tags.add("cncf");
        }

        return tags;
    }

    /**
     * Gets current cached projects.
     *
     * @return List of cached CNCF projects
     */
    public List<CncfProject> getCurrentProjects() {
        return new ArrayList<>(cachedProjects.get());
    }

    /**
     * Gets the last refresh timestamp.
     *
     * @return Last refresh time
     */
    public Instant getLastRefresh() {
        return lastRefresh.get();
    }

    /**
     * Gets the last error message.
     *
     * @return Last error message or null
     */
    public String getLastError() {
        return lastError.get();
    }

    /**
     * Gets the last error timestamp.
     *
     * @return Last error time
     */
    public Instant getLastErrorTime() {
        return lastErrorTime.get();
    }

    /**
     * Checks if data is fresh (refreshed within the last hour).
     *
     * @return True if data is fresh
     */
    public boolean isDataFresh() {
        Instant last = lastRefresh.get();
        return last != null && last.isAfter(Instant.now().minusSeconds(3600));
    }

    /**
     * Forces a data refresh regardless of cache.
     *
     * @return True if refresh succeeded
     */
    public boolean forceRefresh() {
        lastETag.set(""); // Invalidate ETag to force refresh
        return refreshData();
    }

    /**
     * Gets refresh service statistics.
     *
     * @return Statistics about refresh operations
     */
    public java.util.Map<String, Object> getStatistics() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("lastRefresh", lastRefresh.get().toString());
        stats.put("projectCount", cachedProjects.get().size());
        stats.put("dataFresh", isDataFresh());
        stats.put("hasError", lastError.get() != null);

        if (lastError.get() != null) {
            stats.put("lastError", lastError.get());
            stats.put("lastErrorTime", lastErrorTime.get().toString());
        }

        return stats;
    }

    /**
     * Async refresh using Java 25 virtual threads.
     * Simple and efficient for concurrent operations.
     *
     * @return CompletableFuture indicating refresh completion
     */
    public CompletableFuture<Boolean> refreshDataAsync() {
        return CompletableFuture.supplyAsync(this::refreshData, virtualThreadExecutor);
    }
}
