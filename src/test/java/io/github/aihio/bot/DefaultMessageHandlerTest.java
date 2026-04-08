package io.github.aihio.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultMessageHandlerTest {

    private final DefaultMessageHandler handler = new DefaultMessageHandler();

    @Test
    void returnsPromptForBlankInput() {
        var reply = handler.handle("   ");

        assertEquals("Please send a TikTok URL.", reply.getText());
    }

    @Test
    void echoesIncomingText() {
        var reply = handler.handle("hello");

        assertEquals("hello", reply.getText());
    }
}

