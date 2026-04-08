package io.github.aihio.bot;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;

@ApplicationScoped
public class MessageProcessor extends RouteBuilder {

    private static final String TELEGRAM_PRODUCER_URI = "telegram:bots";

    private final TelegramMessageHandler telegramMessageHandler;

    @Inject
    public MessageProcessor(TelegramMessageHandler telegramMessageHandler) {
        this.telegramMessageHandler = telegramMessageHandler;
    }

    @Override
    public void configure() {
        from("telegram:bots")
                .bean(telegramMessageHandler, "handle")
                .filter(body().isNotNull())
                .to(TELEGRAM_PRODUCER_URI);
    }
}
