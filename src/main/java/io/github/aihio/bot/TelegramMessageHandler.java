package io.github.aihio.bot;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Body;
import org.apache.camel.Header;
import org.apache.camel.component.telegram.TelegramConstants;
import org.apache.camel.component.telegram.model.IncomingMessage;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;

@ApplicationScoped
public class TelegramMessageHandler {

    private final TikTokDownloader tiktokDownloader;
    private final DefaultMessageHandler defaultMessageHandler;
    private final TelegramVideoSender telegramVideoSender;
    private final StartCommandMessageHandler startCommandMessageHandler;

    @Inject
    public TelegramMessageHandler(TikTokDownloader tiktokDownloader,
                                  DefaultMessageHandler defaultMessageHandler,
                                  TelegramVideoSender telegramVideoSender,
                                  StartCommandMessageHandler startCommandMessageHandler) {
        this.tiktokDownloader = tiktokDownloader;
        this.defaultMessageHandler = defaultMessageHandler;
        this.telegramVideoSender = telegramVideoSender;
        this.startCommandMessageHandler = startCommandMessageHandler;
    }

    public Object handle(@Body IncomingMessage incomingMessage,
                         @Header(TelegramConstants.TELEGRAM_CHAT_ID) String chatId) {
        var incoming = incomingMessage == null ? null : incomingMessage.getText();
        if (isStartCommand(incoming)) {
            return startCommandMessageHandler.handle(incomingMessage);
        }
        if (!isValidTikTokUrl(incoming)) {
            return defaultMessageHandler.handle(incoming);
        }
        if (chatId == null || chatId.isBlank()) {
            return defaultMessageHandler.handle("Unable to detect Telegram chat id for this message.");
        }

        var progressMessageId = sendProgressIndicator(chatId);
        var videoPath = tiktokDownloader.download(incoming);
        var sent = false;
        try {
            var fileName = videoPath.getFileName().toString();
            telegramVideoSender.sendVideo(chatId, videoPath, fileName);
            sent = true;
            // Video was already sent directly, so skip producer route output for this exchange.
            return null;
        } finally {
            try {
                Files.deleteIfExists(videoPath);
            } catch (IOException ignored) {
                // ignore cleanup failure for temporary download artifacts
            }
            if (sent && progressMessageId != null) {
                telegramVideoSender.deleteMessage(chatId, progressMessageId);
            }
        }
    }

    private Long sendProgressIndicator(String chatId) {
        return telegramVideoSender.sendTextMessage(chatId, "⏳");
    }

    private boolean isStartCommand(String incoming) {
        if (incoming == null) {
            return false;
        }
        var normalized = incoming.trim();
        if (!normalized.startsWith("/start")) {
            return false;
        }
        if (normalized.length() == 6) {
            return true;
        }
        var suffix = normalized.charAt(6);
        return suffix == ' ' || suffix == '@';
    }

    private boolean isValidTikTokUrl(String incoming) {
        if (incoming == null || incoming.isBlank()) {
            return false;
        }

        try {
            var uri = new URI(incoming.trim());
            var scheme = uri.getScheme();
            if (scheme == null) {
                return false;
            }
            var normalizedScheme = scheme.toLowerCase();
            if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
                return false;
            }

            var host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            var normalizedHost = host.toLowerCase();
            return normalizedHost.equals("tiktok.com") || normalizedHost.endsWith(".tiktok.com");
        } catch (URISyntaxException ignored) {
            return false;
        }
    }
}
