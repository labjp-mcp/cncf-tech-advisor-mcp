package io.mcp.cncf.tool;

import java.util.Optional;

import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Records which tool was invoked, by which address, with what argument (clipped), and how
 * it ended. One line per call on the {@code io.mcp.cncf.audit} category, so an operator can
 * see an agent loop, a refused caller or a run of failures without turning on the
 * extension's traffic logging (which would dump whole catalogue pages into the log).
 *
 * <p>Entries carry the caller's address and a short, quote-safe excerpt of the argument,
 * never a response body.
 */
@ApplicationScoped
public class ToolAuditLog {

    private static final Logger LOG = Logger.getLogger("io.mcp.cncf.audit");

    /** Truncation bound for the argument preview kept in the audit record. */
    private static final int ARGUMENT_PREVIEW_CHARS = 60;

    private final Instance<HttpServerRequest> request;

    @Inject
    public ToolAuditLog(Instance<HttpServerRequest> request) {
        this.request = request;
    }

    /** Records a completed invocation. */
    public void record(String tool, String argument, String outcome, long elapsedMillis) {
        LOG.infof("tool=%s source=%s outcome=%s ms=%d arg=\"%s\"",
                tool, sourceAddress(), outcome, elapsedMillis, preview(argument));
    }

    /** Records a refused invocation, so repeated refusals are visible. */
    public void recordDenied(String tool, String reason) {
        LOG.warnf("tool=%s source=%s denied=\"%s\"", tool, sourceAddress(), reason);
    }

    private String sourceAddress() {
        try {
            if (request.isResolvable()) {
                var address = request.get().remoteAddress();
                return address == null || address.hostAddress() == null ? "unresolved" : address.hostAddress();
            }
        } catch (RuntimeException e) {
            // No active HTTP request, as on stdio.
        }
        return "local";
    }

    /**
     * Keeps a short, quote-safe excerpt so an audit line stays greppable and cannot be
     * broken up (or made to look like a second line) by the argument's own characters.
     */
    static String preview(String argument) {
        String text = Optional.ofNullable(argument).orElse("")
                .replaceAll("[\\p{Cntrl}\"\\u2028\\u2029]", " ").strip();
        return text.length() <= ARGUMENT_PREVIEW_CHARS
                ? text
                : text.substring(0, ARGUMENT_PREVIEW_CHARS) + "...";
    }
}
