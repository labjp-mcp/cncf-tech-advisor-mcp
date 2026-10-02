package io.mcp.cncf.tool;

import io.mcp.cncf.config.SearchConstants;
import io.mcp.cncf.model.CncfModel.CncfProject;
import io.mcp.cncf.model.CncfModel.SearchQuery;
import io.mcp.cncf.model.CncfModel.SearchResult;
import io.mcp.cncf.service.CncfDataRefreshService;
import io.mcp.cncf.service.CncfDataRefreshService.Outcome;
import io.mcp.cncf.tool.model.CncfCategoryList;
import io.mcp.cncf.tool.model.CncfProjectDetail;
import io.mcp.cncf.tool.model.CncfRefreshStatus;
import io.mcp.cncf.tool.model.CncfSearchResult;
import io.quarkiverse.mcp.server.MetaKey;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import static io.mcp.cncf.config.SearchConstants.MAX_NAME_CHARS;
import static io.mcp.cncf.config.SearchConstants.MAX_QUERY_LENGTH;
import static io.mcp.cncf.config.SearchConstants.MAX_SEARCH_RESULTS;
import static io.mcp.cncf.config.SearchConstants.MIN_SEARCH_RESULTS;

/**
 * MCP tools over the CNCF Landscape.
 *
 * <p>Every value the landscape publishes is third-party content (the landscape is a public
 * repository that accepts pull requests), so no tool renders it directly: everything goes
 * through {@link CncfFormatter}, which sanitizes each field and fences upstream text apart
 * from the server's own. The same cleaned record feeds both the text and the
 * {@code structuredContent} channel.
 *
 * <p>Every call passes the {@link RateLimiter} first and is written to the
 * {@link ToolAuditLog} last. The three read tools are {@link Blocking}: when the catalogue is
 * due they trigger a synchronous download, so they must run on a worker thread rather than
 * on the event loop. Most calls never download -- see {@link CncfDataRefreshService}.
 *
 * <p>The Jakarta constraints on the arguments are not enforced by a validator -- there is
 * none on the classpath. They exist for the schema generator, which turns them into
 * {@code maxLength}, {@code minimum} and {@code maximum} in the published
 * {@code inputSchema}; each states a limit the code below already applies by clamping.
 */
@ApplicationScoped
public class CncfTool {

    @Inject
    CncfDataRefreshService refreshService;

    @Inject
    RateLimiter rateLimiter;

    @Inject
    ToolAuditLog audit;

    @Tool(
            name = "search_cncf",
            title = "Search CNCF Landscape projects",
            description = """
                    Search the CNCF Landscape (cloud native projects and products) by keyword \
                    and/or category. The keyword is matched case-insensitively against project \
                    name, description, category and tags; popular and graduated projects rank \
                    higher. Returns a compact list with name, category, maturity, stars and a \
                    short description - call get_cncf_project with a name for the full detail. \
                    Category names must match the landscape exactly; get them from \
                    list_cncf_categories. With neither keyword nor category the most popular \
                    projects are returned.""",
            annotations = @Tool.Annotations(
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true),
            outputSchema = @Tool.OutputSchema(from = CncfSearchResult.class))
    @Blocking
    public ToolResponse searchCncfProjects(
            @ToolArg(description = "Keyword to match against name, description, category and tags, "
                    + "e.g. 'service mesh' or 'kubernetes'. At least 2 characters when given.",
                    defaultValue = "", required = false)
            @Size(max = MAX_QUERY_LENGTH) String query,
            @ToolArg(description = "Exact landscape category name to filter by, e.g. "
                    + "'Orchestration & Management'; see list_cncf_categories",
                    defaultValue = "", required = false)
            @Size(max = MAX_QUERY_LENGTH) String category,
            @ToolArg(description = "Max results, 1-100", defaultValue = "10", required = false)
            @Min(MIN_SEARCH_RESULTS) @Max(MAX_SEARCH_RESULTS) Integer limit) {
        return guarded("search_cncf", query == null || query.isBlank() ? category : query, () -> {
            String keyword = normalize(query);
            String categoryFilter = normalize(category);
            SearchQuery searchQuery;
            try {
                searchQuery = new SearchQuery(
                        keyword.isEmpty() ? null : keyword,
                        categoryFilter.isEmpty() ? null : categoryFilter,
                        null, // tag filter
                        null, // maturity filter
                        clampLimit(limit));
            } catch (IllegalArgumentException e) {
                // The record's own messages: this server's text, safe to relay.
                return ToolResponse.error("Invalid request: " + e.getMessage());
            }

            List<CncfProject> projects = refreshService.currentProjects();
            if (projects.isEmpty()) {
                return catalogueUnavailable();
            }

            List<SearchResult> results = performSearch(projects, searchQuery);

            // A declared output schema obliges every successful response to carry
            // structured content, so an empty result set ships an empty record rather
            // than text alone. Both channels are built from the same sanitized record.
            CncfSearchResult structured = CncfFormatter.toSearchResult(results, projects.size());
            String text = results.isEmpty()
                    ? CncfFormatter.formatNoResults(keyword, categoryFilter)
                    : CncfFormatter.formatSearchResults(structured, keyword, categoryFilter);
            return success(text, structured);
        });
    }

