package io.github.aihio.bot;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.component.telegram.model.OutgoingTextMessage;

@ApplicationScoped
public class DefaultMessageHandler {

    public OutgoingTextMessage handle(String incoming) {
        var message = new OutgoingTextMessage();
        if (incoming == null || incoming.isBlank()) {
            message.setText("Please send a TikTok URL.");
            return message;
        }
        message.setText(incoming);
        return message;
    }
}

