package io.mcp.cncf.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import com.github.tomakehurst.wiremock.http.Fault;
import io.mcp.cncf.model.CncfModel.CncfProject;
import io.mcp.cncf.service.CncfDataRefreshService;
import io.mcp.cncf.service.CncfDataRefreshService.Outcome;
import io.mcp.cncf.testing.LandscapeStub;
import io.mcp.cncf.testing.LandscapeStubProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The HTTP client and the refresh that consumes it, against a WireMock standing in for
 * {@code landscape.cncf.io}.
 *
 * <p>The bug of origin: the client requested {@code /full.json} at the site root, the
 * single-page app answered its {@code index.html} with a 200, and every refresh failed on the
 * first {@code '<'} -- while {@code refresh_cncf_data} reported the catalogue as "already
 * current". The first test pins the path; the rest pin that each way the upstream can
 * misbehave -- wrong content, an error status, a dropped connection, a body that dribbles
 * past the deadline, a body larger than the bound, a gzip bomb -- produces a recorded,
 * controlled failure in this server's own words that leaves the loaded catalogue in place.
 */
@QuarkusTest
@TestProfile(LandscapeStubProfile.class)
class LandscapeHttpTest {

    @Inject
    LandscapeHttp client;

    @Inject
    CncfDataRefreshService service;

    @BeforeEach
    void resetStub() {
        LandscapeStub.reset();
    }

    @Test
    @DisplayName("regression: requests /data/full.json, not the /full.json the SPA answers with HTML")
    void requestsTheDataPathNotTheSpaRoot() {
        // Both paths answer 200. Only the wrong one returns HTML, exactly as the live site
        // does, so a client that slid back to /full.json would get the SPA and fail here.
        LandscapeStub.server().stubFor(get(urlEqualTo("/full.json")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "text/html").withBody(LandscapeStub.SPA_INDEX_HTML)));
        LandscapeStub.serveCurrentLandscape();

        assertThat(client.dataUri().getPath()).isEqualTo(LandscapeHttp.DATA_PATH);
        LandscapeSource.Fetch fetch = client.fetch(Optional.empty());

