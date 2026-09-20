package io.mcp.cncf.tool;

import io.quarkiverse.mcp.server.ToolResponse;
import org.jboss.logging.Logger;

/**
 * Turns an unexpected exception into a tool error the model can act on without learning
 * anything about the server's insides.
 *
 * <p>The replaced {@code ErrorHandler} appended {@code e.getMessage()} to its default
 * branches, which for a runtime failure means class names, file paths, hostnames and
 * whatever an upstream body an exception chose to quote -- all of it delivered into the
 * model's context, some of it steerable by the landscape. Here the exception, stack and
 * all, goes to the server log, and the caller gets one fixed sentence that names the tool.
 */
final class ToolErrors {

    private static final Logger LOG = Logger.getLogger(ToolErrors.class);

    private ToolErrors() {
        // Utility class
    }

    /** Logs the failure with its cause and returns a generic error response. */
    static ToolResponse internal(String tool, Exception e) {
        LOG.errorf(e, "%s failed", tool);
        return ToolResponse.error(tool + " failed because of an internal error; the server log has the detail. "
                + "Try again, or call refresh_cncf_data if the catalogue may be stale.");
    }

    /** The message shown when the caller is over its rate limit. */
    static String rateLimited(int callsPerMinute) {
        return "Rate limit exceeded: at most " + callsPerMinute
                + " tool calls per minute per caller. Wait a moment and retry.";
    }
}
