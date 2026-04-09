package io.github.aihio.bot;

import io.github.aihio.bot.cache.CachedMedia;
import io.github.aihio.bot.cache.FileIdCache;
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
import java.util.logging.Logger;

@ApplicationScoped
public class TelegramMessageHandler {

    public static final String EX_PROP_TEMP_PATHS = "telegram.temp.paths";
    public static final String EX_PROP_PROGRESS_MESSAGE_ID = "telegram.progress.message.id";

    private static final Logger log = Logger.getLogger(TelegramMessageHandler.class.getName());

    private final TikTokDownloader tiktokDownloader;
    private final DefaultMessageHandler defaultMessageHandler;
    private final TelegramVideoSender telegramVideoSender;
    private final StartCommandMessageHandler startCommandMessageHandler;
    private final FileIdCache fileIdCache;

    @Inject
    public TelegramMessageHandler(TikTokDownloader tiktokDownloader,
                                  DefaultMessageHandler defaultMessageHandler,
                                  TelegramVideoSender telegramVideoSender,
                                  StartCommandMessageHandler startCommandMessageHandler,
                                  FileIdCache fileIdCache) {
        this.tiktokDownloader = tiktokDownloader;
        this.defaultMessageHandler = defaultMessageHandler;
        this.telegramVideoSender = telegramVideoSender;
        this.startCommandMessageHandler = startCommandMessageHandler;
        this.fileIdCache = fileIdCache;
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
            var resolved = tiktokDownloader.resolveMedia(incoming);
            var postId = resolved.tiktokPostId();

            // Check cache for both video and gallery before any download
            var cached = fileIdCache.getCachedMedia(postId);
            if (cached.isPresent()) {
                try {
                    sendFromCache(chatId, cached.get());
                    return null;
                } catch (TikTokDownloader.TikTokDownloadException cacheExpired) {
                    fileIdCache.invalidateCachedMedia(postId);
                    // fall through to normal delivery
                }
            }

            // Video: try URL send, fallback to download/upload; always cache result
            if (!resolved.isGallery()) {
                sendVideoWithCaching(chatId, incoming, resolved);
                return null;
            }

            // Gallery: download, send, cache photos + audio as a pair
            var media = tiktokDownloader.downloadMedia(incoming);
            downloadedPaths = collectDownloadedPaths(media);
            rememberDownloadedPaths(exchange, downloadedPaths);

            var photoFileIds = telegramVideoSender.sendMediaGroup(chatId, media.photoPaths());
            var audioFileId = sendAudioAndGetFileId(chatId, media.audioPath());

            if (!photoFileIds.isEmpty()) {
                fileIdCache.cacheMedia(postId, new CachedMedia(true, photoFileIds, audioFileId));
            }
            return null;

        } catch (TikTokDownloader.TikTokDownloadException e) {
            return mapExceptionToUserMessage(e);
        } finally {
            if (exchange == null) {
                cleanupDownloadedPaths(downloadedPaths);
                telegramVideoSender.deleteMessage(chatId, progressMessageId);
            }
        }
    }

    /**
     * Deliver all media from cached Telegram file_ids — zero TikTok or bot traffic.
     */
    private void sendFromCache(String chatId, CachedMedia cached) {

        if (cached.isGallery()) {
            telegramVideoSender.sendMediaGroupByFileIds(chatId, cached.mediaFileIds());
        } else {
            telegramVideoSender.sendVideoByFileId(chatId, cached.mediaFileIds().getFirst());
        }

        if (cached.audioFileId() != null) {
            telegramVideoSender.sendAudioByFileId(chatId, cached.audioFileId());
        }

    }

    /**
     * Try Telegram URL fetch → fallback to download/upload → always cache video + audio pair.
     */
    private void sendVideoWithCaching(String chatId, String tiktokUrl,
                                      TikTokDownloader.ResolvedMedia resolved) {
        var postId = resolved.tiktokPostId();

        try {
            var videoUrl = resolved.videoUrlCandidates().getFirst();

            var response = telegramVideoSender.sendVideoByUrl(chatId, videoUrl,
                    resolved.videoWidth(), resolved.videoHeight());

            // Download and send audio; capture file_id for caching
            var audioFileId = (String) null;
            if (!resolved.audioUrlCandidates().isEmpty()) {
                var audioPath = tiktokDownloader.downloadAudioFromUrls(resolved.audioUrlCandidates());
                try {
                    audioFileId = telegramVideoSender.sendAudio(chatId, audioPath,
                            audioPath.getFileName().toString());
                } finally {
                    cleanupDownloadedPaths(List.of(audioPath));
                }
            }

            if (response.fileId() != null) {
                fileIdCache.cacheMedia(postId,
                        new CachedMedia(false, List.of(response.fileId()), audioFileId));
            }

        } catch (TikTokDownloader.TikTokDownloadException urlSendFailed) {

            var media = tiktokDownloader.downloadMedia(tiktokUrl);
            var downloadedPaths = collectDownloadedPaths(media);
            try {
                var fallbackResponse = sendPrimaryVideo(chatId, media);
                var audioFileId = sendAudioAndGetFileId(chatId, media.audioPath());

                if (fallbackResponse != null && fallbackResponse.fileId() != null) {
                    fileIdCache.cacheMedia(postId,
                            new CachedMedia(false, List.of(fallbackResponse.fileId()), audioFileId));
                }
            } finally {
                cleanupDownloadedPaths(downloadedPaths);
            }
        }
    }

    private TelegramVideoSender.TelegramVideoResponse sendPrimaryVideo(String chatId,
                                                                       TikTokDownloader.DownloadedMedia media) {
        var videoPath = media.videoPath();
        if (videoPath == null) {
            throw new TikTokDownloader.TikTokDownloadException(
                    "Extraction failure: TikTok post has no playable media");
        }
        return telegramVideoSender.sendVideo(chatId, videoPath, videoPath.getFileName().toString(),
                media.videoWidth(), media.videoHeight());
    }

    private String sendAudioAndGetFileId(String chatId, Path audioPath) {
        if (audioPath == null) {
            throw new TikTokDownloader.TikTokDownloadException(
                    "Extraction failure: TikTok post has no audio track");
        }
        return telegramVideoSender.sendAudio(chatId, audioPath, audioPath.getFileName().toString());
    }

    private Object validateTikTokInput(String incoming, String chatId) {
        if (!isValidTikTokUrl(incoming)) {
            return defaultMessageHandler.handle();
        }
        if (chatId == null || chatId.isBlank()) {
            return defaultMessageHandler.handle();
        }
        return null;
    }

    private Object mapExceptionToUserMessage(TikTokDownloader.TikTokDownloadException exception) {
        var message = exception.getMessage();
        if (message == null) {
            return defaultMessageHandler.handleProcessingError();
        }
        if (message.contains("unavailable") || message.contains("statusCode")) {
            return defaultMessageHandler.handleUnavailableVideo();
        }
        if (message.contains("Invalid URL")) {
            return defaultMessageHandler.handleInvalidUrl();
        }
        if (message.contains("Extraction failure")) {
            return defaultMessageHandler.handleUnavailableVideo();
        }
        return defaultMessageHandler.handleProcessingError();
    }

    private Long sendProgressIndicator(String chatId) {
        return telegramVideoSender.sendTextMessage(chatId, "⏳");
    }

    private ArrayList<Path> collectDownloadedPaths(TikTokDownloader.DownloadedMedia media) {
        var paths = new ArrayList<Path>();
        if (media.videoPath() != null) paths.add(media.videoPath());
        paths.addAll(media.photoPaths());
        if (media.audioPath() != null) paths.add(media.audioPath());
        return paths;
    }

    private void rememberDownloadedPaths(Exchange exchange, List<Path> downloadedPaths) {
        if (exchange == null) return;
        exchange.setProperty(EX_PROP_TEMP_PATHS, List.copyOf(downloadedPaths));
    }

    private void rememberProgressMessage(Exchange exchange, Long progressMessageId) {
        if (exchange == null) return;
        exchange.setProperty(EX_PROP_PROGRESS_MESSAGE_ID, progressMessageId);
    }

    private void cleanupDownloadedPaths(List<Path> downloadedPaths) {
        for (var path : downloadedPaths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // best effort cleanup only
            }
        }
    }

    private boolean isStartCommand(String incoming) {
        if (incoming == null) return false;
        var normalized = incoming.trim();
        if (!normalized.startsWith("/start")) return false;
        if (normalized.length() == 6) return true;
        var suffix = normalized.charAt(6);
        return suffix == ' ' || suffix == '@';
    }

    private boolean isValidTikTokUrl(String incoming) {
        if (incoming == null || incoming.isBlank()) return false;
        try {
            var uri = new URI(incoming.trim());
            var scheme = uri.getScheme();
            if (scheme == null) return false;
            var normalizedScheme = scheme.toLowerCase();
            if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) return false;
            var host = uri.getHost();
            if (host == null || host.isBlank()) return false;
            var normalizedHost = host.toLowerCase();
            return normalizedHost.equals("tiktok.com") || normalizedHost.endsWith(".tiktok.com");
        } catch (URISyntaxException ignored) {
            return false;
        }
    }
}
