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

    public TelegramVideoResponse sendVideoByUrl(String chatId, String videoUrl, int width, int height) {
        var payloadBuilder = new java.util.HashMap<String, Object>();
        payloadBuilder.put("chat_id", chatId);
        payloadBuilder.put("video", videoUrl);
        payloadBuilder.put("supports_streaming", true);
        if (width > 0 && height > 0) {
            payloadBuilder.put("width", width);
            payloadBuilder.put("height", height);
        }

        var json = postJson("sendVideo", payloadBuilder);
        var result = json.path("result");
        var messageId = result.path("message_id");
        var video = result.path("video");
        var fileId = video.path("file_id");

        return new TelegramVideoResponse(
                messageId.isIntegralNumber() ? messageId.asLong() : null,
                fileId.isTextual() ? fileId.asText() : null
        );
    }

    public TelegramVideoResponse sendVideoByFileId(String chatId, String fileId) {
        var payload = Map.of("chat_id", chatId, "video", fileId);

        var json = postJson("sendVideo", payload);
        var result = json.path("result");
        var messageId = result.path("message_id");
        var videoNode = result.path("video");
        var returnedFileId = videoNode.path("file_id");

        return new TelegramVideoResponse(
                messageId.isIntegralNumber() ? messageId.asLong() : null,
                returnedFileId.isTextual() ? returnedFileId.asText() : fileId
        );
    }

    public TelegramVideoResponse sendVideo(String chatId, Path videoPath, String fileName, int width, int height) {
        var parts = new ArrayList<MultipartPart>();
        parts.add(new FieldPart("chat_id", chatId));
        parts.add(new FieldPart("supports_streaming", "true"));
        if (width > 0 && height > 0) {
            parts.add(new FieldPart("width", String.valueOf(width)));
            parts.add(new FieldPart("height", String.valueOf(height)));
        }
        parts.add(new FilePart("video", fileName, "video/mp4", videoPath));

        var json = uploadMultipart("sendVideo", parts);
        var result = json.path("result");
        var messageId = result.path("message_id");
        var fileId = result.path("video").path("file_id");

        return new TelegramVideoResponse(
                messageId.isIntegralNumber() ? messageId.asLong() : null,
                fileId.isTextual() ? fileId.asText() : null
        );
    }

    public String sendAudio(String chatId, Path audioPath, String fileName) {
        var json = uploadMultipart("sendAudio", List.of(
                new FieldPart("chat_id", chatId),
                new FilePart("audio", fileName, "audio/mpeg", audioPath)
        ));
        var fileId = json.path("result").path("audio").path("file_id");
        return fileId.isTextual() ? fileId.asText() : null;
    }

    public void sendAudioByFileId(String chatId, String fileId) {
        postJson("sendAudio", Map.of("chat_id", chatId, "audio", fileId));
    }

    public List<String> sendMediaGroup(String chatId, List<Path> photoPaths) {
        if (photoPaths == null || photoPaths.isEmpty()) {
            return List.of();
        }

        if (photoPaths.size() == 1) {
            var photoPath = photoPaths.getFirst();
            var fileId = sendPhoto(chatId, photoPath, photoPath.getFileName().toString());
            return fileId != null ? List.of(fileId) : List.of();
        }

        var allFileIds = new ArrayList<String>();
        for (var start = 0; start < photoPaths.size(); start += TELEGRAM_MEDIA_GROUP_MAX) {
            var endExclusive = Math.min(start + TELEGRAM_MEDIA_GROUP_MAX, photoPaths.size());
            allFileIds.addAll(sendMediaGroupChunk(chatId, photoPaths.subList(start, endExclusive)));
        }
        return allFileIds;
    }

    public void sendMediaGroupByFileIds(String chatId, List<String> fileIds) {
        var media = fileIds.stream()
                .map(fid -> Map.of("type", "photo", "media", fid))
                .toList();
        var payload = new java.util.HashMap<String, Object>();
        payload.put("chat_id", chatId);
        payload.put("media", media);
        postJson("sendMediaGroup", payload);
    }

    private List<String> sendMediaGroupChunk(String chatId, List<Path> photoPaths) {
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
            var json = uploadMultipart("sendMediaGroup", parts);
            // result is an array of messages; extract highest-quality file_id from each photo
            var result = json.path("result");
            var fileIds = new ArrayList<String>();
            if (result.isArray()) {
                for (var message : result) {
                    var photoArray = message.path("photo");
                    if (photoArray.isArray() && !photoArray.isEmpty()) {
                        var fileId = photoArray.get(photoArray.size() - 1).path("file_id");
                        if (fileId.isTextual()) {
                            fileIds.add(fileId.asText());
                        }
                    }
                }
            }
            return fileIds;
        } catch (IOException e) {
            throw new TikTokDownloader.TikTokDownloadException("Telegram sendMediaGroup failed: " + e.getMessage(), e);
        }
    }

    private String sendPhoto(String chatId, Path photoPath, String fileName) {
        var json = uploadMultipart("sendPhoto", List.of(
                new FieldPart("chat_id", chatId),
                new FilePart("photo", fileName, contentTypeFor(photoPath), photoPath)
        ));
        var photoArray = json.path("result").path("photo");
        if (photoArray.isArray() && !photoArray.isEmpty()) {
            var fileId = photoArray.get(photoArray.size() - 1).path("file_id");
            return fileId.isTextual() ? fileId.asText() : null;
        }
        return null;
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

    private com.fasterxml.jackson.databind.JsonNode uploadMultipart(String method, List<MultipartPart> requestParts) {
        var boundary = newBoundary();
        var request = HttpRequest.newBuilder(methodUri(method))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(multipartBody(boundary, requestParts))
                .build();

        return sendTelegramRequest(method, request);
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

    public record TelegramVideoResponse(
            Long messageId,
            String fileId
    ) {
    }
}
