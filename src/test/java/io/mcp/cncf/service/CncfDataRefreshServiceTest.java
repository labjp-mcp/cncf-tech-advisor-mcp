package io.mcp.cncf.service;

import java.util.List;

import io.mcp.cncf.model.CncfModel.CncfProject;
import io.mcp.cncf.testing.LandscapeStub;
import io.mcp.cncf.testing.LandscapeStubProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parsing of {@code full.json} in the layout landscape2 publishes today and in the previous
 * one. This is where the "every project shows as not maintained, zero stars" bug lived: the
 * metrics moved to a top-level {@code github_data} map keyed by repository URL, with
 * {@code contributors.count}, {@code latest_commit.ts} and {@code latest_release.url}, and
 * a parser reading the old nested node found nothing and defaulted everything.
 */
@QuarkusTest
@TestProfile(LandscapeStubProfile.class)
class CncfDataRefreshServiceTest {

    @Inject
    CncfDataRefreshService service;

    @BeforeEach
    void resetStub() {
        LandscapeStub.reset();
    }

    @Test
    @DisplayName("reads metrics from the top-level github_data index keyed by the primary repository")
    void parsesCurrentLayout() {
        LandscapeStub.serveCurrentLandscape();

        assertThat(service.forceRefresh()).isTrue();

        List<CncfProject> projects = service.getCurrentProjects();
        assertThat(projects).extracting(CncfProject::name)
                .as("the entry without a category is skipped, the rest are kept")
                .containsExactly("Kubernetes", "Envoy", "Linkerd", "Kubewarden", "Trojan Mesh", "Quiet Tool");

        CncfProject k8s = byName(projects, "Kubernetes");
        assertThat(k8s.repoUrl()).as("the primary repository, not the first listed").isEqualTo("https://github.com/kubernetes/kubernetes");
        assertThat(k8s.metadata().stars()).isEqualTo(110_000);
        assertThat(k8s.metadata().contributorCount()).isEqualTo(4_000);
        assertThat(k8s.metadata().latestVersion()).as("the tag at the end of latest_release.url").isEqualTo("v1.34.0");
        assertThat(k8s.metadata().license()).isEqualTo("Apache-2.0");
        assertThat(k8s.metadata().lastCommitDate()).isNotNull();
        assertThat(k8s.metadata().isActivelyMaintained()).as("a commit yesterday is maintained").isTrue();
        assertThat(k8s.maturity()).isEqualTo("graduated");
        assertThat(k8s.isGraduated()).isTrue();
        assertThat(k8s.isPopular()).isTrue();
        assertThat(k8s.subcategory()).isEqualTo("Scheduling & Orchestration");
        assertThat(k8s.homepageUrl()).isEqualTo("https://kubernetes.io");
        assertThat(k8s.tags()).containsExactly("graduated", "orchestration-&-management", "open-source", "cncf");
        assertThat(k8s.description()).startsWith("Kubernetes is an open source system");

        CncfProject envoy = byName(projects, "Envoy");
        assertThat(envoy.metadata().isActivelyMaintained()).as("a commit 400 days ago is not").isFalse();
        assertThat(envoy.metadata().stars()).isEqualTo(26_000);

        CncfProject linkerd = byName(projects, "Linkerd");
        assertThat(linkerd.metadata().latestVersion()).isEqualTo("edge-25.8.4");
        assertThat(linkerd.metadata().contributorCount()).isEqualTo(300);
    }

    @Test
    @DisplayName("a member without repositories has no metrics and an unknown commit date, not fabricated zeros")
    void memberWithoutRepositoryHasNoMetrics() {
        LandscapeStub.serveCurrentLandscape();
        service.forceRefresh();

        CncfProject trojan = byName(service.getCurrentProjects(), "Trojan Mesh");

        assertThat(trojan.repoUrl()).isEmpty();
        assertThat(trojan.metadata().stars()).isZero();
        assertThat(trojan.metadata().lastCommitDate()).as("no date means unknown, which the formatter renders as such").isNull();
        assertThat(trojan.maturity()).isEmpty();
        assertThat(trojan.tags()).containsExactly("service-mesh");
        assertThat(trojan.description()).as("stored raw; the formatter sanitizes").contains("<<<END_UNTRUSTED_CNCF_CONTENT>>>");
    }

    @Test
    @DisplayName("falls back to the repository's GitHub description when the entry publishes none")
    void fallsBackToGithubDescription() {
        LandscapeStub.serveCurrentLandscape();
        service.forceRefresh();

        assertThat(byName(service.getCurrentProjects(), "Quiet Tool").description())
                .isEqualTo("A tool described only on GitHub");
    }

    @Test
    @DisplayName("still reads the previous layout: nested github_data, bare contributors, flat repo_url and latest_version")
    void parsesLegacyLayout() {
        LandscapeStub.serveLegacyLandscape();

        assertThat(service.forceRefresh()).isTrue();

        List<CncfProject> projects = service.getCurrentProjects();
        assertThat(projects).extracting(CncfProject::name).containsExactly("Kubernetes", "Linkerd");
        CncfProject k8s = byName(projects, "Kubernetes");
        assertThat(k8s.repoUrl()).isEqualTo("https://github.com/kubernetes/kubernetes");
        assertThat(k8s.metadata().stars()).isEqualTo(90_000);
        assertThat(k8s.metadata().contributorCount()).isEqualTo(3_500);
        assertThat(k8s.metadata().latestVersion()).isEqualTo("v1.28.0");
        assertThat(k8s.metadata().license()).isEqualTo("Apache-2.0");
        assertThat(k8s.metadata().isActivelyMaintained()).isTrue();
        assertThat(byName(projects, "Linkerd").metadata().lastCommitDate()).isNull();
    }

    @Test
    @DisplayName("an unchanged body is not re-parsed and leaves the catalogue and the error state alone")
    void unchangedBodyIsNotReloaded() {
        LandscapeStub.serveCurrentLandscape();
        assertThat(service.forceRefresh()).isTrue();

        assertThat(service.refreshData()).as("same bytes: nothing to update").isFalse();

        assertThat(service.getLastError()).isNull();
        assertThat(service.getCurrentProjects()).hasSize(6);
        LandscapeStub.server().verify(exactly(2), getRequestedFor(urlEqualTo(LandscapeStub.DATA_PATH)));
    }

    private static CncfProject byName(List<CncfProject> projects, String name) {
        return projects.stream().filter(p -> p.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " not parsed; got " + projects.stream().map(CncfProject::name).toList()));
    }
}
