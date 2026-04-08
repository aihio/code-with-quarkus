package io.github.aihio.bot;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.apache.camel.component.telegram.model.User;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class BotWiringTest {

    @Inject
    StartCommandMessageHandler startCommandMessageHandler;

    @Inject
    TelegramMessageHandler telegramMessageHandler;

    @Inject
    TikTokDownloaderRuntimeConfig runtimeConfig;

    @Inject
    HttpClient httpClient;

    @Inject
    CamelContext camelContext;

    @Test
    void injectsCoreBeansAndConfigDefaults() {
        assertNotNull(startCommandMessageHandler);
        assertNotNull(telegramMessageHandler);
        assertNotNull(runtimeConfig);
        assertNotNull(httpClient);
        assertNotNull(camelContext);

        assertEquals(Duration.ofSeconds(10), runtimeConfig.connectTimeout());
        assertEquals(Duration.ofSeconds(30), runtimeConfig.readTimeout());
        assertEquals(2, runtimeConfig.retries());
        assertEquals(Optional.of(Duration.ofSeconds(10)), httpClient.connectTimeout());
        assertEquals(HttpClient.Redirect.NORMAL, httpClient.followRedirects());
        assertTrue(httpClient.cookieHandler().isPresent());
        assertNotNull(camelContext.getRoute("route1"));
    }

    @Test
    void startCommandHandlerWorksThroughCdi() {
        var incomingMessage = new IncomingMessage();
        incomingMessage.setFrom(user("Username", "FirstName"));

        var reply = startCommandMessageHandler.handle(incomingMessage);

        assertEquals("Hi, Username! Paste a TikTok link and I will download it for you.", reply.getText());
    }

    private User user(String username, String firstName) {
        var user = new User();
        user.setUsername(username);
        user.setFirstName(firstName);
        return user;
    }
}

