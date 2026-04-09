package io.github.aihio.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultMessageHandlerTest {

    private final DefaultMessageHandler handler = new DefaultMessageHandler();

    @Test
    void returnsPromptForBlankInput() {
        var reply = handler.handle();

        assertEquals("Please send a TikTok URL.", reply.getText());
    }

    @Test
    void returnsUnavailableMessageForUnavailableVideos() {
        var reply = handler.handleUnavailableVideo();

        assertEquals("The TikTok video is unavailable or has been deleted. Please try another link.", reply.getText());
    }

    @Test
    void returnsInvalidUrlMessage() {
        var reply = handler.handleInvalidUrl();

        assertEquals("The link you provided is invalid. Please share a valid TikTok URL.", reply.getText());
    }

    @Test
    void returnsProcessingErrorMessage() {
        var reply = handler.handleProcessingError();

        assertEquals("Failed to process this TikTok link. Please try another link.", reply.getText());
    }
}

