package io.github.aihio.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.apache.camel.component.telegram.model.User;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class TelegramMessageHandlerTest {

    private final DefaultMessageHandler defaultMessageHandler = new DefaultMessageHandler();
    private final StartCommandMessageHandler startCommandMessageHandler = new StartCommandMessageHandler();

    @Test
    void handlesStartCommandBeforeAnyOtherFlow() {
        var downloader = new StubTikTokDownloader();
        var sender = new StubTelegramVideoSender();
        var handler = new TelegramMessageHandler(downloader, defaultMessageHandler, sender, startCommandMessageHandler);

        var reply = handler.handle(incomingMessage("/start", "Username", "FirstName"), "123");

        assertEquals("Hi, Username! Paste a TikTok link and I will download it for you.", ((org.apache.camel.component.telegram.model.OutgoingTextMessage) reply).getText());
        assertNull(downloader.lastUrl);
        assertEquals(0, sender.sendTextCalls);
    }

    @Test
    void echoesNonTikTokMessages() {
        var handler = new TelegramMessageHandler(new StubTikTokDownloader(), defaultMessageHandler,
                new StubTelegramVideoSender(), startCommandMessageHandler);

        var reply = handler.handle(incomingMessage("hello", "Username", "FirstName"), "123");

        assertEquals("hello", ((org.apache.camel.component.telegram.model.OutgoingTextMessage) reply).getText());
    }

    @Test
    void returnsGuidanceWhenChatIdIsMissingForTikTokUrl() {
        var handler = new TelegramMessageHandler(new StubTikTokDownloader(), defaultMessageHandler,
                new StubTelegramVideoSender(), startCommandMessageHandler);

        var reply = handler.handle(incomingMessage("https://www.tiktok.com/@demo/video/123", "Username", "FirstName"), " ");

        assertEquals("Unable to detect Telegram chat id for this message.",
                ((org.apache.camel.component.telegram.model.OutgoingTextMessage) reply).getText());
    }

    @Test
    void sendsProgressAndVideoAndDeletesProgressMessage() throws IOException {
        var videoPath = Files.createTempFile("telegram-handler-", ".mp4");
        Files.writeString(videoPath, "video-content");

        var downloader = new StubTikTokDownloader();
        downloader.downloadResult = videoPath;
        var sender = new StubTelegramVideoSender();
        sender.progressMessageId = 77L;
        var handler = new TelegramMessageHandler(downloader, defaultMessageHandler, sender, startCommandMessageHandler);

        var result = handler.handle(incomingMessage("https://www.tiktok.com/@demo/video/123", "Username", "FirstName"), "123");

        assertNull(result);
        assertEquals("https://www.tiktok.com/@demo/video/123", downloader.lastUrl);
        assertEquals(1, sender.sendTextCalls);
        assertEquals("⏳", sender.lastProgressText);
        assertEquals("123", sender.lastChatId);
        assertEquals(videoPath.getFileName().toString(), sender.lastFileName);
        assertEquals(videoPath, sender.lastVideoPath);
        assertEquals(1, sender.deleteCalls);
        assertEquals(77L, sender.deletedMessageId);
        assertTrue(Files.notExists(videoPath));
    }

    @Test
    void deletesTempFileAndKeepsProgressMessageWhenVideoSendFails() throws IOException {
        var videoPath = Files.createTempFile("telegram-handler-fail-", ".mp4");
        Files.writeString(videoPath, "video-content");

        var downloader = new StubTikTokDownloader();
        downloader.downloadResult = videoPath;
        var sender = new StubTelegramVideoSender();
        sender.sendVideoFailure = new TikTokDownloader.TikTokDownloadException("video send failed");
        var handler = new TelegramMessageHandler(downloader, defaultMessageHandler, sender, startCommandMessageHandler);

        var exception = assertThrows(TikTokDownloader.TikTokDownloadException.class,
                () -> handler.handle(incomingMessage("https://www.tiktok.com/@demo/video/123", "Username", "FirstName"), "123"));

        assertEquals("video send failed", exception.getMessage());
        assertEquals(0, sender.deleteCalls);
        assertTrue(Files.notExists(videoPath));
    }

    private IncomingMessage incomingMessage(String text, String username, String firstName) {
        var incomingMessage = new IncomingMessage();
        incomingMessage.setText(text);
        var user = new User();
        user.setUsername(username);
        user.setFirstName(firstName);
        incomingMessage.setFrom(user);
        return incomingMessage;
    }

    private static final class StubTikTokDownloader extends TikTokDownloader {
        private String lastUrl;
        private Path downloadResult;

        private StubTikTokDownloader() {
            super(new ObjectMapper(),
                    new TikTokDownloader.Config(Duration.ofSeconds(1), Duration.ofSeconds(1), 0, "JUnit"),
                    new NoOpTransport());
        }

        @Override
        public Path download(String url) {
            lastUrl = url;
            return downloadResult;
        }
    }

    private static final class StubTelegramVideoSender extends TelegramVideoSender {
        private int sendTextCalls;
        private int deleteCalls;
        private Long progressMessageId = 10L;
        private String lastProgressText;
        private String lastChatId;
        private Path lastVideoPath;
        private String lastFileName;
        private Long deletedMessageId;
        private RuntimeException sendVideoFailure;

        private StubTelegramVideoSender() {
            super("token", new ObjectMapper(), HttpClient.newHttpClient());
        }

        @Override
        public Long sendTextMessage(String chatId, String text) {
            sendTextCalls++;
            lastChatId = chatId;
            lastProgressText = text;
            return progressMessageId;
        }

        @Override
        public void deleteMessage(String chatId, Long messageId) {
            deleteCalls++;
            lastChatId = chatId;
            deletedMessageId = messageId;
        }

        @Override
        public void sendVideo(String chatId, Path videoPath, String fileName) {
            if (sendVideoFailure != null) {
                throw sendVideoFailure;
            }
            lastChatId = chatId;
            lastVideoPath = videoPath;
            lastFileName = fileName;
        }
    }

    private static final class NoOpTransport implements TikTokDownloader.Transport {
        @Override
        public TikTokDownloader.Response<String> getText(URI uri, java.util.Map<String, String> headers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Path downloadToTemp(URI uri, java.util.Map<String, String> headers, String suffix) {
            throw new UnsupportedOperationException();
        }
    }
}

