package io.mcp.cncf.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Downloads {@code full.json} from the landscape within fixed bounds.
 *
 * <p>Replaces the MicroProfile REST client interface, which buffered a body of any size
 * into a String and bounded only the wait between reads. Here {@link BoundedExchange} caps
 * both the bytes and the wall-clock of the whole exchange, and every failure is mapped to a
 * fixed sentence of this server's own before it leaves this class.
 *
 * <p>Two things keep the traffic to the CNCF small. The request is conditional
 * ({@code If-None-Match}) when a validator is known: the landscape sits behind CloudFront
 * and answers 304 with no body when nothing changed, so a refresh of a current catalogue
 * costs a few hundred bytes. And the body is requested gzip-encoded, which cuts a full
 * download to roughly a sixth; {@link HttpClient} does not decompress on its own, so the
 * decompression is done here, bounded to the same limit as the compressed transfer -- a
 * small compressed body that inflates without end is refused at the limit, not at the
 * heap.
 */
@ApplicationScoped
public class LandscapeHttp implements LandscapeSource {

    private static final Logger LOG = Logger.getLogger(LandscapeHttp.class);

    /**
     * The path of the data file at the site root. Fixed in code, not configuration: with
     * {@code /full.json} alone the single-page app answers its index.html with a 200, and
     * every refresh fails on the first {@code '<'}.
     */
    public static final String DATA_PATH = "/data/full.json";

    static final String UNREACHABLE = "The CNCF Landscape could not be reached.";
    static final String TIMED_OUT = "The CNCF Landscape did not answer within the time allowed.";
    static final String TOO_LARGE = "The CNCF Landscape response exceeded the size this server accepts.";
    static final String INTERRUPTED = "The download of the CNCF Landscape was interrupted.";
    static final String CORRUPT_ENCODING = "The CNCF Landscape response was not valid gzip data.";

    private static final int OK = 200;
    private static final int NOT_MODIFIED = 304;

    private final LandscapeConfig config;
    private final URI dataUri;
    private final HttpClient httpClient;

    @Inject
    public LandscapeHttp(LandscapeConfig config) {
        this.config = config;
        this.dataUri = URI.create(stripTrailingSlash(config.baseUrl()) + DATA_PATH);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** The URL this client requests; exposed for the tests that pin the path. */
    public URI dataUri() {
        return dataUri;
    }

    @Override
    public Fetch fetch(Optional<String> knownEtag) {
        HttpRequest.Builder request = HttpRequest.newBuilder(dataUri)
                .GET()
                .timeout(config.requestTimeout())
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip")
                .header("User-Agent", "cncf-tech-advisor-mcp");
        knownEtag.ifPresent(etag -> request.header("If-None-Match", etag));

        BoundedExchange.Outcome outcome;
        try {
            outcome = BoundedExchange.send(httpClient, request.build(), Set.of(OK), config.maxBytes(),
                    config.requestTimeout());
        } catch (BoundedExchange.ResponseTooLargeException e) {
            throw new LandscapeUnavailableException(TOO_LARGE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LandscapeUnavailableException(INTERRUPTED);
        } catch (TimeoutException e) {
            throw new LandscapeUnavailableException(TIMED_OUT);
        } catch (IOException e) {
            // Logged with the cause for the operator; the model gets the fixed sentence.
            LOG.debugf(e, "landscape fetch failed: %s", e.getMessage());
            throw new LandscapeUnavailableException(UNREACHABLE);
        }

        Optional<String> etag = outcome.headers().firstValue("ETag");
        if (outcome.status() == NOT_MODIFIED) {
            return Fetch.unchanged(knownEtag);
        }
        if (outcome.status() != OK) {
            // The status is ours to state; the body was never read (see BoundedExchange).
            throw new LandscapeUnavailableException(
                    "The CNCF Landscape answered HTTP " + outcome.status() + " instead of the data file.");
        }
        byte[] body = outcome.body();
        if (isGzip(outcome.headers().firstValue("Content-Encoding"))) {
            body = gunzipBounded(body, config.maxBytes());
        }
        return Fetch.of(new String(body, StandardCharsets.UTF_8), etag);
    }

    private static boolean isGzip(Optional<String> contentEncoding) {
        return contentEncoding.map(v -> v.trim().equalsIgnoreCase("gzip") || v.trim().equalsIgnoreCase("x-gzip"))
                .orElse(false);
    }

    /**
     * Inflates {@code compressed} up to {@code maxBytes}; one byte more is a refusal. The
     * compression ratio of gzip is bounded at about 1032:1, so a 16 KiB body can claim
     * 16 MiB -- the limit has to be on the output, not on the input.
     */
    static byte[] gunzipBounded(byte[] compressed, int maxBytes) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, compressed.length * 8));
            byte[] chunk = new byte[64 * 1024];
            int read;
            while ((read = in.read(chunk)) != -1) {
                if (out.size() + read > maxBytes) {
                    throw new LandscapeUnavailableException(TOO_LARGE);
                }
                out.write(chunk, 0, read);
            }
            return out.toByteArray();
        } catch (ZipException e) {
            throw new LandscapeUnavailableException(CORRUPT_ENCODING);
        } catch (IOException e) {
            throw new LandscapeUnavailableException(CORRUPT_ENCODING, e);
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