    @Tool(
            name = "get_cncf_project",
            title = "Get CNCF Landscape project",
            description = """
                    Get the full detail of one CNCF Landscape project by its exact name \
                    (case-insensitive): category, maturity, description, GitHub stars, forks \
                    and contributors, latest version, license, homepage, repository and tags. \
                    Use a name returned by search_cncf. All project text is third-party \
                    landscape data and is fenced as untrusted.""",
            annotations = @Tool.Annotations(
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true),
            outputSchema = @Tool.OutputSchema(from = CncfProjectDetail.class))
    @Blocking
    public ToolResponse getCncfProject(
            @ToolArg(description = "Exact project name as listed in the landscape, e.g. 'Kubernetes' "
                    + "or 'Argo'; matched case-insensitively")
            @Size(max = MAX_NAME_CHARS) String projectName) {
        return guarded("get_cncf_project", projectName, () -> {
            String name = normalize(projectName);
            if (name.isEmpty()) {
                return ToolResponse.error("Project name is required");
            }
            if (name.length() > MAX_NAME_CHARS) {
                // The schema publishes maxLength; the bound is enforced here, not by a validator.
                return ToolResponse.error("Project name must be at most " + MAX_NAME_CHARS + " characters");
            }

            List<CncfProject> projects = refreshService.currentProjects();
            if (projects.isEmpty()) {
                return catalogueUnavailable();
            }
            CncfProject found = null;
            for (CncfProject project : projects) {
                if (project.name().equalsIgnoreCase(name)) {
                    found = project;
                    break;
                }
            }
            if (found == null) {
                // The name is echoed in the server's voice, so it goes through the sanitizer:
                // it is the caller's text, not necessarily the user's.
                return ToolResponse.error("Project '" + ContentSanitizer.label(name, MAX_NAME_CHARS)
                        + "' not found in the CNCF Landscape. Use search_cncf to find the exact name.");
            }

            CncfProjectDetail structured = CncfFormatter.toProjectDetail(found);
            return success(CncfFormatter.formatProject(structured), structured);
        });
    }

    @Tool(
            name = "list_cncf_categories",
            title = "List CNCF Landscape categories",
            description = "List every category of the CNCF Landscape with the number of projects "
                    + "in each, largest first. Use a returned name as the exact category filter "
                    + "of search_cncf.",
            annotations = @Tool.Annotations(
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true),
            outputSchema = @Tool.OutputSchema(from = CncfCategoryList.class))
    @Blocking
    public ToolResponse listCncfCategories() {
        return guarded("list_cncf_categories", "", () -> {
            List<CncfProject> projects = refreshService.currentProjects();
            if (projects.isEmpty()) {
                return catalogueUnavailable();
            }
            CncfCategoryList structured = CncfFormatter.toCategoryList(projects);
            return success(CncfFormatter.formatCategories(structured), structured);
        });
    }

