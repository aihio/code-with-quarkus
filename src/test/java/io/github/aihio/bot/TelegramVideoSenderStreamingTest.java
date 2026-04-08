package io.github.aihio.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramVideoSenderStreamingTest {

    @Test
    void sendVideoPublishesMultipartInChunksAndStreamsFileFromDisk() throws IOException {
        var httpClient = new CapturingHttpClient();
        var sender = new TelegramVideoSender(
                new ObjectMapper(),
                httpClient,
                URI.create("https://telegram.test/bot-token/")
        );

        var file = Files.createTempFile("telegram-streaming-", ".mp4");
        try {
            writeRandomFile(file);
            var fileSize = Files.size(file);

            sender.sendVideo("123", file, "streaming.mp4");

            assertTrue(httpClient.chunkCount > 3, "multipart publisher should emit multiple chunks");
            assertTrue(httpClient.publishedBytes > fileSize, "multipart envelope must add overhead");
            assertTrue(httpClient.publishedBytes < fileSize + 128 * 1024,
                    "envelope overhead should stay small and not duplicate full media in memory");
            assertFalse(httpClient.contentType == null || httpClient.contentType.isBlank());
            assertTrue(httpClient.contentType.startsWith("multipart/form-data; boundary="));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private void writeRandomFile(Path file) throws IOException {
        var random = new SecureRandom();
        var buffer = new byte[8192];
        try (var outputStream = Files.newOutputStream(file)) {
            var remaining = 33554432;
            while (remaining > 0) {
                var chunkSize = Math.min(remaining, buffer.length);
                random.nextBytes(buffer);
                outputStream.write(buffer, 0, chunkSize);
                remaining -= chunkSize;
            }
        }
    }

    private static final class CapturingHttpClient extends HttpClient {
        private long publishedBytes;
        private int chunkCount;
        private String contentType;

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NORMAL;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SSLParameters sslParameters() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            contentType = request.headers().firstValue("Content-Type").orElse(null);
            var publisher = request.bodyPublisher().orElseThrow();
            var latch = new CountDownLatch(1);
            var stats = new long[]{0L, 0L};
            var errors = new RuntimeException[1];

            publisher.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(ByteBuffer item) {
                    stats[0] += item.remaining();
                    stats[1]++;
                }

                @Override
                public void onError(Throwable throwable) {
                    errors[0] = new RuntimeException(throwable);
                    latch.countDown();
                }

                @Override
                public void onComplete() {
                    latch.countDown();
                }
            });

            try {
                assertTrue(latch.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            if (errors[0] != null) {
                throw errors[0];
            }

            publishedBytes = stats[0];
            chunkCount = (int) stats[1];

            @SuppressWarnings("unchecked")
            var response = (HttpResponse<T>) new StubResponse(request.uri(), 200, "{\"ok\":true,\"result\":{\"message_id\":1}}", request);
            return response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler,
                                                                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }
    }

    private record StubResponse(URI uri,
                                int statusCode,
                                String body,
                                HttpRequest request) implements HttpResponse<String> {

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Map.of(), (_, _) -> true);
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return uri;
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}

