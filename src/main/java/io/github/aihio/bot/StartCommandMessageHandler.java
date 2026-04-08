package io.github.aihio.bot;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.apache.camel.component.telegram.model.OutgoingTextMessage;

@ApplicationScoped
public class StartCommandMessageHandler {

    public OutgoingTextMessage handle(IncomingMessage incomingMessage) {
        var message = new OutgoingTextMessage();
        var username = resolveDisplayName(incomingMessage);
        message.setText("Hi, " + username + "! Paste a TikTok link and I will download it for you.");
        return message;
    }

    private String resolveDisplayName(IncomingMessage incomingMessage) {
        if (incomingMessage == null || incomingMessage.getFrom() == null) {
            return "there";
        }

        var username = incomingMessage.getFrom().getUsername();
        if (username != null && !username.isBlank()) {
            return username;
        }

        var firstName = incomingMessage.getFrom().getFirstName();
        if (firstName != null && !firstName.isBlank()) {
            return firstName;
        }
        return "there";
    }
}

