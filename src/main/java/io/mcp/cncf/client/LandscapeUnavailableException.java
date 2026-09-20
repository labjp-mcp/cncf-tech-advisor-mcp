package io.mcp.cncf.client;

/**
 * The landscape could not be obtained or was not usable. The message is always one of
 * this server's own fixed sentences -- it names what went wrong in the caller's terms and
 * quotes nothing from the wire: no upstream body, no exception text, no internal host or
 * path. It is what {@code refresh_cncf_data} reports to the model.
 */
public class LandscapeUnavailableException extends RuntimeException {

    public LandscapeUnavailableException(String message) {
        super(message);
    }

    public LandscapeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