        assertThat(fetch.notModified()).isFalse();
        assertThat(fetch.body()).startsWith("{").contains("\"items\"");
        LandscapeStub.server().verify(exactly(1), getRequestedFor(urlEqualTo(LandscapeStub.DATA_PATH))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("Accept-Encoding", equalTo("gzip"))
                .withHeader("If-None-Match", absent()));
        LandscapeStub.server().verify(exactly(0), getRequestedFor(urlEqualTo("/full.json")));
    }

    @Test
    @DisplayName("a gzip-encoded body is inflated; the landscape's CDN serves it that way")
    void inflatesGzipBody() {
        LandscapeStub.server().stubFor(LandscapeStub.dataRequest().willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withHeader("Content-Encoding", "gzip")
                .withBody(gzip(LandscapeStub.fixture("landscape-current.json")))));

        assertThat(service.forceRefresh()).isEqualTo(Outcome.UPDATED);
        assertCatalogueStillLoaded();
    }

    @Test
    @DisplayName("sends the validator back and takes a 304 as 'unchanged' without re-parsing")
    void conditionalRequestHonoursNotModified() {
        LandscapeStub.server().stubFor(LandscapeStub.dataRequest().willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withHeader("ETag", "W/\"abc123\"")
                .withBody(LandscapeStub.fixture("landscape-current.json"))));
        assertThat(service.forceRefresh()).isEqualTo(Outcome.UPDATED);
        Instant firstLoad = service.getLastRefresh();

        LandscapeStub.reset();
        LandscapeStub.server().stubFor(LandscapeStub.dataRequest()
                .withHeader("If-None-Match", equalTo("W/\"abc123\""))
                .willReturn(aResponse().withStatus(304)));
        // Anything without the validator gets a body that would replace the catalogue.
        LandscapeStub.server().stubFor(LandscapeStub.dataRequest()
                .withHeader("If-None-Match", absent())
                .willReturn(aResponse().withStatus(200).withBody(LandscapeStub.fixture("landscape-legacy.json"))));

        assertThat(service.forceRefresh()).isEqualTo(Outcome.UNCHANGED);

        assertCatalogueStillLoaded();
        assertThat(service.getLastError()).isNull();
        assertThat(service.getLastRefresh()).as("a 304 confirms the catalogue: it counts as a refresh")
                .isAfterOrEqualTo(firstLoad);
        LandscapeStub.server().verify(exactly(1), getRequestedFor(urlEqualTo(LandscapeStub.DATA_PATH))
                .withHeader("If-None-Match", equalTo("W/\"abc123\"")));
    }

    @Test
    @DisplayName("HTML where JSON is expected is a recorded failure, not 'already current' with zero projects")
    void htmlBodyIsARecordedFailureThatKeepsTheCatalogue() {
        loadGoodCatalogue();
        LandscapeStub.server().stubFor(LandscapeStub.dataRequest().willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "text/html").withBody(LandscapeStub.SPA_INDEX_HTML)));

        assertThat(service.forceRefresh()).isEqualTo(Outcome.FAILED);
        assertThat(service.getLastError()).as("the failure must be recorded").contains("not valid JSON");
        assertThat(service.getLastError()).as("no upstream bytes quoted back").doesNotContain("<").doesNotContain("doctype");
        assertThat(service.getLastErrorTime()).isNotNull();
        assertCatalogueStillLoaded();
    }

    @Test
    @DisplayName("an empty body is a recorded failure")
    void emptyBodyIsARecordedFailure() {
        loadGoodCatalogue();
        LandscapeStub.server().stubFor(LandscapeStub.dataRequest().willReturn(aResponse().withStatus(200).withBody("")));

        assertThat(service.forceRefresh()).isEqualTo(Outcome.FAILED);
        assertThat(service.getLastError()).contains("empty");
        assertCatalogueStillLoaded();
    }

    @Test
    @DisplayName("valid JSON with no items is a recorded failure, not an empty catalogue")
    void jsonWithoutItemsIsARecordedFailure() {
        loadGoodCatalogue();
        LandscapeStub.serveLandscape("{\"github_data\":{},\"items\":[]}");

        assertThat(service.forceRefresh()).isEqualTo(Outcome.FAILED);
        assertThat(service.getLastError()).contains("No projects found");
        assertCatalogueStillLoaded();
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 500, 503})
    @DisplayName("an HTTP error status is a recorded failure that names the status and quotes no body")
    void httpErrorIsARecordedFailure(int status) {
        loadGoodCatalogue();
        LandscapeStub.server().stubFor(LandscapeStub.dataRequest().willReturn(aResponse()
                .withStatus(status).withHeader("Content-Type", "text/html")
                .withBody("<h1>" + status + " secret-internal-host</h1>")));

        assertThat(service.forceRefresh()).isEqualTo(Outcome.FAILED);
        assertThat(service.getLastError()).contains("HTTP " + status).doesNotContain("secret-internal-host");
        assertCatalogueStillLoaded();
    }

    @ParameterizedTest
    @EnumSource(value = Fault.class, names = {"CONNECTION_RESET_BY_PEER", "RANDOM_DATA_THEN_CLOSE", "MALFORMED_RESPONSE_CHUNK"})
    @DisplayName("a connection dropped or a body cut mid-transfer is a recorded failure, not a crash or partial data")
    void transportFaultIsARecordedFailure(Fault fault) {
        loadGoodCatalogue();
        LandscapeStub.server().stubFor(LandscapeStub.dataRequest().willReturn(aResponse().withFault(fault)));

        assertThat(service.forceRefresh()).as(fault.name()).isEqualTo(Outcome.FAILED);
        assertThat(service.getLastError()).as(fault.name()).isEqualTo(LandscapeHttp.UNREACHABLE);
        assertCatalogueStillLoaded();
    }

    @Test
    @DisplayName("a body that dribbles in past the deadline is given up on at the deadline, not read to the end")
    void dribblingBodyTimesOutAtTheDeadline() {
        // The dangerous case is not a slow status line (a read timeout catches that) but a
        // 200 that arrives at once followed by a body trickling in chunk by chunk: each read
        // completes inside any per-read timeout, and the thread is held for the whole body.
        loadGoodCatalogue();
        LandscapeStub.server().stubFor(LandscapeStub.dataRequest().willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(LandscapeStub.fixture("landscape-current.json"))
                .withChunkedDribbleDelay(20, LandscapeStubProfile.REQUEST_TIMEOUT_MS * 4)));

        Instant start = Instant.now();
        Outcome outcome = service.forceRefresh();
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(outcome).isEqualTo(Outcome.FAILED);
        assertThat(service.getLastError()).isEqualTo(LandscapeHttp.TIMED_OUT);
        assertThat(elapsed).as("the client must not wait for the whole body")
                .isLessThan(Duration.ofMillis(LandscapeStubProfile.REQUEST_TIMEOUT_MS * 3L));
        assertCatalogueStillLoaded();
    }

    @Test
    @DisplayName("a body over the size bound is refused as too large, whether declared or streamed")
    void oversizedBodyIsRefused() {
        loadGoodCatalogue();
        String huge = "{\"items\":[" + "{\"name\":\"x\"},".repeat(LandscapeStubProfile.MAX_BYTES / 12) + "{}]}";
        assertThat(huge.length()).isGreaterThan(LandscapeStubProfile.MAX_BYTES);

        // Declared: Content-Length says it is too big, refused before the body is read.
        LandscapeStub.serveLandscape(huge);
        assertThat(service.forceRefresh()).isEqualTo(Outcome.FAILED);
        assertThat(service.getLastError()).isEqualTo(LandscapeHttp.TOO_LARGE);

        // Streamed: chunked transfer, no Content-Length; refused at the first byte over.
        LandscapeStub.reset();
        LandscapeStub.server().stubFor(LandscapeStub.dataRequest().willReturn(aResponse()
                .withStatus(200).withBody(huge).withChunkedDribbleDelay(8, 200)));
        assertThat(service.forceRefresh()).isEqualTo(Outcome.FAILED);
        assertThat(service.getLastError()).isEqualTo(LandscapeHttp.TOO_LARGE);
        assertCatalogueStillLoaded();
    }

    @Test
    @DisplayName("a gzip bomb -- small on the wire, enormous inflated -- is refused at the inflated bound")
    void gzipBombIsRefused() {
        loadGoodCatalogue();
        byte[] bomb = gzip("[" + "0,".repeat(LandscapeStubProfile.MAX_BYTES) + "0]");
        assertThat(bomb.length).as("the bomb itself passes the transfer bound").isLessThan(LandscapeStubProfile.MAX_BYTES / 10);
        LandscapeStub.server().stubFor(LandscapeStub.dataRequest().willReturn(aResponse()
                .withStatus(200).withHeader("Content-Encoding", "gzip").withBody(bomb)));

        assertThat(service.forceRefresh()).isEqualTo(Outcome.FAILED);
        assertThat(service.getLastError()).isEqualTo(LandscapeHttp.TOO_LARGE);
        assertCatalogueStillLoaded();
    }

    @Test
    @DisplayName("a Content-Encoding: gzip header on a body that is not gzip is a recorded failure")
    void corruptGzipIsARecordedFailure() {
        loadGoodCatalogue();
        LandscapeStub.server().stubFor(LandscapeStub.dataRequest().willReturn(aResponse()
                .withStatus(200).withHeader("Content-Encoding", "gzip").withBody("{\"items\":[]}")));

        assertThat(service.forceRefresh()).isEqualTo(Outcome.FAILED);
        assertThat(service.getLastError()).isEqualTo(LandscapeHttp.CORRUPT_ENCODING);
        assertCatalogueStillLoaded();
    }

    @Test
    @DisplayName("gunzipBounded refuses one byte over the bound and accepts the bound exactly")
    void gunzipBoundIsExact() {
        byte[] exact = gzip("a".repeat(1000));
        assertThat(LandscapeHttp.gunzipBounded(exact, 1000)).hasSize(1000);
        assertThatThrownBy(() -> LandscapeHttp.gunzipBounded(exact, 999))
                .isInstanceOf(LandscapeUnavailableException.class)
                .hasMessage(LandscapeHttp.TOO_LARGE);
    }

    @Test
    @DisplayName("a successful refresh after a failure clears the recorded error")
    void successClearsTheError() {
        LandscapeStub.server().stubFor(LandscapeStub.dataRequest().willReturn(aResponse().withStatus(500)));
        service.forceRefresh();
        assertThat(service.getLastError()).isNotBlank();

        LandscapeStub.reset();
        LandscapeStub.serveCurrentLandscape();
        assertThat(service.forceRefresh()).isEqualTo(Outcome.UPDATED);

        assertThat(service.getLastError()).isNull();
        assertThat(service.getLastErrorTime()).isNull();
    }

    private void loadGoodCatalogue() {
        LandscapeStub.serveCurrentLandscape();
        service.forceRefresh();
        assertCatalogueStillLoaded();
        LandscapeStub.reset();
    }

    private void assertCatalogueStillLoaded() {
        assertThat(service.getCurrentProjects()).extracting(CncfProject::name).contains("Kubernetes", "Envoy", "Linkerd");
    }

    static byte[] gzip(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
