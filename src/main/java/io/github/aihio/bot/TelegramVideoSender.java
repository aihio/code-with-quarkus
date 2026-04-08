package io.github.aihio.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aihio.bot.tiktok.TikTokDownloader;
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

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI telegramApiBaseUri;

    @Inject
    public TelegramVideoSender(@ConfigProperty(name = "camel.component.telegram.authorization-token") String telegramToken,
                               ObjectMapper objectMapper,
                               HttpClient httpClient) {
        this(objectMapper, httpClient, URI.create("https://api.telegram.org/bot" + telegramToken + "/"));
    }

    TelegramVideoSender(ObjectMapper objectMapper,
                        HttpClient httpClient,
                        URI telegramApiBaseUri) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.telegramApiBaseUri = telegramApiBaseUri;
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
        uploadMultipart("sendVideo", List.of(
                new FieldPart("chat_id", chatId),
                new FieldPart("supports_streaming", "true"),
                new FilePart("video", fileName, "video/mp4", videoPath)
        ));
    }

    public void sendAudio(String chatId, Path audioPath, String fileName) {
        uploadMultipart("sendAudio", List.of(
                new FieldPart("chat_id", chatId),
                new FilePart("audio", fileName, "audio/mpeg", audioPath)
        ));
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
        var media = new ArrayList<Map<String, String>>();
        var parts = new ArrayList<MultipartPart>();
        parts.add(new FieldPart("chat_id", chatId));

        for (var index = 0; index < photoPaths.size(); index++) {
            var photoPath = photoPaths.get(index);
            var attachName = "photo" + index;
            media.add(Map.of("type", "photo", "media", "attach://" + attachName));
            parts.add(new FilePart(attachName, photoPath.getFileName().toString(), contentTypeFor(photoPath), photoPath));
        }

        try {
            parts.add(1, new FieldPart("media", objectMapper.writeValueAsString(media)));
            uploadMultipart("sendMediaGroup", parts);
        } catch (IOException e) {
            throw new TikTokDownloader.TikTokDownloadException("Telegram sendMediaGroup failed: " + e.getMessage(), e);
        }
    }

    private void sendPhoto(String chatId, Path photoPath, String fileName) {
        uploadMultipart("sendPhoto", List.of(
                new FieldPart("chat_id", chatId),
                new FilePart("photo", fileName, contentTypeFor(photoPath), photoPath)
        ));
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
            var requestBody = objectMapper.writeValueAsString(payload);
            var request = HttpRequest.newBuilder(methodUri(method))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            return sendTelegramRequest(method, request);
        } catch (IOException e) {
            throw new TikTokDownloader.TikTokDownloadException("Telegram " + method + " failed: " + e.getMessage(), e);
        }
    }

    private void uploadMultipart(String method, List<MultipartPart> requestParts) {
        var boundary = newBoundary();
        var request = HttpRequest.newBuilder(methodUri(method))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(multipartBody(boundary, requestParts))
                .build();

        sendTelegramRequest(method, request);
    }

    private com.fasterxml.jackson.databind.JsonNode sendTelegramRequest(String method, HttpRequest request) {

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
            return json;
        } catch (IOException e) {
            throw new TikTokDownloader.TikTokDownloadException("Telegram " + method + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TikTokDownloader.TikTokDownloadException("Telegram " + method + " interrupted", e);
        }
    }

    private HttpRequest.BodyPublisher multipartBody(String boundary, List<MultipartPart> requestParts) {
        var publishers = new ArrayList<HttpRequest.BodyPublisher>();
        for (var part : requestParts) {
            if (part instanceof FieldPart fieldPart) {
                publishers.add(HttpRequest.BodyPublishers.ofString(fieldPart.header(boundary), StandardCharsets.UTF_8));
                continue;
            }

            if (part instanceof FilePart filePart) {
                publishers.add(HttpRequest.BodyPublishers.ofString(filePart.header(boundary), StandardCharsets.UTF_8));
                try {
                    publishers.add(HttpRequest.BodyPublishers.ofFile(filePart.path()));
                } catch (IOException e) {
                    throw new TikTokDownloader.TikTokDownloadException("Telegram upload failed: cannot read downloaded file", e);
                }
                publishers.add(HttpRequest.BodyPublishers.ofString("\r\n", StandardCharsets.UTF_8));
            }
        }

        publishers.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "--\r\n", StandardCharsets.UTF_8));
        return HttpRequest.BodyPublishers.concat(publishers.toArray(HttpRequest.BodyPublisher[]::new));
    }

    private String newBoundary() {
        return "----tiktokbot-" + UUID.randomUUID().toString().replace("-", "");
    }

    private URI methodUri(String method) {
        return telegramApiBaseUri.resolve(method);
    }

    private sealed interface MultipartPart permits FieldPart, FilePart {
    }

    private record FieldPart(String name, String value) implements MultipartPart {
        private String header(String boundary) {
            return "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                    + value + "\r\n";
        }
    }

    private record FilePart(String name, String fileName, String contentType, Path path) implements MultipartPart {
        private String header(String boundary) {
            return "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n"
                    + "Content-Type: " + contentType + "\r\n\r\n";
        }
    }
}
