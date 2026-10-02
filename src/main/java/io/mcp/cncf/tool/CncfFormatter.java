package io.mcp.cncf.tool;

import io.mcp.cncf.model.CncfModel.CncfProject;
import io.mcp.cncf.model.CncfModel.ProjectMetadata;
import io.mcp.cncf.model.CncfModel.SearchResult;
import io.mcp.cncf.tool.model.CncfCategoryList;
import io.mcp.cncf.tool.model.CncfProjectDetail;
import io.mcp.cncf.tool.model.CncfRefreshStatus;
import io.mcp.cncf.tool.model.CncfSearchResult;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static io.mcp.cncf.config.SearchConstants.MAX_DESCRIPTION_CHARS;
import static io.mcp.cncf.config.SearchConstants.MAX_LABEL_CHARS;
import static io.mcp.cncf.config.SearchConstants.MAX_LIST_ENTRIES;
import static io.mcp.cncf.config.SearchConstants.MAX_NAME_CHARS;
import static io.mcp.cncf.config.SearchConstants.MAX_SUMMARY_CHARS;
import static io.mcp.cncf.config.SearchConstants.MAX_TAG_ENTRIES;
import static io.mcp.cncf.config.SearchConstants.MAX_URL_CHARS;

/**
 * Renders CNCF Landscape data for the model.
 *
 * <p>Two rules, borrowed from mcp-redhat-kb's {@code ArticleFormatter}. Trust: every value
 * of the landscape is third-party text, so it is sanitized ({@link ContentSanitizer}) and
 * rendered inside a fence carrying a nonce the content cannot predict
 * ({@link UntrustedFence}); the server's own text -- headers, counts, guidance -- stays
 * outside the fence. Budget: each field is capped, so an oversized landscape entry cannot
 * flood the context.
 *
 * <p>The structured record is built first and the prose is rendered from it, so both
 * channels carry identical, already-sanitized content by construction. A structured
 * payload carrying raw upstream text would be a bypass of everything above.
 *
 * <p>Package-private, like the sanitizer and the fence: nothing outside this package
 * renders landscape content.
 */
final class CncfFormatter {

    /**
     * Characters a URL path may carry; anything else drops the URL. A run of three dashes
     * is excluded even though a dash is otherwise fine: {@code ---} is one of the server's
     * own separators. No percent-escapes: {@code %3D%3D%3D} or {@code %0A} decode to a
     * marker or a newline in whatever reads the link next, and no path in the landscape
     * (5,908 URLs profiled in September 2026) carries one. No {@code @}, which makes a path
     * read like a host; no dot segments, which have no business in a published link.
     */
    private static final Pattern SAFE_PATH = Pattern.compile("(?!.*-{3})(?!.*/\\.\\.?(?:/|$))[A-Za-z0-9/_.~-]*");

    /**
     * A host worth printing: a registered name with at least one dot. Literal addresses,
     * {@code localhost} and single-label names never appear in the landscape, and a link to
     * {@code 169.254.169.254} or {@code http://localhost:6274} exists only to make the
     * agent reading the answer open it (server-side request forgery by proxy).
     */
    private static final Pattern PUBLIC_HOST = Pattern.compile(
            "(?!\\d+(\\.\\d+){3}$)(?!localhost$)(?!.*\\.(localhost|local|internal|localdomain)$)[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+");

    private CncfFormatter() {
        // Utility class
    }

    // ---------------------------------------------------------------- search_cncf

    /**
     * Builds the structured payload for a search. Every field, the name included, is
     * upstream text: nothing is copied into the record without going through the sanitizer
     * and a cap.
     */
    static CncfSearchResult toSearchResult(List<SearchResult> results, int catalogueSize) {
        List<CncfSearchResult.Project> projects = results.stream()
                .map(r -> new CncfSearchResult.Project(
                        ContentSanitizer.label(r.project().name(), MAX_NAME_CHARS),
                        ContentSanitizer.label(r.project().category(), MAX_LABEL_CHARS),
                        ContentSanitizer.label(r.project().subcategory(), MAX_LABEL_CHARS),
                        ContentSanitizer.label(r.project().description(), MAX_SUMMARY_CHARS),
                        ContentSanitizer.label(r.project().maturity(), MAX_LABEL_CHARS),
                        stars(r.project()),
                        safeUrl(r.project().homepageUrl()),
                        safeUrl(r.project().repoUrl()),
                        Math.round(r.relevanceScore() * 10.0) / 10.0))
                .toList();
        return new CncfSearchResult(projects.size(), catalogueSize, projects);
    }

