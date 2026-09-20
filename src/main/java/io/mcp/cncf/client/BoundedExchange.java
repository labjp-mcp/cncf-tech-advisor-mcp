package io.mcp.cncf.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One HTTP exchange bounded in <em>both</em> dimensions an upstream can abuse: the whole of
 * it -- connection, headers and body -- has to finish within one deadline, and the body may
 * occupy at most a fixed number of bytes.
 *
 * <p>Ported from mcp-redhat-kb's {@code BoundedExchange}. {@link HttpRequest.Builder#timeout}
 * alone does not do this: it bounds the wait for the response headers and nothing after
 * them, so a server that answers the status line at once and then dribbles the body holds
 * the calling thread for as long as the body takes. The MicroProfile REST client this
 * replaced had the same shape of limit (a per-read timeout) and no size limit at all: a
 * hostile or merely broken {@code full.json} of any size was buffered whole into a String.
 * Here the request is sent asynchronously, the caller waits on the future for the deadline,
 * and on expiry the future is cancelled, which aborts the exchange and closes the connection.
 *
 * <p>The size bound is enforced by the body subscriber while the bytes arrive: it cancels
 * the subscription at the first byte over the limit, so heap use is bounded by the limit
 * plus one network chunk regardless of what the server sends. A {@code Content-Length}
 * beyond the limit is refused before a single body byte is requested, and the body of a
 * reply with a status the caller did not ask to read is never read at all.
 */
final class BoundedExchange {

    /** Raised when the response body would exceed the size bound. */
    static final class ResponseTooLargeException extends RuntimeException {
        ResponseTooLargeException() {
            super("response body exceeds the size bound", null, false, false);
        }
    }

    /** Status, headers and raw body of a bounded exchange; the body is empty for statuses not read. */
    record Outcome(int status, HttpHeaders headers, byte[] body) {
    }

    private BoundedExchange() {
        // Utility class
    }

    /**
     * Performs the exchange.
     *
     * @param readBodyFor the statuses whose body is read; any other status yields an empty body
     * @param deadline how long the whole exchange may take, body included
     * @throws ResponseTooLargeException when the body would exceed {@code maxBodyBytes}
     * @throws TimeoutException when the deadline expires; the exchange has been cancelled
     * @throws IOException when the connection fails or the body is cut off
     * @throws InterruptedException when the calling thread is interrupted; the exchange has
     *         been cancelled
     */
    static Outcome send(HttpClient client, HttpRequest request, Set<Integer> readBodyFor, int maxBodyBytes,
            Duration deadline) throws IOException, InterruptedException, TimeoutException {
        // Cancelling the body subscription aborts the exchange, and HttpClient may then fail
        // the response with its own "subscription cancelled" IOException before it looks at
        // what the subscriber decided. The refusal is therefore recorded on the side, so it
        // is reported as the size refusal it is and not as a network failure.
        AtomicBoolean refusedAsTooLarge = new AtomicBoolean();
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request, info -> {
            if (!readBodyFor.contains(info.statusCode())) {
                return new RefusingSubscriber(CompletableFuture.completedFuture(new byte[0]));
            }
            long declared = info.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > maxBodyBytes) {
                refusedAsTooLarge.set(true);
                return new RefusingSubscriber(CompletableFuture.failedFuture(new ResponseTooLargeException()));
            }
            return new BoundedSubscriber(maxBodyBytes, (int) Math.max(0, declared), refusedAsTooLarge);
        });
        try {
            HttpResponse<byte[]> response = pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
            return new Outcome(response.statusCode(), response.headers(), response.body());
        } catch (TimeoutException | InterruptedException e) {
            // mayInterruptIfRunning=true is what makes HttpClient abort the exchange.
            pending.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            if (refusedAsTooLarge.get()) {
                throw new ResponseTooLargeException();
            }
            throw unwrap(e);
        }
    }

    /**
     * Maps whatever the future failed with back onto the exceptions this class documents.
     * HttpClient wraps some failures more than once, so the chain is walked rather than only
     * the direct cause inspected.
     */
    private static IOException unwrap(ExecutionException e) {
        for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
            if (t instanceof IOException io) {
                return io;
            }
        }
        return new IOException(e.getCause() == null ? e : e.getCause());
    }

    /** Cancels the transfer on subscription; the body is settled before any byte arrives. */
    private static final class RefusingSubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final CompletableFuture<byte[]> body;

        RefusingSubscriber(CompletableFuture<byte[]> body) {
            this.body = body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.cancel();
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            // Nothing was requested; anything that still arrives is dropped.
        }

        @Override
        public void onError(Throwable throwable) {
            // The outcome was decided before the transfer; a failure to abort it is moot.
        }

        @Override
        public void onComplete() {
            // Already settled.
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }
    }

    /** Accumulates the body, refusing it at the first byte over the bound. */
    private static final class BoundedSubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final int maxBytes;
        private final ByteArrayOutputStream buffer;
        private final AtomicBoolean refusedAsTooLarge;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private boolean settled;

        BoundedSubscriber(int maxBytes, int expectedBytes, AtomicBoolean refusedAsTooLarge) {
            this.maxBytes = maxBytes;
            this.buffer = new ByteArrayOutputStream(Math.min(Math.max(expectedBytes, 1024), maxBytes));
            this.refusedAsTooLarge = refusedAsTooLarge;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> chunks) {
            if (settled) {
                return;
            }
            for (ByteBuffer chunk : chunks) {
                int length = chunk.remaining();
                if (buffer.size() + length > maxBytes) {
                    settled = true;
                    refusedAsTooLarge.set(true);
                    subscription.cancel();
                    body.completeExceptionally(new ResponseTooLargeException());
                    return;
                }
                byte[] bytes = new byte[length];
                chunk.get(bytes);
                buffer.write(bytes, 0, length);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) {
            if (!settled) {
                settled = true;
                body.completeExceptionally(throwable);
            }
        }

        @Override
        public void onComplete() {
            if (!settled) {
                settled = true;
                body.complete(buffer.toByteArray());
            }
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }
    }
}
