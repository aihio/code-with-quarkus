package io.github.aihio.bot;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.telegram.TelegramConstants;
import org.apache.camel.component.telegram.model.IncomingMessage;

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
                .process(exchange -> {
                    var incomingMessage = exchange.getIn().getBody(IncomingMessage.class);
                    var chatId = exchange.getIn().getHeader(TelegramConstants.TELEGRAM_CHAT_ID, String.class);
                    var response = telegramMessageHandler.handle(incomingMessage, chatId);
                    exchange.getIn().setBody(response);
                })
                .filter(body().isNotNull())
                .to(TELEGRAM_PRODUCER_URI);
    }
}