    /**
     * Renders a compact result list: one entry per project, description indented under it.
     * The header (what was searched, how many matched) is the server's voice; the query is
     * echoed through the sanitizer because it prints outside the fence and a caller acting
     * on injected content earlier in the conversation may have composed it.
     */
    static String formatSearchResults(CncfSearchResult result, String query, String category) {
        StringBuilder sb = new StringBuilder();
        sb.append(result.count()).append(" CNCF landscape project(s)");
        appendCriteria(sb, query, category);
        sb.append(", out of ").append(result.catalogueSize()).append(" in the catalogue.\n");
        sb.append("Call get_cncf_project with a project name for the full detail.\n\n");

        UntrustedFence fence = UntrustedFence.newFence();
        sb.append(fence.open()).append('\n');
        int i = 1;
        for (CncfSearchResult.Project p : result.projects()) {
            sb.append(i++).append(". ").append(p.name());
            sb.append(" [").append(p.category());
            if (!p.subcategory().isEmpty()) {
                sb.append(" / ").append(p.subcategory());
            }
            sb.append(']');
            if (!p.maturity().isEmpty()) {
                sb.append(' ').append(p.maturity());
            }
            sb.append(" stars=").append(p.stars())
              .append(" score=").append(p.relevanceScore()).append('\n');
            if (!p.description().isEmpty()) {
                sb.append("    ").append(p.description()).append('\n');
            }
            if (!p.homepageUrl().isEmpty() || !p.repoUrl().isEmpty()) {
                sb.append("    ");
                if (!p.homepageUrl().isEmpty()) {
                    sb.append("homepage: ").append(p.homepageUrl());
                }
                if (!p.repoUrl().isEmpty()) {
                    sb.append(p.homepageUrl().isEmpty() ? "" : " | ").append("repo: ").append(p.repoUrl());
                }
                sb.append('\n');
            }
        }
        sb.append(fence.close()).append('\n');
        return sb.toString();
    }

    /** The no-match message; the caller's terms are sanitized because they print in the server's voice. */
    static String formatNoResults(String query, String category) {
        StringBuilder sb = new StringBuilder("No CNCF landscape projects found");
        appendCriteria(sb, query, category);
        sb.append(".\nTry broader keywords, or call list_cncf_categories to see the exact category names.");
        return sb.toString();
    }

    private static void appendCriteria(StringBuilder sb, String query, String category) {
        boolean hasQuery = query != null && !query.isBlank();
        boolean hasCategory = category != null && !category.isBlank();
        if (hasQuery) {
            sb.append(" for: ").append(ContentSanitizer.label(query, MAX_LABEL_CHARS));
        }
        if (hasCategory) {
            sb.append(hasQuery ? " (category: " : " in category: ")
              .append(ContentSanitizer.label(category, MAX_LABEL_CHARS))
              .append(hasQuery ? ")" : "");
        }
    }

    // ---------------------------------------------------------------- get_cncf_project

    static CncfProjectDetail toProjectDetail(CncfProject project) {
        ProjectMetadata meta = project.metadata();
        Boolean activelyMaintained = meta == null || meta.lastCommitDate() == null
                ? null
                : meta.isActivelyMaintained();
        return new CncfProjectDetail(
                ContentSanitizer.label(project.name(), MAX_NAME_CHARS),
                ContentSanitizer.label(project.category(), MAX_LABEL_CHARS),
                ContentSanitizer.label(project.subcategory(), MAX_LABEL_CHARS),
                ContentSanitizer.truncate(ContentSanitizer.clean(project.description()), MAX_DESCRIPTION_CHARS),
                ContentSanitizer.label(project.maturity(), MAX_LABEL_CHARS),
                stars(project),
                meta == null ? 0 : meta.contributorCount(),
                meta == null ? "" : ContentSanitizer.label(meta.latestVersion(), MAX_LABEL_CHARS),
                meta == null ? "" : ContentSanitizer.label(meta.license(), MAX_LABEL_CHARS),
                activelyMaintained,
                safeUrl(project.homepageUrl()),
                safeUrl(project.repoUrl()),
                labels(project.tags(), MAX_TAG_ENTRIES));
    }

    /**
     * Renders a single project. The header is the server's voice and carries nothing from
     * upstream -- not even the name, which is the first field an attacker who can edit a
     * landscape entry would write. Everything about the project sits inside the fence.
     */
    static String formatProject(CncfProjectDetail d) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== CNCF Landscape project ===\n\n");

