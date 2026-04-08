package io.github.aihio.bot.tiktok;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

record TikTokHttpTransport(TikTokDownloader.Config config, HttpClient client) implements TikTokDownloader.Transport {

    @Override
    public TikTokDownloader.Response<String> getText(URI uri, Map<String, String> headers) {
        return send(uri, headers, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), "fetch TikTok page");
    }

    @Override
    public Path downloadToTemp(URI uri, Map<String, String> headers, String suffix) {
        var response = send(uri, headers, HttpResponse.BodyHandlers.ofInputStream(), "download TikTok video");
        Path tempFile = null;
        try (InputStream inputStream = response.body()) {
            tempFile = Files.createTempFile("tiktok-", suffix);
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            return tempFile;
        } catch (IOException e) {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // ignore cleanup failure
                }
            }
            throw new TikTokDownloader.TikTokDownloadException("Download failure: unable to stream video to disk", e);
        }
    }

    private <T> TikTokDownloader.Response<T> send(URI uri,
                                                  Map<String, String> headers,
                                                  HttpResponse.BodyHandler<T> bodyHandler,
                                                  String action) {
        var requestBuilder = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(config.readTimeout());
        headers.forEach(requestBuilder::header);

        try {
            var response = client.send(requestBuilder.build(), bodyHandler);
            var statusCode = response.statusCode();
            if (statusCode >= 200 && statusCode < 300) {
                return new TikTokDownloader.Response<>(response.uri(), statusCode, response.body());
            }
            throw new TikTokDownloader.TikTokDownloadException(statusMessage(action, statusCode));
        } catch (IOException e) {
            throw new TikTokDownloader.TikTokDownloadException(action + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TikTokDownloader.TikTokDownloadException(action + " interrupted", e);
        }
    }

    private String statusMessage(String action, int statusCode) {
        if (statusCode == 403 || statusCode == 404) {
            if ("download TikTok video".equals(action)) {
                return "download TikTok video failed: extracted video URL has expired or is blocked (HTTP " + statusCode + "). This can happen if TikTok's CDN rejected the request. Try again immediately.";
            }
            return action + " failed: TikTok video is private, unavailable, or the direct media URL expired (HTTP " + statusCode + ")";
        }
        return action + " failed: HTTP " + statusCode;
    }
}


