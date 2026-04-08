package io.github.aihio.bot;

import org.junit.jupiter.api.Test;

import java.net.CookieHandler;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SharedHttpClientProducerTest {

    private final SharedHttpClientProducer producer = new SharedHttpClientProducer();

    @Test
    void createsHttpClientUsingRuntimeConfig() {
        var runtimeConfig = new TestRuntimeConfig(Duration.ofSeconds(3), Duration.ofSeconds(7), 1, "JUnit");

        try (var client = producer.httpClient(runtimeConfig)) {

            assertEquals(HttpClient.Redirect.NORMAL, client.followRedirects());
            assertEquals(Optional.of(Duration.ofSeconds(3)), client.connectTimeout());
            assertTrue(client.cookieHandler().isPresent());
            assertInstanceOf(CookieHandler.class, client.cookieHandler().orElseThrow());
        }
    }

    private record TestRuntimeConfig(Duration connectTimeout, Duration readTimeout, int retries,
                                     String userAgent) implements TikTokDownloaderRuntimeConfig {
    }
}

