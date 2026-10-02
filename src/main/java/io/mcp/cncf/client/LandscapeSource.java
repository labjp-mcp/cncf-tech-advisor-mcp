package io.mcp.cncf.client;

import java.util.Optional;

/**
 * Where the landscape's {@code full.json} comes from. One implementation talks HTTP
 * ({@link LandscapeHttp}); tests substitute a counting fake to prove that the cache in
 * front of it does not call more often than it should.
 */
public interface LandscapeSource {

    /**
     * A successful exchange: either a new body with the validator upstream sent for it,
     * or the statement that the body behind {@code knownEtag} is still current.
     *
     * @param body the JSON text; empty when {@link #notModified()}
     * @param etag the validator to present next time, if upstream sent one
     * @param notModified true when upstream answered 304 to the conditional request
     */
    record Fetch(String body, Optional<String> etag, boolean notModified) {

        public static Fetch unchanged(Optional<String> etag) {
            return new Fetch("", etag, true);
        }

        public static Fetch of(String body, Optional<String> etag) {
            return new Fetch(body, etag, false);
        }
    }

    /**
     * Fetches the landscape, conditionally when a validator from a previous fetch is given.
     *
     * @throws LandscapeUnavailableException when the landscape could not be obtained; the
     *         message is one of this server's own sentences and safe to show the model
     */
    Fetch fetch(Optional<String> knownEtag) throws LandscapeUnavailableException;
}
