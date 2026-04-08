package io.github.aihio.bot;

import io.github.aihio.bot.tiktok.TikTokDownloader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Body;
import org.apache.camel.Exchange;
import org.apache.camel.Header;
import org.apache.camel.component.telegram.TelegramConstants;
import org.apache.camel.component.telegram.model.IncomingMessage;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class TelegramMessageHandler {

    public static final String EX_PROP_TEMP_PATHS = "telegram.temp.paths";
    public static final String EX_PROP_PROGRESS_MESSAGE_ID = "telegram.progress.message.id";

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
        return handle(incomingMessage, chatId, null);
    }

    public Object handle(@Body IncomingMessage incomingMessage,
                         @Header(TelegramConstants.TELEGRAM_CHAT_ID) String chatId,
                         Exchange exchange) {
        var incoming = incomingMessage == null ? null : incomingMessage.getText();
        if (isStartCommand(incoming)) {
            return startCommandMessageHandler.handle(incomingMessage);
        }
        var validationResponse = validateTikTokInput(incoming, chatId);
        if (validationResponse != null) {
            return validationResponse;
        }

        var progressMessageId = sendProgressIndicator(chatId);
        rememberProgressMessage(exchange, progressMessageId);
        var downloadedPaths = List.<Path>of();
        try {
            var media = tiktokDownloader.downloadMedia(incoming);
            downloadedPaths = collectDownloadedPaths(media);
            rememberDownloadedPaths(exchange, downloadedPaths);
            sendPrimaryMedia(chatId, media);
            sendAudio(chatId, media.audioPath());
            // Media was already sent directly, so skip producer route output for this exchange.
            return null;
        } catch (TikTokDownloader.TikTokDownloadException e) {
            if (exchange != null) {
                throw e;
            }
            return defaultMessageHandler.handle("Failed to process this TikTok link. Please try another link.");
        } finally {
            if (exchange == null) {
                cleanupDownloadedPaths(downloadedPaths);
                telegramVideoSender.deleteMessage(chatId, progressMessageId);
            }
        }
    }

    private Object validateTikTokInput(String incoming, String chatId) {
        if (!isValidTikTokUrl(incoming)) {
            return defaultMessageHandler.handle(incoming);
        }
        if (chatId == null || chatId.isBlank()) {
            return defaultMessageHandler.handle("Unable to detect Telegram chat id for this message.");
        }
        return null;
    }

    private void sendPrimaryMedia(String chatId, TikTokDownloader.DownloadedMedia media) {
        if (media.gallery()) {
            telegramVideoSender.sendMediaGroup(chatId, media.photoPaths());
            return;
        }

        var videoPath = media.videoPath();
        if (videoPath == null) {
            throw new TikTokDownloader.TikTokDownloadException("Extraction failure: TikTok post has no playable media");
        }
        telegramVideoSender.sendVideo(chatId, videoPath, videoPath.getFileName().toString());
    }

    private void sendAudio(String chatId, Path audioPath) {
        if (audioPath == null) {
            throw new TikTokDownloader.TikTokDownloadException("Extraction failure: TikTok post has no audio track");
        }
        telegramVideoSender.sendAudio(chatId, audioPath, audioPath.getFileName().toString());
    }

    private Long sendProgressIndicator(String chatId) {
        return telegramVideoSender.sendTextMessage(chatId, "⏳");
    }

    private ArrayList<Path> collectDownloadedPaths(TikTokDownloader.DownloadedMedia media) {
        var paths = new ArrayList<Path>();
        if (media.videoPath() != null) {
            paths.add(media.videoPath());
        }
        paths.addAll(media.photoPaths());
        if (media.audioPath() != null) {
            paths.add(media.audioPath());
        }
        return paths;
    }

    private void rememberDownloadedPaths(Exchange exchange, List<Path> downloadedPaths) {
        if (exchange == null) {
            return;
        }
        exchange.setProperty(EX_PROP_TEMP_PATHS, List.copyOf(downloadedPaths));
    }

    private void rememberProgressMessage(Exchange exchange, Long progressMessageId) {
        if (exchange == null) {
            return;
        }
        exchange.setProperty(EX_PROP_PROGRESS_MESSAGE_ID, progressMessageId);
    }

    private void cleanupDownloadedPaths(List<Path> downloadedPaths) {
        for (var path : downloadedPaths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // ignore cleanup failure for temporary download artifacts
            }
        }
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
