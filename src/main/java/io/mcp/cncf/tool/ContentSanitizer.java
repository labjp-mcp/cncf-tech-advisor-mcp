package io.mcp.cncf.tool;

import java.util.List;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;

import static io.mcp.cncf.config.SearchConstants.TRUNCATION_MARKER;

/**
 * Sanitizes CNCF Landscape content before it is handed to the language model.
 *
 * <p>Ported from mcp-redhat-kb's {@code com.redhat.kb.mcp.ContentSanitizer}, which went
 * through audit and pentest; only the fence label and the truncation notice differ.
 *
 * <p>Every field of the landscape -- project name, description, category, URLs -- is
 * third-party text: the landscape is a public repository that accepts pull requests, so a
 * value may carry HTML, and may mimic this server's own output structure in an attempt to
 * steer the model. The sanitizer strips markup, breaks the structural markers the formatter
 * emits, removes invisible characters and clips each value to a budget.
 *
 * <p>Package-private on purpose: nothing outside {@code io.mcp.cncf.tool} renders upstream
 * content, so nothing can bypass this.
 */
final class ContentSanitizer {

    /**
     * Matches a run of the structural separators emitted by the formatter, at any position
     * of a line: entity-encoded HTML ({@code &lt;&lt;&lt;}) only becomes a literal marker
     * after Jsoup decodes it, and by then it can sit mid-line where a line-anchored pattern
     * never looks. The whole run is matched, not just its first three characters: matching
     * {@code ===} alone turned {@code ====} into {@code = ===}, which still carries a marker.
     * See {@link #breakRun}.
     */
    private static final Pattern STRUCTURAL_MARKER = Pattern.compile("(-{3,}|={3,}|<{3,})");

    /**
     * Matches a reproduced fence label wherever it appears. Displacing it is not enough --
     * mid-line it would still read as a fence -- so the bracket run is split apart, which
     * leaves the text legible but no longer parseable as a marker. The nonce in
     * {@link UntrustedFence} is the real guarantee; this is defense in depth.
     */
    private static final Pattern FENCE_LABEL =
            Pattern.compile("<{3,}\\s*((?:END_)?UNTRUSTED_CNCF_CONTENT)");

    /**
     * Line breaks other than {@code \n}: a carriage return, and the Unicode line and
     * paragraph separators, which no regex {@code \n} matches but a reader treats as a new
     * line. Folded to {@code \n} before anything else, so the one-line rule of
     * {@link #label} and the excess-blank-line rule below see them.
     */
    private static final Pattern EXOTIC_LINE_BREAKS = Pattern.compile("\\r\\n|[\\r\\u0085\\u2028\\u2029]");

    /**
     * Characters with no visible width: C0 and C1 controls other than tab and newline, the
     * zero-width characters, the bidirectional overrides and isolates (Trojan Source), and
     * the byte order mark. Placed inside a marker (a zero-width space between the first two
     * brackets of {@code <<<}) they hide it from the patterns above while a reader still
     * sees {@code <<<}; and each one costs six characters once JSON-escaped.
     */
    private static final Pattern INVISIBLE = Pattern.compile(
            "[\\p{Cc}&&[^\\n\\t]]|[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF]");

    private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("\n{3,}");

    /** Any whitespace in the Unicode sense, so an ideographic space folds like an ASCII one. */
    private static final Pattern WHITESPACE = Pattern.compile("(?U)\\s+");

    private ContentSanitizer() {
        // Utility class
    }

    /**
     * Strips HTML, neutralizes structural markers and collapses excess whitespace.
     *
     * @return the cleaned text, or an empty string when {@code raw} is null or blank
     */
    static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        // Parse as HTML even when the field is plain text: wholeText() decodes entities
        // and drops tags without introducing markup of its own.
        String text = Jsoup.parse(raw, "", Parser.htmlParser()).wholeText();

        text = EXOTIC_LINE_BREAKS.matcher(text).replaceAll("\n");
        // Before the marker rules, so a zero-width character cannot keep a run apart.
        text = INVISIBLE.matcher(text).replaceAll("");

        // Fence labels first: once split they no longer contain "<<<", so the generic
        // marker rule does not touch them a second time.
        text = FENCE_LABEL.matcher(text).replaceAll("<< < $1");
        text = STRUCTURAL_MARKER.matcher(text).replaceAll(m -> breakRun(m.group(1)));
        text = EXCESS_BLANK_LINES.matcher(text).replaceAll("\n\n");
        // Non-breaking spaces survive entity decoding and would otherwise reach the
        // model as opaque characters.
        text = text.replace(' ', ' ');

        return text.strip();
    }

    /**
     * Breaks a run of marker characters so that no three stand together, wherever it ends
     * up: {@code ===} becomes {@code = ==}, {@code ====} becomes {@code = == =}. The run is
     * split from the inside rather than pushed right with a space, because a leading space
     * is undone by the final {@code strip()}. The result contains no run of three, so
     * cleaning it again changes nothing -- the sanitizer is idempotent on its own output.
     */
    private static String breakRun(String run) {
        StringBuilder sb = new StringBuilder(run.length() + run.length() / 2 + 1);
        sb.append(run.charAt(0));
        for (int i = 1; i < run.length(); i++) {
            if ((i - 1) % 2 == 0) {
                sb.append(' ');
            }
            sb.append(run.charAt(i));
        }
        return sb.toString();
    }

    /**
     * Cleans every entry of a list, dropping those that end up empty.
     */
    static List<String> clean(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .map(ContentSanitizer::clean)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Cleans a one-line value -- a name, a category, a tag -- and clips it to
     * {@code maxChars} with an ellipsis. One line by definition: a newline inside a label
     * is a line that starts in the server's voice, right after a header the model reads as
     * trusted.
     */
    static String label(String raw, int maxChars) {
        String cleaned = WHITESPACE.matcher(clean(raw)).replaceAll(" ");
        if (cleaned.length() <= maxChars) {
            return cleaned;
        }
        return cleaned.substring(0, cutBefore(cleaned, maxChars - 1)) + "…";
    }

    /**
     * Truncates text to {@code maxChars}, appending a marker that tells the model content
     * was omitted rather than letting it silently assume the description ended.
     */
    static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        int cut = cutBefore(text, maxChars);
        int omitted = text.length() - cut;
        return text.substring(0, cut)
                + "\n" + TRUNCATION_MARKER + " " + omitted
                + " more characters; open the project homepage for the full text]";
    }

    /**
     * The cut point at or just before {@code index} that does not split a surrogate pair.
     * A lone high surrogate serializes as a {@code \\uD83D} escape with no low half, which
     * a strict JSON parser rejects -- one emoji at the boundary would cost such a client the
     * whole response.
     */
    private static int cutBefore(String text, int index) {
        return index > 0 && Character.isHighSurrogate(text.charAt(index - 1)) ? index - 1 : index;
    }
}
