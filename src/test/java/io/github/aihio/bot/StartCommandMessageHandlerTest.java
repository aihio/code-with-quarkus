package io.github.aihio.bot;

import org.apache.camel.component.telegram.model.IncomingMessage;
import org.apache.camel.component.telegram.model.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StartCommandMessageHandlerTest {

    private final StartCommandMessageHandler handler = new StartCommandMessageHandler();

    @Test
    void greetsUserByUsernameWhenAvailable() {
        var incomingMessage = new IncomingMessage();
        incomingMessage.setFrom(user("Username", "FirstName"));

        var reply = handler.handle(incomingMessage);

        assertEquals("Hi, Username! Paste a TikTok link and I will download it for you.", reply.getText());
    }

    @Test
    void fallsBackToFirstNameWhenUsernameIsMissing() {
        var incomingMessage = new IncomingMessage();
        incomingMessage.setFrom(user(null, "FirstName"));

        var reply = handler.handle(incomingMessage);

        assertEquals("Hi, FirstName! Paste a TikTok link and I will download it for you.", reply.getText());
    }

    private User user(String username, String firstName) {
        var user = new User();
        user.setUsername(username);
        user.setFirstName(firstName);
        return user;
    }
}

