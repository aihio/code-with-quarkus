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
import java.nio.file.Path;
import java.util.ArrayList;

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
        var downloadedPaths = new ArrayList<Path>();
        try {
            var media = tiktokDownloader.downloadMedia(incoming);
            downloadedPaths = collectDownloadedPaths(media);
            if (media.gallery()) {
                telegramVideoSender.sendMediaGroup(chatId, media.photoPaths());
            } else {
                var videoPath = media.videoPath();
                if (videoPath == null) {
                    throw new TikTokDownloader.TikTokDownloadException("Extraction failure: TikTok post has no playable media");
                }
                var fileName = videoPath.getFileName().toString();
                telegramVideoSender.sendVideo(chatId, videoPath, fileName);
            }

            var audioPath = media.audioPath();
            if (audioPath == null) {
                throw new TikTokDownloader.TikTokDownloadException("Extraction failure: TikTok post has no audio track");
            }
            telegramVideoSender.sendAudio(chatId, audioPath, audioPath.getFileName().toString());
            // Media was already sent directly, so skip producer route output for this exchange.
            return null;
        } catch (RuntimeException e) {
            return defaultMessageHandler.handle("Failed to process this TikTok link. Please try another link.");
        } finally {
            for (var path : downloadedPaths) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // ignore cleanup failure for temporary download artifacts
                }
            }
            telegramVideoSender.deleteMessage(chatId, progressMessageId);
        }
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
