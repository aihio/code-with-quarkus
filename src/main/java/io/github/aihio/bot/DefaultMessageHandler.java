package io.github.aihio.bot;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.component.telegram.model.OutgoingTextMessage;

@ApplicationScoped
public class DefaultMessageHandler {

    public OutgoingTextMessage handle() {
        return OutgoingTextMessage.builder().text("Please send a TikTok URL.").build();
    }

    public OutgoingTextMessage handleUnavailableVideo() {
        return OutgoingTextMessage.builder()
                .text("The TikTok video is unavailable or has been deleted. Please try another link.").build();
    }

    public OutgoingTextMessage handleInvalidUrl() {
        return OutgoingTextMessage.builder()
                .text("The link you provided is invalid. Please share a valid TikTok URL.").build();
    }

    public OutgoingTextMessage handleProcessingError() {
        return OutgoingTextMessage.builder()
                .text("Failed to process this TikTok link. Please try another link.").build();
    }
}