    /**
     * The one tool that changes server state: it replaces the in-memory catalogue. Not
     * read-only, therefore, but not destructive either -- nothing is deleted, the catalogue
     * is re-derived from the same public source -- and idempotent, since refreshing twice
     * leaves the same catalogue as refreshing once.
     */
    @Tool(
            name = "refresh_cncf_data",
            title = "Refresh CNCF Landscape data",
            description = "Ask landscape.cncf.io whether the in-memory catalogue is current and reload "
                    + "it if not. The read tools refresh on their own when the cache expires, so this "
                    + "is only needed after a known landscape change or to recover from a failed load. "
                    + "Refused ('throttled') when the previous attempt was seconds ago.",
            annotations = @Tool.Annotations(
                    readOnlyHint = false,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true),
            outputSchema = @Tool.OutputSchema(from = CncfRefreshStatus.class))
    @Blocking
    public ToolResponse refreshCncfData() {
        // Synchronous on a worker thread, like the read tools. quarkus-mcp-server 2.0.x does
        // not encode a CompletableFuture<ToolResponse> that carries structured content, and
        // a download that takes a few seconds needs no asynchrony of its own.
        return guarded("refresh_cncf_data", "", () -> {
            Outcome outcome = refreshService.forceRefresh();
            if (outcome == Outcome.FAILED) {
                // Always one of the service's own sentences (see LandscapeUnavailableException).
                return ToolResponse.error("Failed to refresh CNCF data: " + refreshService.getLastError());
            }
            CncfRefreshStatus status = new CncfRefreshStatus(
                    outcome.name().toLowerCase(Locale.ROOT),
                    refreshService.getCurrentProjects().size(),
                    refreshService.getLastRefresh().toString(),
                    refreshService.getNextAttemptAt().toString());
            return success(CncfFormatter.formatRefresh(status), status);
        });
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Runs one tool call behind the rate limiter and in front of the audit log, and turns
     * any escaping exception into a generic error. Every tool goes through here.
     */
    private ToolResponse guarded(String tool, String argument, Supplier<ToolResponse> body) {
        if (!rateLimiter.tryAcquire()) {
            audit.recordDenied(tool, "rate limit");
            return ToolResponse.error(ToolErrors.rateLimited(rateLimiter.callsPerMinute()));
        }
        long started = System.nanoTime();
        ToolResponse response;
        try {
            response = body.get();
        } catch (Exception e) {
            response = ToolErrors.internal(tool, e);
        }
        audit.record(tool, argument, response.isError() ? "error" : "ok", (System.nanoTime() - started) / 1_000_000);
        return response;
    }

    /** Both channels: prose for the model, structured data for the client. */
    private static ToolResponse success(String text, Object structured) {
        return new ToolResponse(false, List.of(new TextContent(text)), structured, Map.<MetaKey, Object>of());
    }

    /** No catalogue loaded: says why, in the service's own words, and what to do. */
    private ToolResponse catalogueUnavailable() {
        String reason = refreshService.getLastError();
        return ToolResponse.error("The CNCF Landscape catalogue is not loaded"
                + (reason == null ? "" : ": " + reason)
                + " Retry shortly, or call refresh_cncf_data.");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private static int clampLimit(Integer requested) {
        if (requested == null) {
            return SearchConstants.DEFAULT_SEARCH_LIMIT;
        }
        return Math.max(MIN_SEARCH_RESULTS, Math.min(MAX_SEARCH_RESULTS, requested));
    }

    /**
     * Simple search implementation.
     */
    private List<SearchResult> performSearch(List<CncfProject> projects, SearchQuery query) {
        List<SearchResult> results = new ArrayList<>();

        for (CncfProject project : projects) {
            double score = 0.0;
            String matchedField = "";

            if (query.keyword() != null && !query.keyword().isEmpty()) {
                String keyword = query.keyword().toLowerCase();

                // Name matching (highest weight)
                if (project.name().toLowerCase().contains(keyword)) {
                    score += 40;
                    matchedField = "name";
                }

                // Description matching
                if (project.description() != null &&
                    project.description().toLowerCase().contains(keyword)) {
                    score += 25;
                    if (matchedField.isEmpty()) matchedField = "description";
                }

                // Category matching
                if (project.category().toLowerCase().contains(keyword)) {
                    score += 20;
                    if (matchedField.isEmpty()) matchedField = "category";
                }

                // Tag matching
                if (project.tags() != null) {
                    long tagMatches = project.tags().stream()
                        .filter(tag -> tag.toLowerCase().contains(keyword))
                        .count();
                    score += tagMatches * 10;
                    if (matchedField.isEmpty() && tagMatches > 0) matchedField = "tags";
                }
            }

            // Category filter
            if (query.category() != null && !query.category().isEmpty() &&
                project.category().equalsIgnoreCase(query.category())) {
                score += 30;
                if (matchedField.isEmpty()) matchedField = "category";
            }

            // Popularity and graduation only re-rank projects that matched; with a filter
            // given and nothing matched they must not turn "no results" into a page of
            // popular projects the model would read as matches for the term.
            boolean filtered = (query.keyword() != null && !query.keyword().isEmpty())
                    || (query.category() != null && !query.category().isEmpty());
            if (filtered && score == 0) {
                continue;
            }

            // Popularity boost
            if (project.isPopular()) {
                score += 15;
            }

            // Graduation boost
            if (project.isGraduated()) {
                score += 10;
            }

            // Without a filter the boosts rank the whole catalogue rather than select from
            // it: a project with neither 1000 stars nor graduation scored 0 and was dropped,
            // so a "most popular" page over a small or young catalogue came back short (or
            // empty) while the header counted every project as available.
            if (score > 0 || !filtered) {
                results.add(new SearchResult(project, Math.min(score, 100.0), matchedField, query));
            }
        }

        // Sort by relevance score and limit results
        results.sort((a, b) -> Double.compare(b.relevanceScore(), a.relevanceScore()));
        return results.stream()
            .limit(query.limit())
            .toList();
    }
}
