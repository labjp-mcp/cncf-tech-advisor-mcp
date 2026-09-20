package io.mcp.cncf.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * A WireMock standing in for {@code landscape.cncf.io}, shared by every {@code @QuarkusTest}.
 *
 * <p>Started once per JVM, lazily, because Quarkus reads the configuration (and therefore
 * the stub's port) before any test instance exists; {@link LandscapeStubProfile} points the
 * REST client at it. Tests reset the mappings in their {@code @BeforeEach} and configure
 * what they need, so no test sees another test's stubs.
 *
 * <p>The fixtures are trimmed copies of {@code full.json}: a handful of projects, not the
 * 3.8 MB the landscape publishes. Two placeholders are replaced relative to the clock, so
 * the "actively maintained" flag (a commit within 90 days) does not rot as the fixture ages
 * and the tests stay deterministic without depending on a particular date.
 */
public final class LandscapeStub {

    /** The path the client must request; the site root answers its SPA at anything else. */
    public static final String DATA_PATH = "/data/full.json";

    /** What the single-page app returns for a path it does not know, {@code /full.json} included. */
    public static final String SPA_INDEX_HTML =
            "<!doctype html><html><head><title>CNCF Landscape</title></head>"
                    + "<body><div id=\"app\"></div><script src=\"/assets/index.js\"></script></body></html>";

    private static volatile WireMockServer server;

    private LandscapeStub() {
    }

    public static WireMockServer server() {
        WireMockServer current = server;
        if (current == null) {
            synchronized (LandscapeStub.class) {
                current = server;
                if (current == null) {
                    current = new WireMockServer(options().dynamicPort().bindAddress("127.0.0.1"));
                    current.start();
                    WireMockServer started = current;
                    Runtime.getRuntime().addShutdownHook(new Thread(started::stop));
                    server = current;
                }
            }
        }
        return current;
    }

    /** Base URL the REST client is pointed at: the site root, exactly as in production. */
    public static String url() {
        return "http://127.0.0.1:" + server().port();
    }

    /** Drops every mapping and every recorded request. */
    public static void reset() {
        server().resetAll();
    }

    /** Serves the current-layout fixture at {@link #DATA_PATH}. */
    public static void serveCurrentLandscape() {
        serveLandscape(fixture("landscape-current.json"));
    }

    /** Serves the legacy-layout fixture at {@link #DATA_PATH}. */
    public static void serveLegacyLandscape() {
        serveLandscape(fixture("landscape-legacy.json"));
    }

    /** Serves an arbitrary body as JSON at {@link #DATA_PATH}. */
    public static void serveLandscape(String json) {
        server().stubFor(dataRequest().willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(json)));
    }

    /** The request every landscape stub answers. */
    public static MappingBuilder dataRequest() {
        return get(urlEqualTo(DATA_PATH));
    }

    /**
     * Reads a fixture from the test classpath and fills its clock-relative placeholders:
     * a recent commit (yesterday) and a stale one (well past the 90-day window).
     */
    public static String fixture(String name) {
        try (InputStream in = LandscapeStub.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalArgumentException("no fixture named " + name + " on the test classpath");
            }
            Instant now = Instant.now();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("__RECENT_COMMIT__", now.minus(Duration.ofDays(1)).toString())
                    .replace("__STALE_COMMIT__", now.minus(Duration.ofDays(400)).toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
