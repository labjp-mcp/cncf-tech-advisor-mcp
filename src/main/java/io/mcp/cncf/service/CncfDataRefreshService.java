package io.mcp.cncf.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mcp.cncf.client.LandscapeConfig;
import io.mcp.cncf.client.LandscapeSource;
import io.mcp.cncf.client.LandscapeSource.Fetch;
import io.mcp.cncf.client.LandscapeUnavailableException;
import io.mcp.cncf.model.CncfModel.CncfProject;
import io.mcp.cncf.model.CncfModel.ProjectMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Holds the in-memory catalogue and decides when to ask the landscape for it again.
 *
 * <p>The decision is the point. Before this class existed in its present form, every read
 * tool called the download unconditionally: each {@code search_cncf} fetched 3.8 MB from
 * landscape.cncf.io, so one agent loop was a denial of service against this host and an
 * amplifier against the CNCF. Now a loaded catalogue is served for {@link
 * LandscapeConfig#cacheTtl()} without any request; a failed download is not retried for
 * {@link LandscapeConfig#failureBackoff()}; a forced refresh is refused inside {@link
 * LandscapeConfig#minForceInterval()} of the previous attempt; and concurrent callers that
 * find the catalogue due share one download rather than each starting their own.
 *
 * <p>When a request is made it is conditional: the validator upstream sent last time goes
 * back as {@code If-None-Match}, and a 304 costs no body at all. Without a validator the
 * body's hash tells an identical download from a changed one, so an unchanged catalogue is
 * never re-parsed.
 *
 * <p>Parses the layout landscape.cncf.io publishes today: each item lists its
 * {@code repositories}, and GitHub metrics live in a top-level {@code github_data} map keyed
 * by repository URL. The previous layout (metrics nested under the item as
 * {@code github_data}, a flat {@code repo_url}) is still read as a fallback.
 */
@ApplicationScoped
public class CncfDataRefreshService {

    private static final Logger LOG = Logger.getLogger(CncfDataRefreshService.class);

    /** What one attempt to refresh achieved. */
    public enum Outcome {
        /** A new catalogue was downloaded and parsed. */
        UPDATED,
        /** Upstream confirmed (304) or the bytes proved that nothing changed. */
        UNCHANGED,
        /** A forced refresh was refused because the previous attempt was too recent. */
        THROTTLED,
        /** The download or the parse failed; the catalogue loaded before, if any, stays. */
        FAILED
    }

    static final String EMPTY_RESPONSE = "The CNCF Landscape returned an empty response.";
    static final String NOT_JSON = "The CNCF Landscape response is not valid JSON.";
    static final String NO_PROJECTS = "No projects found in the CNCF Landscape data.";
    static final String UNPROCESSABLE = "The CNCF Landscape data could not be processed.";

    /** A parsed catalogue together with what identifies the download it came from. */
    private record Loaded(List<CncfProject> projects, Optional<String> etag, int bodyHash, Instant loadedAt) {
    }

    private final LandscapeSource source;
    private final LandscapeConfig config;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Only one download at a time; callers that arrive while it runs wait for its result. */
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile Loaded loaded;
    /** Earliest moment the read tools may cause another request. */
    private volatile Instant nextAttemptAt = Instant.EPOCH;
    /** When a request was last made, successful or not; bounds forced refreshes. */
    private volatile Instant lastAttemptAt = Instant.EPOCH;
    private volatile String lastError;
    private volatile Instant lastErrorTime;

    @Inject
    public CncfDataRefreshService(LandscapeSource source, LandscapeConfig config) {
        this(source, config, Clock.systemUTC());
    }

    /** For tests: a clock they can move. */
    CncfDataRefreshService(LandscapeSource source, LandscapeConfig config, Clock clock) {
        this.source = source;
        this.config = config;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- the cache

    /**
     * The catalogue, refreshed first if it is due. This is what the read tools call: it
     * costs a request only when the TTL (or the failure backoff) has elapsed.
     */
    public List<CncfProject> currentProjects() {
        ensureFresh();
        return getCurrentProjects();
    }

    /** Refreshes if due; otherwise returns at once without touching the network. */
    public void ensureFresh() {
        if (!isDue()) {
            return;
        }
        refreshLock.lock();
        try {
            // Whoever held the lock may have just refreshed on this caller's behalf.
            if (isDue()) {
                attempt();
            }
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * Refreshes regardless of the TTL, unless the previous attempt is more recent than
     * {@link LandscapeConfig#minForceInterval()}. The request is still conditional, so a
     * current catalogue usually costs a 304 and no body.
     */
    public Outcome forceRefresh() {
        refreshLock.lock();
        try {
            if (clock.instant().isBefore(lastAttemptAt.plus(config.minForceInterval()))) {
                return Outcome.THROTTLED;
            }
            return attempt();
        } finally {
            refreshLock.unlock();
        }
    }

    private boolean isDue() {
        return !clock.instant().isBefore(nextAttemptAt);
    }

    /** One request to upstream; the lock is held by the caller. */
    private Outcome attempt() {
        Instant now = clock.instant();
        lastAttemptAt = now;
        Loaded current = loaded;
        try {
            Fetch fetch = source.fetch(current == null ? Optional.empty() : current.etag());
            if (fetch.notModified()) {
                if (current == null) {
                    // A 304 with nothing loaded cannot happen when no validator was sent;
                    // if upstream does it anyway there is still no catalogue to serve.
                    return fail(now, EMPTY_RESPONSE);
                }
                return unchanged(now, current, fetch.etag());
            }
            String body = fetch.body();
            if (body == null || body.isBlank()) {
                return fail(now, EMPTY_RESPONSE);
            }
            int hash = body.hashCode();
            if (current != null && current.bodyHash() == hash) {
                return unchanged(now, current, fetch.etag());
            }
            List<CncfProject> projects = parseLandscapeData(body);
            if (projects.isEmpty()) {
                return fail(now, NO_PROJECTS);
            }
            loaded = new Loaded(List.copyOf(projects), fetch.etag(), hash, now);
            succeeded(now);
            LOG.infof("CNCF landscape refreshed: %d projects", projects.size());
            return Outcome.UPDATED;
        } catch (LandscapeUnavailableException e) {
            return fail(now, e.getMessage());
        } catch (RuntimeException e) {
            // Anything else is a defect in the parser, not a state of the upstream; the
            // detail goes to the log and a fixed sentence to the caller.
            LOG.errorf(e, "unexpected failure while refreshing the CNCF landscape");
            return fail(now, UNPROCESSABLE);
        }
    }

    private Outcome unchanged(Instant now, Loaded current, Optional<String> etag) {
        loaded = new Loaded(current.projects(), etag.isPresent() ? etag : current.etag(), current.bodyHash(), now);
        succeeded(now);
        LOG.debug("CNCF landscape unchanged");
        return Outcome.UNCHANGED;
    }

    private void succeeded(Instant now) {
        nextAttemptAt = now.plus(config.cacheTtl());
        lastError = null;
        lastErrorTime = null;
    }

    private Outcome fail(Instant now, String message) {
        LOG.warnf("CNCF landscape refresh failed: %s", message);
        lastError = message;
        lastErrorTime = now;
        nextAttemptAt = now.plus(config.failureBackoff());
        return Outcome.FAILED;
    }

    // ---------------------------------------------------------------- state

    /** The catalogue as loaded, without checking whether it is due. Empty until the first success. */
    public List<CncfProject> getCurrentProjects() {
        Loaded current = loaded;
        return current == null ? List.of() : current.projects();
    }

    /** When upstream was last consulted successfully (a 304 counts); EPOCH before the first success. */
    public Instant getLastRefresh() {
        Loaded current = loaded;
        return current == null ? Instant.EPOCH : current.loadedAt();
    }

    /** When the read tools will next ask upstream. */
    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    /** The reason the last attempt failed, always one of this server's own sentences; null after a success. */
    public String getLastError() {
        return lastError;
    }

    public Instant getLastErrorTime() {
        return lastErrorTime;
    }

    /** Whether a catalogue is loaded and still inside its TTL. */
    public boolean isDataFresh() {
        Loaded current = loaded;
        return current != null && clock.instant().isBefore(current.loadedAt().plus(config.cacheTtl()));
    }

    // ---------------------------------------------------------------- parsing

    /**
     * Parses CNCF Landscape JSON data into CncfProject objects. A body that is not JSON at
     * all is reported, not swallowed; individual items that do not parse are skipped.
     */
    private List<CncfProject> parseLandscapeData(String jsonData) {
        List<CncfProject> projects = new ArrayList<>();

        JsonNode rootNode;
        try {
            rootNode = objectMapper.readTree(jsonData);
        } catch (JsonProcessingException e) {
            // Nothing of the message is relayed: even Jackson's location-free text names the
            // offending character, and the fixed sentence says all the caller can act on.
            LOG.debugf("landscape body is not JSON: %s", e.getOriginalMessage());
            throw new LandscapeUnavailableException(NOT_JSON);
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
                } catch (RuntimeException e) {
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

            // Counts are clamped at zero: a negative or absurd value in a public data file
            // is a data error, not a reason to drop the project or to trust the number.
            long stars = nonNegative(github.path("stars"));
            long forks = nonNegative(github.path("forks"));
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

        } catch (RuntimeException e) {
            LOG.debugf("Error parsing project node: %s", e.getMessage());
            return null;
        }
    }

    /**
     * A count from a JSON number, clamped to {@code [0, Integer.MAX_VALUE]}. {@code asInt}
     * on a value outside the int range wraps or saturates depending on the node type; a
     * star count is never larger than an int, so anything beyond it is treated as noise.
     */
    private static long nonNegative(JsonNode node) {
        if (!node.isNumber()) {
            return 0;
        }
        double value = node.asDouble(0);
        if (Double.isNaN(value) || value <= 0) {
            return 0;
        }
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (long) value;
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
            return (int) nonNegative(contributors.path("count"));
        }
        return (int) nonNegative(contributors);
    }

    /** An ISO-8601 timestamp at {@code <objectField>.ts} (current layout) or {@code <flatField>} (old). */
    private Instant extractTimestamp(JsonNode github, String objectField, String flatField) {
        try {
            String ts = getNestedValue(github.path(objectField), "ts");
            if (ts == null) {
                ts = getNestedValue(github, flatField);
            }
            return ts == null ? null : Instant.parse(ts);
        } catch (RuntimeException e) {
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
}
