package io.mcp.cncf.client;

import java.time.Duration;
import java.time.Instant;

import com.github.tomakehurst.wiremock.http.Fault;
import io.mcp.cncf.model.CncfModel.CncfProject;
import io.mcp.cncf.service.CncfDataRefreshService;
import io.mcp.cncf.testing.LandscapeStub;
import io.mcp.cncf.testing.LandscapeStubProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client and the refresh that consumes it, against a WireMock standing in for
 * {@code landscape.cncf.io}.
 *
 * <p>The bug of origin: the client requested {@code /full.json} at the site root, the
 * single-page app answered its {@code index.html} with a 200, and every refresh failed on the
 * first {@code '<'} -- while {@code refresh_cncf_data} reported the catalogue as "already
 * current". The first test pins the path; the rest pin that each way the upstream can
 * misbehave produces a recorded, controlled failure that leaves the loaded catalogue in
 * place rather than a crash, an empty catalogue or a success message.
 */
@QuarkusTest
@TestProfile(LandscapeStubProfile.class)
class CncfLandscapeClientTest {

    @Inject
    @RestClient
    CncfLandscapeClient client;

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

        String body = client.getFullLandscapeData();

        assertThat(body).startsWith("{").contains("\"items\"");
        LandscapeStub.server().verify(exactly(1), getRequestedFor(urlEqualTo(LandscapeStub.DATA_PATH)));
        LandscapeStub.server().verify(exactly(0), getRequestedFor(urlEqualTo("/full.json")));
        LandscapeStub.server().verify(getRequestedFor(urlEqualTo(LandscapeStub.DATA_PATH))
                .withHeader("Accept", com.github.tomakehurst.wiremock.client.WireMock.containing("application/json")));
    }

    @Test
    @DisplayName("HTML where JSON is expected is a recorded failure, not 'already current' with zero projects")
    void htmlBodyIsARecordedFailureThatKeepsTheCatalogue() {
        loadGoodCatalogue();
        LandscapeStub.server().stubFor(LandscapeStub.dataRequest().willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "text/html").withBody(LandscapeStub.SPA_INDEX_HTML)));

        boolean updated = service.forceRefresh();

        assertThat(updated).isFalse();
        assertThat(service.getLastError()).as("the failure must be recorded").contains("not valid JSON");
        assertThat(service.getLastError()).as("no upstream bytes quoted back").doesNotContain("<!doctype");
        assertThat(service.getLastErrorTime()).isNotNull();
        assertCatalogueStillLoaded();
    }

    @Test
    @DisplayName("an empty body is a recorded failure")
    void emptyBodyIsARecordedFailure() {
        loadGoodCatalogue();
        LandscapeStub.server().stubFor(LandscapeStub.dataRequest().willReturn(aResponse().withStatus(200).withBody("")));

        assertThat(service.forceRefresh()).isFalse();
        assertThat(service.getLastError()).contains("empty");
        assertCatalogueStillLoaded();
    }

    @Test
    @DisplayName("valid JSON with no items is a recorded failure, not an empty catalogue")
    void jsonWithoutItemsIsARecordedFailure() {
        loadGoodCatalogue();
        LandscapeStub.serveLandscape("{\"github_data\":{},\"items\":[]}");

        assertThat(service.forceRefresh()).isFalse();
        assertThat(service.getLastError()).contains("No projects found");
        assertCatalogueStillLoaded();
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 500, 503})
    @DisplayName("an HTTP error status is a recorded failure that keeps the catalogue")
    void httpErrorIsARecordedFailure(int status) {
        loadGoodCatalogue();
        LandscapeStub.server().stubFor(LandscapeStub.dataRequest().willReturn(aResponse()
                .withStatus(status).withHeader("Content-Type", "text/html").withBody("<h1>" + status + "</h1>")));

        assertThat(service.forceRefresh()).isFalse();
        assertThat(service.getLastError()).as("status " + status).isNotBlank();
        assertCatalogueStillLoaded();
    }

    @ParameterizedTest
    @EnumSource(value = Fault.class, names = {"CONNECTION_RESET_BY_PEER", "RANDOM_DATA_THEN_CLOSE", "MALFORMED_RESPONSE_CHUNK"})
    @DisplayName("a connection dropped or a body cut mid-transfer is a recorded failure, not a crash or partial data")
    void transportFaultIsARecordedFailure(Fault fault) {
        loadGoodCatalogue();
        LandscapeStub.server().stubFor(LandscapeStub.dataRequest().willReturn(aResponse().withFault(fault)));

        assertThat(service.forceRefresh()).as(fault.name()).isFalse();
        assertThat(service.getLastError()).as(fault.name()).isNotBlank();
        assertCatalogueStillLoaded();
    }

    @Test
    @DisplayName("a body that arrives slower than the read timeout is given up on, within the timeout")
    void slowBodyTimesOut() {
        loadGoodCatalogue();
        LandscapeStub.server().stubFor(LandscapeStub.dataRequest().willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(LandscapeStub.fixture("landscape-current.json"))
                .withFixedDelay(LandscapeStubProfile.READ_TIMEOUT_MS * 4)));

        Instant start = Instant.now();
        boolean updated = service.forceRefresh();
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(updated).isFalse();
        assertThat(service.getLastError()).isNotBlank();
        assertThat(elapsed).as("the client must not wait for the whole delay")
                .isLessThan(Duration.ofMillis(LandscapeStubProfile.READ_TIMEOUT_MS * 3L));
        assertCatalogueStillLoaded();
    }

    @Test
    @DisplayName("a successful refresh after a failure clears the recorded error")
    void successClearsTheError() {
        LandscapeStub.server().stubFor(LandscapeStub.dataRequest().willReturn(aResponse().withStatus(500)));
        service.forceRefresh();
        assertThat(service.getLastError()).isNotBlank();

        LandscapeStub.reset();
        LandscapeStub.serveCurrentLandscape();
        assertThat(service.forceRefresh()).isTrue();

        assertThat(service.getLastError()).isNull();
        assertThat(service.getLastErrorTime()).isNull();
        assertThat(service.isDataFresh()).isTrue();
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
}
