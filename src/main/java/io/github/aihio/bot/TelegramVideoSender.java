package io.github.aihio.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class TelegramVideoSender {

    private static final int TELEGRAM_MEDIA_GROUP_MAX = 10;

    private final String telegramToken;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Inject
    public TelegramVideoSender(@ConfigProperty(name = "camel.component.telegram.authorization-token") String telegramToken,
                               ObjectMapper objectMapper,
                               HttpClient httpClient) {
        this.telegramToken = telegramToken;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public Long sendTextMessage(String chatId, String text) {
        var payload = Map.of("chat_id", chatId, "text", text);
        var json = postJson("sendMessage", payload);
        if (!json.path("ok").asBoolean(false)) {
            throw new TikTokDownloader.TikTokDownloadException(
                    "Telegram sendMessage rejected request: " + json);
        }
        var messageId = json.path("result").path("message_id");
        return messageId.isIntegralNumber() ? messageId.asLong() : null;
    }

    public void deleteMessage(String chatId, Long messageId) {
        if (messageId == null) {
            return;
        }
        try {
            var payload = Map.of("chat_id", chatId, "message_id", messageId);
            postJson("deleteMessage", payload);
        } catch (RuntimeException ignored) {
            // best effort cleanup only
        }
    }

    public void sendVideo(String chatId, Path videoPath, String fileName) {
        var boundary = newBoundary();
        var uri = URI.create("https://api.telegram.org/bot" + telegramToken + "/sendVideo");
        var parts = new ArrayList<byte[]>();
        parts.add(formField(boundary, "chat_id", chatId));
        parts.add(formField(boundary, "supports_streaming", "true"));

        sendMultipartRequest("sendVideo", uri, boundary,
                multipartBody(boundary, parts, List.of(filePart(boundary, "video", fileName, "video/mp4", videoPath))));
    }

    public void sendAudio(String chatId, Path audioPath, String fileName) {
        var boundary = newBoundary();
        var uri = URI.create("https://api.telegram.org/bot" + telegramToken + "/sendAudio");
        var parts = List.of(formField(boundary, "chat_id", chatId));
        sendMultipartRequest("sendAudio", uri, boundary,
                multipartBody(boundary, parts, List.of(filePart(boundary, "audio", fileName, "audio/mpeg", audioPath))));
    }

    public void sendMediaGroup(String chatId, List<Path> photoPaths) {
        if (photoPaths == null || photoPaths.isEmpty()) {
            return;
        }

        if (photoPaths.size() == 1) {
            var photoPath = photoPaths.getFirst();
            sendPhoto(chatId, photoPath, photoPath.getFileName().toString());
            return;
        }

        for (var start = 0; start < photoPaths.size(); start += TELEGRAM_MEDIA_GROUP_MAX) {
            var endExclusive = Math.min(start + TELEGRAM_MEDIA_GROUP_MAX, photoPaths.size());
            sendMediaGroupChunk(chatId, photoPaths.subList(start, endExclusive));
        }
    }

    private void sendMediaGroupChunk(String chatId, List<Path> photoPaths) {
        var boundary = newBoundary();
        var uri = URI.create("https://api.telegram.org/bot" + telegramToken + "/sendMediaGroup");
        var media = new ArrayList<Map<String, String>>();
        var files = new ArrayList<FilePart>();

        for (var index = 0; index < photoPaths.size(); index++) {
            var photoPath = photoPaths.get(index);
            var attachName = "photo" + index;
            media.add(Map.of("type", "photo", "media", "attach://" + attachName));
            files.add(filePart(boundary, attachName, photoPath.getFileName().toString(), contentTypeFor(photoPath), photoPath));
        }

        try {
            var mediaJson = objectMapper.writeValueAsString(media);
            var parts = List.of(
                    formField(boundary, "chat_id", chatId),
                    formField(boundary, "media", mediaJson)
            );
            sendMultipartRequest("sendMediaGroup", uri, boundary, multipartBody(boundary, parts, files));
        } catch (IOException e) {
            throw new TikTokDownloader.TikTokDownloadException("Telegram sendMediaGroup failed: " + e.getMessage(), e);
        }
    }

    private void sendPhoto(String chatId, Path photoPath, String fileName) {
        var boundary = newBoundary();
        var uri = URI.create("https://api.telegram.org/bot" + telegramToken + "/sendPhoto");
        var parts = List.of(formField(boundary, "chat_id", chatId));
        sendMultipartRequest("sendPhoto", uri, boundary,
                multipartBody(boundary, parts, List.of(filePart(boundary, "photo", fileName, contentTypeFor(photoPath), photoPath))));
    }

    private String contentTypeFor(Path path) {
        var fileName = path.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".png")) {
            return "image/png";
        }
        if (fileName.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private com.fasterxml.jackson.databind.JsonNode postJson(String method, Map<String, ?> payload) {
        try {
            var uri = URI.create("https://api.telegram.org/bot" + telegramToken + "/" + method);
            var requestBody = objectMapper.writeValueAsString(payload);
            var request = HttpRequest.newBuilder(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new TikTokDownloader.TikTokDownloadException(
                        "Telegram " + method + " failed: HTTP " + response.statusCode() + " - " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new TikTokDownloader.TikTokDownloadException("Telegram " + method + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TikTokDownloader.TikTokDownloadException("Telegram " + method + " interrupted", e);
        }
    }

    private void sendMultipartRequest(String method, URI uri, String boundary, HttpRequest.BodyPublisher bodyPublisher) {
        var request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(bodyPublisher)
                .build();

        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new TikTokDownloader.TikTokDownloadException(
                        "Telegram " + method + " failed: HTTP " + response.statusCode() + " - " + response.body());
            }

            var json = objectMapper.readTree(response.body());
            if (!json.path("ok").asBoolean(false)) {
                throw new TikTokDownloader.TikTokDownloadException(
                        "Telegram " + method + " rejected request: " + response.body());
            }
        } catch (IOException e) {
            throw new TikTokDownloader.TikTokDownloadException("Telegram " + method + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TikTokDownloader.TikTokDownloadException("Telegram " + method + " interrupted", e);
        }
    }

    private HttpRequest.BodyPublisher multipartBody(String boundary, List<byte[]> fields, List<FilePart> files) {
        var parts = new ArrayList<HttpRequest.BodyPublisher>();
        for (var field : fields) {
            parts.add(HttpRequest.BodyPublishers.ofByteArray(field));
        }
        for (var file : files) {
            parts.add(HttpRequest.BodyPublishers.ofByteArray(file.header().getBytes(StandardCharsets.UTF_8)));
            try {
                parts.add(HttpRequest.BodyPublishers.ofFile(file.path()));
            } catch (IOException e) {
                throw new TikTokDownloader.TikTokDownloadException("Telegram upload failed: cannot read downloaded file", e);
            }
            parts.add(HttpRequest.BodyPublishers.ofByteArray("\r\n".getBytes(StandardCharsets.UTF_8)));
        }

        var closing = "--" + boundary + "--\r\n";
        parts.add(HttpRequest.BodyPublishers.ofByteArray(closing.getBytes(StandardCharsets.UTF_8)));
        return HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new));
    }

    private byte[] formField(String boundary, String name, String value) {
        var separator = "--" + boundary + "\r\n";
        var field = separator
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
        return field.getBytes(StandardCharsets.UTF_8);
    }

    private FilePart filePart(String boundary, String fieldName, String fileName, String contentType, Path path) {
        var separator = "--" + boundary + "\r\n";
        var header = separator
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        return new FilePart(header, path);
    }

    private String newBoundary() {
        return "----tiktokbot-" + UUID.randomUUID().toString().replace("-", "");
    }

    private record FilePart(String header, Path path) {
    }
}