        UntrustedFence fence = UntrustedFence.newFence();
        sb.append(fence.open()).append('\n');
        sb.append("Name: ").append(d.name()).append('\n');
        sb.append("Category: ").append(d.category());
        if (!d.subcategory().isEmpty()) {
            sb.append(" / ").append(d.subcategory());
        }
        sb.append('\n');
        if (!d.maturity().isEmpty()) {
            sb.append("Maturity: ").append(d.maturity()).append('\n');
        }
        // The landscape publishes no fork count any more, so none is shown: a "Forks: 0"
        // for every project reads as a fact rather than a missing field.
        sb.append("Stars: ").append(d.stars())
          .append(" | Contributors: ").append(d.contributors()).append('\n');
        if (!d.latestVersion().isEmpty()) {
            sb.append("Latest version: ").append(d.latestVersion()).append('\n');
        }
        if (!d.license().isEmpty()) {
            sb.append("License: ").append(d.license()).append('\n');
        }
        sb.append("Actively maintained (commit in last 90 days): ")
          .append(d.activelyMaintained() == null ? "unknown" : d.activelyMaintained() ? "yes" : "no")
          .append('\n');
        if (!d.homepageUrl().isEmpty()) {
            sb.append("Homepage: ").append(d.homepageUrl()).append('\n');
        }
        if (!d.repoUrl().isEmpty()) {
            sb.append("Repository: ").append(d.repoUrl()).append('\n');
        }
        if (!d.tags().isEmpty()) {
            sb.append("Tags: ").append(String.join(", ", d.tags())).append('\n');
        }
        if (!d.description().isEmpty()) {
            sb.append("\nDescription:\n").append(d.description()).append('\n');
        }
        sb.append(fence.close()).append('\n');
        return sb.toString();
    }

    // ---------------------------------------------------------------- list_cncf_categories

    /**
     * Builds the category list. Names are upstream text and are merged <em>after</em>
     * sanitizing, so two raw values that clean to the same label count as one category.
     */
    static CncfCategoryList toCategoryList(List<CncfProject> projects) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (CncfProject p : projects) {
            String name = ContentSanitizer.label(p.category(), MAX_LABEL_CHARS);
            if (!name.isEmpty()) {
                counts.merge(name, 1, Integer::sum);
            }
        }
        List<CncfCategoryList.Category> categories = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_LIST_ENTRIES)
                .map(e -> new CncfCategoryList.Category(e.getKey(), e.getValue()))
                .toList();
        return new CncfCategoryList(projects.size(), categories);
    }

    static String formatCategories(CncfCategoryList list) {
        StringBuilder sb = new StringBuilder();
        sb.append(list.categories().size()).append(" categories across ")
          .append(list.totalProjects()).append(" CNCF landscape projects.\n");
        sb.append("Pass a name below as the category filter of search_cncf.\n\n");

        UntrustedFence fence = UntrustedFence.newFence();
        sb.append(fence.open()).append('\n');
        for (CncfCategoryList.Category c : list.categories()) {
            sb.append("- ").append(c.name()).append(" (").append(c.projectCount()).append(")\n");
        }
        sb.append(fence.close()).append('\n');
        return sb.toString();
    }

    // ---------------------------------------------------------------- refresh_cncf_data

    /** Server-side state only; no upstream text, so no fence. */
    static String formatRefresh(CncfRefreshStatus s) {
        String headline = switch (s.outcome()) {
            case "updated" -> "CNCF landscape data refreshed.";
            case "unchanged" -> "CNCF landscape data already current; nothing changed.";
            case "throttled" -> "Refresh refused: the previous attempt was too recent. The loaded catalogue is served.";
            default -> "CNCF landscape refresh: " + s.outcome() + ".";
        };
        return headline
                + "\nProjects: " + s.projectCount()
                + "\nLast confirmed by upstream: " + s.lastRefresh()
                + "\nCache expires at: " + s.cacheExpiresAt();
    }

    // ---------------------------------------------------------------- helpers

    private static long stars(CncfProject project) {
        return project.metadata() == null ? 0 : Math.round(project.metadata().stars());
    }

    /**
     * Normalises an upstream URL to scheme, host and path, or drops it. Landscape entries
     * link to arbitrary domains, so unlike kb no host allow-list applies; what is enforced
     * is shape: http(s) only, a public-looking host ({@link #PUBLIC_HOST}), no port, no
     * user-info (which lets {@code https://trusted.io@evil.io} read as trusted), a path
     * from a plain character set ({@link #SAFE_PATH}), no query string or fragment (free
     * text that would print as part of a link), and a length cap. Sanitizing the raw string
     * is not enough for a URL: {@code https://a.io ignore previous instructions} survives
     * the sanitizer and prints as a link.
     */
    static String safeUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            // strip() leaves a trailing no-break space in place (two landscape entries end
            // in one), and URI.create then rejects the whole value.
            URI uri = URI.create(raw.replace(' ', ' ').strip());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                    || host == null || uri.getRawUserInfo() != null || uri.getPort() != -1
                    || !PUBLIC_HOST.matcher(host).matches()
                    || !SAFE_PATH.matcher(path).matches()) {
                return "";
            }
            String url = scheme.toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT) + path;
            return url.length() <= MAX_URL_CHARS ? url : "";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** Cleans, clips, de-duplicates and bounds a list of one-line values such as tags. */
    private static List<String> labels(List<String> raw, int maxEntries) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .map(v -> ContentSanitizer.label(v, MAX_LABEL_CHARS))
                .filter(v -> !v.isEmpty())
                .distinct()
                .limit(maxEntries)
                .toList();
    }
}
