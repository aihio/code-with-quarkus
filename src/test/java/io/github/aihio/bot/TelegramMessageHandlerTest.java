package io.github.aihio.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aihio.bot.tiktok.TikTokDownloader;
import org.apache.camel.component.telegram.TelegramConstants;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.apache.camel.component.telegram.model.User;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
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

        var reply = handler.handle(incomingMessage("/start"), "123");

        assertEquals("Hi, Username! Paste a TikTok link and I will download it for you.", ((org.apache.camel.component.telegram.model.OutgoingTextMessage) reply).getText());
        assertNull(downloader.lastUrl);
        assertEquals(0, sender.sendTextCalls);
    }

    @Test
    void returnsGuidanceForNonTikTokMessages() {
        var handler = new TelegramMessageHandler(new StubTikTokDownloader(), defaultMessageHandler,
                new StubTelegramVideoSender(), startCommandMessageHandler);

        var reply = handler.handle(incomingMessage("hello"), "123");

        assertEquals("Please send a TikTok URL.", ((org.apache.camel.component.telegram.model.OutgoingTextMessage) reply).getText());
    }

    @Test
    void returnsGuidanceWhenChatIdIsMissingForTikTokUrl() {
        var handler = new TelegramMessageHandler(new StubTikTokDownloader(), defaultMessageHandler,
                new StubTelegramVideoSender(), startCommandMessageHandler);

        var reply = handler.handle(incomingMessage("https://www.tiktok.com/@demo/video/123"), " ");

        assertEquals("Please send a TikTok URL.",
                ((org.apache.camel.component.telegram.model.OutgoingTextMessage) reply).getText());
    }

    @Test
    void sendsProgressVideoAndAudioAndDeletesProgressMessage() throws IOException {
        var videoPath = Files.createTempFile("telegram-handler-", ".mp4");
        Files.writeString(videoPath, "video-content");
        var audioPath = Files.createTempFile("telegram-handler-", ".mp3");
        Files.writeString(audioPath, "audio-content");

        var downloader = new StubTikTokDownloader();
        downloader.downloadResult = new TikTokDownloader.DownloadedMedia(videoPath, java.util.List.of(), audioPath);
        var sender = new StubTelegramVideoSender();
        sender.progressMessageId = 77L;
        var handler = new TelegramMessageHandler(downloader, defaultMessageHandler, sender, startCommandMessageHandler);

        var result = handler.handle(incomingMessage("https://www.tiktok.com/@demo/video/123"), "123");

        assertNull(result);
        assertEquals("https://www.tiktok.com/@demo/video/123", downloader.lastUrl);
        assertEquals(1, sender.sendTextCalls);
        assertEquals("⏳", sender.lastProgressText);
        assertEquals("123", sender.lastChatId);
        assertEquals(videoPath.getFileName().toString(), sender.lastFileName);
        assertEquals(videoPath, sender.lastVideoPath);
        assertEquals(audioPath, sender.lastAudioPath);
        assertEquals(audioPath.getFileName().toString(), sender.lastAudioFileName);
        assertEquals(1, sender.sendAudioCalls);
        assertEquals(1, sender.deleteCalls);
        assertEquals(77L, sender.deletedMessageId);
        assertTrue(Files.notExists(videoPath));
        assertTrue(Files.notExists(audioPath));
    }

    @Test
    void sendsGalleryAsMediaGroupAndAudioAndDeletesProgressMessage() throws IOException {
        var photoOne = Files.createTempFile("telegram-handler-gallery-", ".jpg");
        var photoTwo = Files.createTempFile("telegram-handler-gallery-", ".jpg");
        var audioPath = Files.createTempFile("telegram-handler-gallery-", ".mp3");
        Files.writeString(photoOne, "photo-1");
        Files.writeString(photoTwo, "photo-2");
        Files.writeString(audioPath, "audio-content");

        var downloader = new StubTikTokDownloader();
        downloader.downloadResult = new TikTokDownloader.DownloadedMedia(null, java.util.List.of(photoOne, photoTwo), audioPath);
        var sender = new StubTelegramVideoSender();
        var handler = new TelegramMessageHandler(downloader, defaultMessageHandler, sender, startCommandMessageHandler);

        var result = handler.handle(incomingMessage("https://www.tiktok.com/@demo/video/123"), "123");

        assertNull(result);
        assertEquals(1, sender.sendMediaGroupCalls);
        assertEquals(java.util.List.of(photoOne, photoTwo), sender.lastMediaGroupPaths);
        assertEquals(0, sender.sendVideoCalls);
        assertEquals(1, sender.sendAudioCalls);
        assertEquals(audioPath, sender.lastAudioPath);
        assertEquals(1, sender.deleteCalls);
        assertTrue(Files.notExists(photoOne));
        assertTrue(Files.notExists(photoTwo));
        assertTrue(Files.notExists(audioPath));
    }

    @Test
    void returnsFallbackMessageAndCleansUpWhenAudioSendFails() throws IOException {
        var videoPath = Files.createTempFile("telegram-handler-fail-", ".mp4");
        Files.writeString(videoPath, "video-content");
        var audioPath = Files.createTempFile("telegram-handler-fail-", ".mp3");
        Files.writeString(audioPath, "audio-content");

        var downloader = new StubTikTokDownloader();
        downloader.downloadResult = new TikTokDownloader.DownloadedMedia(videoPath, java.util.List.of(), audioPath);
        var sender = new StubTelegramVideoSender();
        sender.sendAudioFailure = new TikTokDownloader.TikTokDownloadException("audio send failed");
        var handler = new TelegramMessageHandler(downloader, defaultMessageHandler, sender, startCommandMessageHandler);

        var reply = handler.handle(incomingMessage("https://www.tiktok.com/@demo/video/123"), "123");

        assertEquals("Failed to process this TikTok link. Please try another link.",
                ((org.apache.camel.component.telegram.model.OutgoingTextMessage) reply).getText());
        assertEquals(1, sender.deleteCalls);
        assertTrue(Files.notExists(videoPath));
        assertTrue(Files.notExists(audioPath));
    }

    @Test
    void returnsErrorMessageWhenSendingMediaGroupFails() throws IOException {
        var photoOne = Files.createTempFile("telegram-handler-route-fail-", ".jpg");
        var photoTwo = Files.createTempFile("telegram-handler-route-fail-", ".jpg");
        var audioPath = Files.createTempFile("telegram-handler-route-fail-", ".mp3");
        Files.writeString(photoOne, "photo-1");
        Files.writeString(photoTwo, "photo-2");
        Files.writeString(audioPath, "audio-content");

        var downloader = new StubTikTokDownloader();
        downloader.downloadResult = new TikTokDownloader.DownloadedMedia(null, java.util.List.of(photoOne, photoTwo), audioPath);
        var sender = new StubTelegramVideoSender();
        sender.sendMediaGroupFailure = new TikTokDownloader.TikTokDownloadException("media group send failed");
        var handler = new TelegramMessageHandler(downloader, defaultMessageHandler, sender, startCommandMessageHandler);
        var processor = new MessageProcessor(handler, defaultMessageHandler, sender);

        var exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getIn().setHeader(TelegramConstants.TELEGRAM_CHAT_ID, "123");

        var reply = handler.handle(incomingMessage("https://www.tiktok.com/@demo/video/123"), "123", exchange);

        // Files should still exist because they are managed by the route
        assertTrue(Files.exists(photoOne));
        assertTrue(Files.exists(photoTwo));
        assertTrue(Files.exists(audioPath));

        // The processor should clean them up
        processor.cleanupExchangeArtifacts(exchange);

        assertTrue(Files.notExists(photoOne));
        assertTrue(Files.notExists(photoTwo));
        assertTrue(Files.notExists(audioPath));
        assertEquals(1, sender.deleteCalls);

        // Verify error message was returned
        assertEquals("Failed to process this TikTok link. Please try another link.",
                ((org.apache.camel.component.telegram.model.OutgoingTextMessage) reply).getText());
    }

    @Test
    void returnsUnavailableMessageWhenVideoUnavailable() {
        var downloader = new StubTikTokDownloader();
        downloader.downloadFailure = new TikTokDownloader.TikTokDownloadException("TikTok video is unavailable (statusCode=100002)");
        var sender = new StubTelegramVideoSender();
        var handler = new TelegramMessageHandler(downloader, defaultMessageHandler, sender, startCommandMessageHandler);

        var reply = handler.handle(incomingMessage("https://www.tiktok.com/@demo/video/123"), "123");

        assertEquals("The TikTok video is unavailable or has been deleted. Please try another link.",
                ((org.apache.camel.component.telegram.model.OutgoingTextMessage) reply).getText());
        assertEquals(0, sender.sendVideoCalls);
        assertEquals(1, sender.sendTextCalls);
    }

    @Test
    void returnsUnavailableMessageWhenExtractionFails() {
        var downloader = new StubTikTokDownloader();
        downloader.downloadFailure = new TikTokDownloader.TikTokDownloadException("Extraction failure: unable to find a playable TikTok video URL");
        var sender = new StubTelegramVideoSender();
        var handler = new TelegramMessageHandler(downloader, defaultMessageHandler, sender, startCommandMessageHandler);

        var reply = handler.handle(incomingMessage("https://www.tiktok.com/@demo/video/123"), "123");

        assertEquals("The TikTok video is unavailable or has been deleted. Please try another link.",
                ((org.apache.camel.component.telegram.model.OutgoingTextMessage) reply).getText());
    }

    @Test
    void returnsInvalidUrlMessageWhenInvalidUrlErrorThrown() {
        var downloader = new StubTikTokDownloader();
        downloader.downloadFailure = new TikTokDownloader.TikTokDownloadException("Invalid URL: expected a tiktok.com URL");
        var sender = new StubTelegramVideoSender();
        var handler = new TelegramMessageHandler(downloader, defaultMessageHandler, sender, startCommandMessageHandler);

        var reply = handler.handle(incomingMessage("https://www.tiktok.com/@demo/video/invalid"), "123");

        assertEquals("The link you provided is invalid. Please share a valid TikTok URL.",
                ((org.apache.camel.component.telegram.model.OutgoingTextMessage) reply).getText());
    }

    @Test
    void returnsProcessingErrorMessageWhenGenericErrorOccurs() {
        var downloader = new StubTikTokDownloader();
        downloader.downloadFailure = new TikTokDownloader.TikTokDownloadException("Some unexpected error occurred");
        var sender = new StubTelegramVideoSender();
        var handler = new TelegramMessageHandler(downloader, defaultMessageHandler, sender, startCommandMessageHandler);

        var reply = handler.handle(incomingMessage("https://www.tiktok.com/@demo/video/123"), "123");

        assertEquals("Failed to process this TikTok link. Please try another link.",
                ((org.apache.camel.component.telegram.model.OutgoingTextMessage) reply).getText());
    }

    private IncomingMessage incomingMessage(String text) {
        var incomingMessage = new IncomingMessage();
        incomingMessage.setText(text);
        var user = new User();
        user.setUsername("Username");
        user.setFirstName("FirstName");
        incomingMessage.setFrom(user);
        return incomingMessage;
    }

    private static final class StubTikTokDownloader extends TikTokDownloader {
        private String lastUrl;
        private DownloadedMedia downloadResult;
        private RuntimeException downloadFailure;

        private StubTikTokDownloader() {
            super(new ObjectMapper(),
                    new TikTokDownloader.Config(Duration.ofSeconds(1), Duration.ofSeconds(1), 0, "JUnit"),
                    new NoOpTransport());
        }

        @Override
        public DownloadedMedia downloadMedia(String url) {
            lastUrl = url;
            if (downloadFailure != null) {
                throw downloadFailure;
            }
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
        private Path lastAudioPath;
        private String lastAudioFileName;
        private java.util.List<Path> lastMediaGroupPaths;
        private Long deletedMessageId;
        private RuntimeException sendAudioFailure;
        private RuntimeException sendMediaGroupFailure;
        private int sendVideoCalls;
        private int sendAudioCalls;
        private int sendMediaGroupCalls;

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
            sendVideoCalls++;
            lastChatId = chatId;
            lastVideoPath = videoPath;
            lastFileName = fileName;
        }

        @Override
        public void sendAudio(String chatId, Path audioPath, String fileName) {
            sendAudioCalls++;
            if (sendAudioFailure != null) {
                throw sendAudioFailure;
            }
            lastChatId = chatId;
            lastAudioPath = audioPath;
            lastAudioFileName = fileName;
        }

        @Override
        public void sendMediaGroup(String chatId, java.util.List<Path> photoPaths) {
            sendMediaGroupCalls++;
            if (sendMediaGroupFailure != null) {
                throw sendMediaGroupFailure;
            }
            lastChatId = chatId;
            lastMediaGroupPaths = java.util.List.copyOf(photoPaths);
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

