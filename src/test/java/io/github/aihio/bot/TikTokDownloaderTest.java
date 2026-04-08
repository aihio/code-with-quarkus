package io.github.aihio.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TikTokDownloaderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Path> createdFiles = new ArrayList<>();

    @AfterEach
    void cleanupTempFiles() throws IOException {
        for (Path path : createdFiles) {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void downloadsVideoFromUniversalDataAfterResolvingShortLink() throws IOException {
        var transport = new StubTransport();
        transport.enqueueText(
                "https://vm.tiktok.com/ZNRm4DYpB/",
                URI.create("https://www.tiktok.com/@realesst.mff/video/7595518984808647958"),
                htmlWithUniversalData("""
                        {
                          "__DEFAULT_SCOPE__": {
                            "webapp.video-detail": {
                              "statusCode": 0,
                              "itemInfo": {
                                "itemStruct": {
                                  "id": "7595518984808647958",
                                  "video": {
                                    "width": 720,
                                    "height": 1280,
                                    "bitrateInfo": [
                                      {
                                        "Bitrate": 300000,
                                        "PlayAddr": {
                                          "UrlList": ["https://cdn.example.com/video-low.mp4"],
                                          "Width": 540,
                                          "Height": 960
                                        }
                                      },
                                      {
                                        "Bitrate": 900000,
                                        "PlayAddr": {
                                          "UrlList": ["https://cdn.example.com/video-high.mp4"],
                                          "Width": 720,
                                          "Height": 1280
                                        }
                                      }
                                    ],
                                    "downloadAddr": "https://cdn.example.com/video-watermarked.mp4",
                                    "hasWatermark": true
                                  }
                                }
                              }
                            }
                          }
                        }
                        """));
        transport.enqueueDownload("https://cdn.example.com/video-high.mp4", "high-quality-video");

        var downloader = downloader(transport, 2);
        var downloaded = downloader.download("https://vm.tiktok.com/ZNRm4DYpB/");
        createdFiles.add(downloaded);

        assertTrue(Files.exists(downloaded));
        assertEquals("high-quality-video", Files.readString(downloaded));
        assertEquals(URI.create("https://cdn.example.com/video-high.mp4"), transport.lastDownloadedUri());
    }

    @Test
    void prefersNonWatermarkedPlayAddressOverWatermarkedDownloadAddress() throws IOException {
        var transport = new StubTransport();
        transport.enqueueText(
                "https://www.tiktok.com/@funny.usa003/video/7618184914181147934",
                URI.create("https://www.tiktok.com/@funny.usa003/video/7618184914181147934"),
                htmlWithSigiState("""
                        {
                          "VideoPage": { "statusCode": 0 },
                          "ItemModule": {
                            "7618184914181147934": {
                              "id": "7618184914181147934",
                              "video": {
                                "width": 720,
                                "height": 1280,
                                "hasWatermark": true,
                                "downloadAddr": "https://cdn.example.com/watermarked.mp4",
                                "playAddr": "https://cdn.example.com/no-watermark.mp4"
                              }
                            }
                          }
                        }
                        """));
        transport.enqueueDownload("https://cdn.example.com/no-watermark.mp4", "clean-video");

        var downloader = downloader(transport, 2);
        var downloaded = downloader.download("https://www.tiktok.com/@funny.usa003/video/7618184914181147934");
        createdFiles.add(downloaded);

        assertEquals("clean-video", Files.readString(downloaded));
        assertEquals(URI.create("https://cdn.example.com/no-watermark.mp4"), transport.lastDownloadedUri());
    }

    @Test
    void fallsBackToNextDataWhenOtherEmbeddedJsonBlobsAreMissing() throws IOException {
        var transport = new StubTransport();
        transport.enqueueText(
                "https://www.tiktok.com/@demo/video/1234567890123456789",
                URI.create("https://www.tiktok.com/@demo/video/1234567890123456789"),
                """
                        <html>
                          <head>
                            <link rel="canonical" href="https://www.tiktok.com/@demo/video/1234567890123456789">
                            <script id="__NEXT_DATA__" type="application/json">
                              {
                                "props": {
                                  "pageProps": {
                                    "itemInfo": {
                                      "itemStruct": {
                                        "id": "1234567890123456789",
                                        "video": {
                                          "width": 720,
                                          "height": 1280,
                                          "play_addr": {
                                            "url_list": ["https://cdn.example.com/from-next.mp4"],
                                            "width": 720,
                                            "height": 1280
                                          }
                                        }
                                      }
                                    }
                                  }
                                }
                              }
                            </script>
                          </head>
                        </html>
                        """);
        transport.enqueueDownload("https://cdn.example.com/from-next.mp4", "next-data-video");

        var downloader = downloader(transport, 1);
        var downloaded = downloader.download("https://www.tiktok.com/@demo/video/1234567890123456789");
        createdFiles.add(downloaded);

        assertEquals("next-data-video", Files.readString(downloaded));
    }

    @Test
    void prefersHigherQualityVariantBasedOnUrlKeyWhenDimensionsAreMissing() throws IOException {
        var transport = new StubTransport();
        transport.enqueueText(
                "https://www.tiktok.com/@demo/video/2223334445556667778",
                URI.create("https://www.tiktok.com/@demo/video/2223334445556667778"),
                htmlWithUniversalData("""
                        {
                          "__DEFAULT_SCOPE__": {
                            "webapp.video-detail": {
                              "statusCode": 0,
                              "itemInfo": {
                                "itemStruct": {
                                  "id": "2223334445556667778",
                                  "video": {
                                    "bitrateInfo": [
                                      {
                                        "Bitrate": 500000,
                                        "PlayAddr": {
                                          "UrlList": ["https://cdn.example.com/variant-540.mp4"],
                                          "UrlKey": "v12344_h264_540p_500"
                                        }
                                      },
                                      {
                                        "Bitrate": 500000,
                                        "PlayAddr": {
                                          "UrlList": ["https://cdn.example.com/variant-720.mp4"],
                                          "UrlKey": "v12344_h264_720p_500"
                                        }
                                      }
                                    ]
                                  }
                                }
                              }
                            }
                          }
                        }
                        """));
        transport.enqueueDownload("https://cdn.example.com/variant-720.mp4", "url-key-quality-video");

        var downloader = downloader(transport, 0);
        var downloaded = downloader.download("https://www.tiktok.com/@demo/video/2223334445556667778");
        createdFiles.add(downloaded);

        assertEquals("url-key-quality-video", Files.readString(downloaded));
        assertEquals(URI.create("https://cdn.example.com/variant-720.mp4"), transport.lastDownloadedUri());
    }

    @Test
    void retriesTransientTransportFailuresBeforeSucceeding() throws IOException {
        var transport = new StubTransport();
        transport.enqueueTextFailure("https://www.tiktok.com/@demo/video/998877665544332211", new TikTokDownloader.TikTokDownloadException("temporary page failure"));
        transport.enqueueText(
                "https://www.tiktok.com/@demo/video/998877665544332211",
                URI.create("https://www.tiktok.com/@demo/video/998877665544332211"),
                htmlWithUniversalData("""
                        {
                          "__DEFAULT_SCOPE__": {
                            "webapp.video-detail": {
                              "statusCode": 0,
                              "itemInfo": {
                                "itemStruct": {
                                  "id": "998877665544332211",
                                  "video": {
                                    "width": 720,
                                    "height": 1280,
                                    "playAddr": "https://cdn.example.com/retry-video.mp4"
                                  }
                                }
                              }
                            }
                          }
                        }
                        """));
        transport.enqueueDownloadFailure("https://cdn.example.com/retry-video.mp4", new TikTokDownloader.TikTokDownloadException("temporary download failure"));
        transport.enqueueDownload("https://cdn.example.com/retry-video.mp4", "retried-video");

        var downloader = downloader(transport, 2);
        var downloaded = downloader.download("https://www.tiktok.com/@demo/video/998877665544332211");
        createdFiles.add(downloaded);

        assertEquals("retried-video", Files.readString(downloaded));
        assertEquals(2, transport.textAttempts("https://www.tiktok.com/@demo/video/998877665544332211"));
        assertEquals(2, transport.downloadAttempts("https://cdn.example.com/retry-video.mp4"));
    }

    @Test
    void fallsBackToNextCandidateWhenBestCandidateDownloadFails() throws IOException {
        var transport = new StubTransport();
        transport.enqueueText(
                "https://www.tiktok.com/@demo/video/1112223334445556667",
                URI.create("https://www.tiktok.com/@demo/video/1112223334445556667"),
                htmlWithUniversalData("""
                        {
                          "__DEFAULT_SCOPE__": {
                            "webapp.video-detail": {
                              "statusCode": 0,
                              "itemInfo": {
                                "itemStruct": {
                                  "id": "1112223334445556667",
                                  "video": {
                                    "width": 720,
                                    "height": 1280,
                                    "playAddr": "https://cdn.example.com/blocked-best.mp4",
                                    "downloadAddr": "https://cdn.example.com/fallback-download.mp4",
                                    "hasWatermark": true
                                  }
                                }
                              }
                            }
                          }
                        }
                        """));
        transport.enqueueDownloadFailure(
                "https://cdn.example.com/blocked-best.mp4",
                new TikTokDownloader.TikTokDownloadException("download TikTok video failed: extracted video URL has expired or is blocked (HTTP 403).")
        );
        transport.enqueueDownload("https://cdn.example.com/fallback-download.mp4", "fallback-video");

        var downloader = downloader(transport, 0);
        var downloaded = downloader.download("https://www.tiktok.com/@demo/video/1112223334445556667");
        createdFiles.add(downloaded);

        assertEquals("fallback-video", Files.readString(downloaded));
        assertEquals(URI.create("https://cdn.example.com/fallback-download.mp4"), transport.lastDownloadedUri());
        assertEquals(1, transport.downloadAttempts("https://cdn.example.com/blocked-best.mp4"));
        assertEquals(1, transport.downloadAttempts("https://cdn.example.com/fallback-download.mp4"));
    }

    @Test
    void throwsClearExceptionForInvalidNonTikTokUrl() {
        var downloader = downloader(new StubTransport(), 0);

        var exception = assertThrows(
                TikTokDownloader.TikTokDownloadException.class,
                () -> downloader.download("https://example.com/video/123")
        );

        assertEquals("Invalid URL: expected a tiktok.com URL", exception.getMessage());
    }

    @Test
    void throwsClearExceptionForPrivateVideo() {
        var transport = new StubTransport();
        transport.enqueueText(
                "https://www.tiktok.com/@private/video/111",
                URI.create("https://www.tiktok.com/@private/video/111"),
                htmlWithUniversalData("""
                        {
                          "__DEFAULT_SCOPE__": {
                            "webapp.video-detail": {
                              "statusCode": 10216,
                              "statusMsg": "This video is private"
                            }
                          }
                        }
                        """));

        var downloader = downloader(transport, 0);

        var exception = assertThrows(
                TikTokDownloader.TikTokDownloadException.class,
                () -> downloader.download("https://www.tiktok.com/@private/video/111")
        );

        assertEquals("TikTok video is private or restricted", exception.getMessage());
    }

    @Test
    void throwsExtractionFailureWhenNoPlayableVideoUrlIsPresent() {
        var transport = new StubTransport();
        transport.enqueueText(
                "https://www.tiktok.com/@demo/video/555",
                URI.create("https://www.tiktok.com/@demo/video/555"),
                htmlWithUniversalData("""
                        {
                          "__DEFAULT_SCOPE__": {
                            "webapp.video-detail": {
                              "statusCode": 0,
                              "itemInfo": {
                                "itemStruct": {
                                  "id": "555",
                                  "video": {
                                    "width": 720,
                                    "height": 1280
                                  }
                                }
                              }
                            }
                          }
                        }
                        """));

        var downloader = downloader(transport, 0);

        var exception = assertThrows(
                TikTokDownloader.TikTokDownloadException.class,
                () -> downloader.download("https://www.tiktok.com/@demo/video/555")
        );

        assertEquals("Extraction failure: unable to find a playable TikTok video URL", exception.getMessage());
    }

    @Test
    void throwsClearExceptionForBlankUrl() {
        var downloader = downloader(new StubTransport(), 0);

        var exception = assertThrows(
                TikTokDownloader.TikTokDownloadException.class,
                () -> downloader.download("   ")
        );

        assertEquals("Invalid URL: TikTok URL must not be blank", exception.getMessage());
    }

    @Test
    void supportsProtocolRelativeMediaUrls() throws IOException {
        var transport = new StubTransport();
        transport.enqueueText(
                "https://www.tiktok.com/@demo/video/777",
                URI.create("https://www.tiktok.com/@demo/video/777"),
                htmlWithUniversalData("""
                        {
                          "__DEFAULT_SCOPE__": {
                            "webapp.video-detail": {
                              "statusCode": 0,
                              "itemInfo": {
                                "itemStruct": {
                                  "id": "777",
                                  "video": {
                                    "width": 720,
                                    "height": 1280,
                                    "playAddr": "//cdn.example.com/protocol-relative.mp4"
                                  }
                                }
                              }
                            }
                          }
                        }
                        """));
        transport.enqueueDownload("https://cdn.example.com/protocol-relative.mp4", "protocol-relative-video");

        var downloader = downloader(transport, 0);
        var downloaded = downloader.download("https://www.tiktok.com/@demo/video/777");
        createdFiles.add(downloaded);

        assertEquals("protocol-relative-video", Files.readString(downloaded));
        assertEquals(URI.create("https://cdn.example.com/protocol-relative.mp4"), transport.lastDownloadedUri());
    }

    @Test
    void findsNestedItemStructRecursively() throws IOException {
        var transport = new StubTransport();
        transport.enqueueText(
                "https://www.tiktok.com/@demo/video/888",
                URI.create("https://www.tiktok.com/@demo/video/888"),
                htmlWithUniversalData("""
                        {
                          "wrapper": {
                            "deeper": {
                              "itemStruct": {
                                "id": "888",
                                "video": {
                                  "playAddr": "https://cdn.example.com/recursive.mp4"
                                }
                              }
                            }
                          }
                        }
                        """));
        transport.enqueueDownload("https://cdn.example.com/recursive.mp4", "recursive-video");

        var downloader = downloader(transport, 0);
        var downloaded = downloader.download("https://www.tiktok.com/@demo/video/888");
        createdFiles.add(downloaded);

        assertEquals("recursive-video", Files.readString(downloaded));
    }

    @Test
    void throwsLastFailureWhenAllCandidatesFail() {
        var transport = new StubTransport();
        transport.enqueueText(
                "https://www.tiktok.com/@demo/video/999",
                URI.create("https://www.tiktok.com/@demo/video/999"),
                htmlWithUniversalData("""
                        {
                          "__DEFAULT_SCOPE__": {
                            "webapp.video-detail": {
                              "statusCode": 0,
                              "itemInfo": {
                                "itemStruct": {
                                  "id": "999",
                                  "video": {
                                    "playAddr": "https://cdn.example.com/blocked-one.mp4",
                                    "downloadAddr": "https://cdn.example.com/blocked-two.mp4",
                                    "hasWatermark": false
                                  }
                                }
                              }
                            }
                          }
                        }
                        """));
        transport.enqueueDownloadFailure("https://cdn.example.com/blocked-one.mp4",
                new TikTokDownloader.TikTokDownloadException("first candidate failed"));
        transport.enqueueDownloadFailure("https://cdn.example.com/blocked-two.mp4",
                new TikTokDownloader.TikTokDownloadException("second candidate failed"));

        var downloader = downloader(transport, 0);

        var exception = assertThrows(
                TikTokDownloader.TikTokDownloadException.class,
                () -> downloader.download("https://www.tiktok.com/@demo/video/999")
        );

        assertEquals("second candidate failed", exception.getMessage());
    }

    private TikTokDownloader downloader(StubTransport transport, int retries) {
        return new TikTokDownloader(
                objectMapper,
                new TikTokDownloader.Config(Duration.ofSeconds(1), Duration.ofSeconds(1), retries, "JUnit"),
                transport
        );
    }

    private String htmlWithUniversalData(String json) {
        return """
                <html>
                  <head>
                    <script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">%s</script>
                  </head>
                </html>
                """.formatted(json);
    }

    private String htmlWithSigiState(String json) {
        return """
                <html>
                  <head>
                    <script id="SIGI_STATE" type="application/json">%s</script>
                  </head>
                </html>
                """.formatted(json);
    }

    private static final class StubTransport implements TikTokDownloader.Transport {
        private final Map<String, Deque<Object>> textResponses = new HashMap<>();
        private final Map<String, Deque<Object>> downloadResponses = new HashMap<>();
        private final Map<String, Integer> textAttempts = new HashMap<>();
        private final Map<String, Integer> downloadAttempts = new HashMap<>();
        private URI lastDownloadedUri;

        void enqueueText(String requestUrl, URI finalUri, String body) {
            textResponses.computeIfAbsent(requestUrl, ignored -> new ArrayDeque<>())
                    .addLast(new TikTokDownloader.Response<>(finalUri, 200, body));
        }

        void enqueueTextFailure(String requestUrl, RuntimeException failure) {
            textResponses.computeIfAbsent(requestUrl, ignored -> new ArrayDeque<>()).addLast(failure);
        }

        void enqueueDownload(String requestUrl, String content) {
            downloadResponses.computeIfAbsent(requestUrl, ignored -> new ArrayDeque<>())
                    .addLast(content.getBytes());
        }

        void enqueueDownloadFailure(String requestUrl, RuntimeException failure) {
            downloadResponses.computeIfAbsent(requestUrl, ignored -> new ArrayDeque<>()).addLast(failure);
        }

        int textAttempts(String requestUrl) {
            return textAttempts.getOrDefault(requestUrl, 0);
        }

        int downloadAttempts(String requestUrl) {
            return downloadAttempts.getOrDefault(requestUrl, 0);
        }

        URI lastDownloadedUri() {
            return lastDownloadedUri;
        }

        @Override
        public TikTokDownloader.Response<String> getText(URI uri, Map<String, String> headers) {
            textAttempts.merge(uri.toString(), 1, Integer::sum);
            return cast(next(textResponses, uri.toString()));
        }

        @Override
        public Path downloadToTemp(URI uri, Map<String, String> headers, String suffix) {
            downloadAttempts.merge(uri.toString(), 1, Integer::sum);
            lastDownloadedUri = uri;
            var response = next(downloadResponses, uri.toString());
            if (response instanceof RuntimeException failure) {
                throw failure;
            }
            if (response instanceof byte[] bytes) {
                try {
                    var file = Files.createTempFile("tiktok-test-", suffix);
                    Files.write(file, bytes);
                    return file;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            throw new IllegalStateException("Unsupported download response: " + response);
        }

        private Object next(Map<String, Deque<Object>> responses, String key) {
            var queue = responses.get(key);
            if (queue == null || queue.isEmpty()) {
                throw new IllegalStateException("No stubbed response for " + key);
            }
            var next = queue.removeFirst();
            if (next instanceof RuntimeException failure) {
                throw failure;
            }
            return next;
        }

        @SuppressWarnings("unchecked")
        private <T> T cast(Object value) {
            return (T) value;
        }
    }
}


