package io.mcp.cncf.config;

/**
 * Configuration constants for search, scoring and the size of what is handed to the model.
 */
public final class SearchConstants {

    private SearchConstants() {
        // Utility class - prevent instantiation
    }

    // Search limits
    public static final int MIN_SEARCH_RESULTS = 1;
    public static final int DEFAULT_SEARCH_LIMIT = 10;
    public static final int MAX_SEARCH_RESULTS = 100;

    /** Longest keyword or category filter accepted; published as {@code maxLength} in the schema. */
    public static final int MAX_QUERY_LENGTH = 200;

    // Scoring thresholds
    public static final double CONFIDENCE_THRESHOLD = 0.5;

    // Project maturity values
    public static final String MATURITY_SANDBOX = "sandbox";
    public static final String MATURITY_INCUBATING = "incubating";
    public static final String MATURITY_GRADUATED = "graduated";

    // Technology matching thresholds
    public static final int MIN_QUERY_LENGTH = 2;

    // -------------------------------------------------------------------------
    // Per-field caps on upstream text. Every value of the CNCF Landscape is third-party
    // content (anyone can send a pull request to it), so nothing is rendered unbounded.
    // -------------------------------------------------------------------------

    /** Project names; the landscape's longest are well under this. */
    public static final int MAX_NAME_CHARS = 120;

    /** One-line values: category, subcategory, maturity, license, version, tags. */
    public static final int MAX_LABEL_CHARS = 80;

    /** Description on a search hit: one line, enough to tell projects apart. */
    public static final int MAX_SUMMARY_CHARS = 300;

    /** Description on a project detail. */
    public static final int MAX_DESCRIPTION_CHARS = 2000;

    /** Homepage and repository URLs after normalisation. */
    public static final int MAX_URL_CHARS = 200;

    /** Most tags rendered for a project, and most categories in the category list. */
    public static final int MAX_LIST_ENTRIES = 100;

    /** Most tags kept on a project detail. */
    public static final int MAX_TAG_ENTRIES = 25;

    /** Prefix of the marker appended when a field is truncated. */
    public static final String TRUNCATION_MARKER = "[truncated -";
}
