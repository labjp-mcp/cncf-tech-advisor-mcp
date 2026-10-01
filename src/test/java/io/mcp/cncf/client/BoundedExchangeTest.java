package io.mcp.cncf.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the race the real HttpClient only loses on some platforms: refusing a body cancels the
 * subscription, and the client may then fail the exchange with its own IOException. Here the
 * client always loses it, so the outcome cannot depend on the machine running the build.
 */
class BoundedExchangeTest {

    private static final HttpRequest REQUEST = HttpRequest.newBuilder(URI.create("http://127.0.0.1/data")).build();
    private static final HttpHeaders ETAG = HttpHeaders.of(Map.of("ETag", List.of("W/\"abc\"")), (k, v) -> true);

    @Test
    @DisplayName("a 304 whose exchange is cancelled is still reported as 304, not as a network failure")
    void unreadStatusSurvivesCancelledExchange() throws Exception {
        HttpClient client = clientThatAnswersThenCancels(304, ETAG);

        BoundedExchange.Outcome outcome = BoundedExchange.send(client, REQUEST, Set.of(200), 1024, Duration.ofSeconds(1));

        assertThat(outcome.status()).isEqualTo(304);
        assertThat(outcome.headers().firstValue("ETag")).contains("W/\"abc\"");
        assertThat(outcome.body()).isEmpty();
    }

    @Test
    @DisplayName("an error status whose exchange is cancelled keeps its status")
    void errorStatusSurvivesCancelledExchange() throws Exception {
        HttpClient client = clientThatAnswersThenCancels(503, HttpHeaders.of(Map.of(), (k, v) -> true));

        assertThat(BoundedExchange.send(client, REQUEST, Set.of(200), 1024, Duration.ofSeconds(1)).status())
                .isEqualTo(503);
    }

    @Test
    @DisplayName("a failure before any status arrives is still a network failure")
    void failureWithoutStatusIsAnIoError() {
        HttpClient client = mock(HttpClient.class);
        when(client.sendAsync(any(), any())).thenReturn(CompletableFuture.failedFuture(new IOException("refused")));

        assertThatThrownBy(() -> BoundedExchange.send(client, REQUEST, Set.of(200), 1024, Duration.ofSeconds(1)))
                .isInstanceOf(IOException.class);
    }

    @SuppressWarnings("unchecked")
    private static HttpClient clientThatAnswersThenCancels(int status, HttpHeaders headers) {
        HttpClient client = mock(HttpClient.class);
        when(client.sendAsync(any(), any())).thenAnswer(invocation -> {
            HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
            handler.apply(new HttpResponse.ResponseInfo() {
                @Override
                public int statusCode() {
                    return status;
                }

                @Override
                public HttpHeaders headers() {
                    return headers;
                }

                @Override
                public HttpClient.Version version() {
                    return HttpClient.Version.HTTP_1_1;
                }
            });
            return CompletableFuture.failedFuture(new IOException("subscription cancelled"));
        });
        return client;
    }
}
