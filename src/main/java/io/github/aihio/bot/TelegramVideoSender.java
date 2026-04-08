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
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class TelegramVideoSender {

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
        var boundary = "----tiktokbot-" + UUID.randomUUID().toString().replace("-", "");
        var uri = URI.create("https://api.telegram.org/bot" + telegramToken + "/sendVideo");
        var bodyPublisher = buildMultipartBody(boundary, chatId, videoPath, fileName);

        var request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(bodyPublisher)
                .build();

        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new TikTokDownloader.TikTokDownloadException(
                        "Telegram sendVideo failed: HTTP " + response.statusCode() + " - " + response.body());
            }

            var json = objectMapper.readTree(response.body());
            if (!json.path("ok").asBoolean(false)) {
                throw new TikTokDownloader.TikTokDownloadException(
                        "Telegram sendVideo rejected request: " + response.body());
            }
        } catch (IOException e) {
            throw new TikTokDownloader.TikTokDownloadException("Telegram sendVideo failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TikTokDownloader.TikTokDownloadException("Telegram sendVideo interrupted", e);
        }
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

    private HttpRequest.BodyPublisher buildMultipartBody(String boundary, String chatId, Path videoPath, String fileName) {
        var separator = "--" + boundary + "\r\n";
        var closing = "--" + boundary + "--\r\n";

        var chatIdPart = separator
                + "Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n"
                + chatId + "\r\n";

        var videoHeader = separator
                + "Content-Disposition: form-data; name=\"video\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";

        try {
            return HttpRequest.BodyPublishers.concat(
                    HttpRequest.BodyPublishers.ofByteArray(chatIdPart.getBytes(StandardCharsets.UTF_8)),
                    HttpRequest.BodyPublishers.ofByteArray(videoHeader.getBytes(StandardCharsets.UTF_8)),
                    HttpRequest.BodyPublishers.ofFile(videoPath),
                    HttpRequest.BodyPublishers.ofByteArray("\r\n".getBytes(StandardCharsets.UTF_8)),
                    HttpRequest.BodyPublishers.ofByteArray(closing.getBytes(StandardCharsets.UTF_8))
            );
        } catch (IOException e) {
            throw new TikTokDownloader.TikTokDownloadException("Telegram sendVideo failed: cannot read downloaded file", e);
        }
    }
}
