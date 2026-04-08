package io.github.aihio.bot;

import io.github.aihio.bot.tiktok.TikTokDownloader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.telegram.TelegramConstants;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@ApplicationScoped
public class MessageProcessor extends RouteBuilder {

    private static final String TELEGRAM_PRODUCER_URI = "telegram:bots";
    private static final Logger LOG = Logger.getLogger(MessageProcessor.class);

    private final TelegramMessageHandler telegramMessageHandler;
    private final DefaultMessageHandler defaultMessageHandler;
    private final TelegramVideoSender telegramVideoSender;

    @Inject
    public MessageProcessor(TelegramMessageHandler telegramMessageHandler,
                            DefaultMessageHandler defaultMessageHandler,
                            TelegramVideoSender telegramVideoSender) {
        this.telegramMessageHandler = telegramMessageHandler;
        this.defaultMessageHandler = defaultMessageHandler;
        this.telegramVideoSender = telegramVideoSender;
    }

    @Override
    public void configure() {
        onException(TikTokDownloader.TikTokDownloadException.class)
                .handled(true)
                .process(exchange -> {
                    var failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
                    if (failure != null) {
                        LOG.warn("TikTok processing failed", failure);
                    }
                    exchange.getIn().setBody(
                            defaultMessageHandler.handle("Failed to process this TikTok link. Please try another link."));
                });

        onCompletion()
                .process(this::cleanupExchangeArtifacts);

        from("telegram:bots")
                .process(exchange -> {
                    var incomingMessage = exchange.getIn().getBody(IncomingMessage.class);
                    var chatId = exchange.getIn().getHeader(TelegramConstants.TELEGRAM_CHAT_ID, String.class);
                    var response = telegramMessageHandler.handle(incomingMessage, chatId, exchange);
                    exchange.getIn().setBody(response);
                })
                .filter(body().isNotNull())
                .to(TELEGRAM_PRODUCER_URI);
    }

    void cleanupExchangeArtifacts(Exchange exchange) {
        var tempPaths = exchange.getProperty(TelegramMessageHandler.EX_PROP_TEMP_PATHS, List.class);
        if (tempPaths != null) {
            for (var rawPath : tempPaths) {
                if (!(rawPath instanceof Path path)) {
                    continue;
                }
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort cleanup only
                }
            }
        }

        var chatId = exchange.getIn().getHeader(TelegramConstants.TELEGRAM_CHAT_ID, String.class);
        var progressMessageId = exchange.getProperty(TelegramMessageHandler.EX_PROP_PROGRESS_MESSAGE_ID, Long.class);
        if (chatId != null && !chatId.isBlank() && progressMessageId != null) {
            telegramVideoSender.deleteMessage(chatId, progressMessageId);
        }
    }
}
