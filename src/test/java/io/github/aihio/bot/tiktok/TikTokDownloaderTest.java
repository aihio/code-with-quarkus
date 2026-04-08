package io.github.aihio.bot.tiktok;

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
    void downloadMediaReturnsVideoAndAudioForVideoPosts() throws IOException {
        var transport = new StubTransport();
        transport.enqueueText(
                "https://www.tiktok.com/@demo/video/1231231231231231231",
                URI.create("https://www.tiktok.com/@demo/video/1231231231231231231"),
                htmlWithUniversalData("""
                        {
                          "__DEFAULT_SCOPE__": {
                            "webapp.video-detail": {
                              "statusCode": 0,
                              "itemInfo": {
                                "itemStruct": {
                                  "id": "1231231231231231231",
                                  "video": {
                                    "playAddr": "https://cdn.example.com/video-main.mp4"
                                  },
                                  "music": {
                                    "playUrl": "https://cdn.example.com/audio-main.mp3"
                                  }
                                }
                              }
                            }
                          }
                        }
                        """));
        transport.enqueueDownload("https://cdn.example.com/audio-main.mp3", "audio-main");
        transport.enqueueDownload("https://cdn.example.com/video-main.mp4", "video-main");

        var downloader = downloader(transport, 0);
        var media = downloader.downloadMedia("https://www.tiktok.com/@demo/video/1231231231231231231");
        createdFiles.add(media.videoPath());
        createdFiles.add(media.audioPath());

        assertFalse(media.gallery());
        assertNotNull(media.videoPath());
        assertNotNull(media.audioPath());
        assertEquals("video-main", Files.readString(media.videoPath()));
        assertEquals("audio-main", Files.readString(media.audioPath()));
    }

    @Test
    void downloadMediaReturnsGalleryPhotosAndAudioForImagePosts() throws IOException {
        var transport = new StubTransport();
        transport.enqueueText(
                "https://www.tiktok.com/@demo/video/3213213213213213213",
                URI.create("https://www.tiktok.com/@demo/video/3213213213213213213"),
                htmlWithUniversalData("""
                        {
                          "__DEFAULT_SCOPE__": {
                            "webapp.video-detail": {
                              "statusCode": 0,
                              "itemInfo": {
                                "itemStruct": {
                                  "id": "3213213213213213213",
                                  "imagePost": {
                                    "images": [
                                      {
                                        "imageURL": {
                                          "urlList": ["https://cdn.example.com/gallery-1.jpg"]
                                        }
                                      },
                                      {
                                        "imageURL": {
                                          "urlList": ["https://cdn.example.com/gallery-2.jpg"]
                                        }
                                      }
                                    ]
                                  },
                                  "music": {
                                    "playUrl": "https://cdn.example.com/gallery-audio.mp3"
                                  }
                                }
                              }
                            }
                          }
                        }
                        """));
        transport.enqueueDownload("https://cdn.example.com/gallery-audio.mp3", "gallery-audio");
        transport.enqueueDownload("https://cdn.example.com/gallery-1.jpg", "gallery-photo-1");
        transport.enqueueDownload("https://cdn.example.com/gallery-2.jpg", "gallery-photo-2");

        var downloader = downloader(transport, 0);
        var media = downloader.downloadMedia("https://www.tiktok.com/@demo/video/3213213213213213213");
        createdFiles.add(media.audioPath());
        for (var photoPath : media.photoPaths()) {
            createdFiles.add(photoPath);
        }

        assertTrue(media.gallery());
        assertNull(media.videoPath());
        assertEquals(2, media.photoPaths().size());
        assertEquals("gallery-audio", Files.readString(media.audioPath()));
        assertEquals("gallery-photo-1", Files.readString(media.photoPaths().get(0)));
        assertEquals("gallery-photo-2", Files.readString(media.photoPaths().get(1)));
    }

    @Test
    void downloadMediaSupportsGalleryImagePostInfoSchema() throws IOException {
        var transport = new StubTransport();
        transport.enqueueText(
                "https://www.tiktok.com/@demo/video/6546546546546546546",
                URI.create("https://www.tiktok.com/@demo/video/6546546546546546546"),
                htmlWithUniversalData("""
                        {
                          "__DEFAULT_SCOPE__": {
                            "webapp.video-detail": {
                              "statusCode": 0,
                              "itemInfo": {
                                "image_post_info": {
                                  "images": [
                                    {
                                      "imageURL": {
                                        "urlList": ["https://cdn.example.com/gallery-alt-1.jpg"]
                                      }
                                    }
                                  ]
                                },
                                "music": {
                                  "playUrl": "https://cdn.example.com/gallery-alt-audio.mp3"
                                }
                              }
                            }
                          }
                        }
                        """));
        transport.enqueueDownload("https://cdn.example.com/gallery-alt-audio.mp3", "gallery-alt-audio");
        transport.enqueueDownload("https://cdn.example.com/gallery-alt-1.jpg", "gallery-alt-photo");

        var downloader = downloader(transport, 0);
        var media = downloader.downloadMedia("https://www.tiktok.com/@demo/video/6546546546546546546");
        createdFiles.add(media.audioPath());
        for (var photoPath : media.photoPaths()) {
            createdFiles.add(photoPath);
        }

        assertTrue(media.gallery());
        assertEquals(1, media.photoPaths().size());
        assertEquals("gallery-alt-audio", Files.readString(media.audioPath()));
        assertEquals("gallery-alt-photo", Files.readString(media.photoPaths().get(0)));
    }

    @Test
    void downloadsVideoWhenScriptPayloadIsWrappedInWindowAssignment() throws IOException {
        var transport = new StubTransport();
        transport.enqueueText(
                "https://www.tiktok.com/@demo/video/7657657657657657657",
                URI.create("https://www.tiktok.com/@demo/video/7657657657657657657"),
                """
                        <html>
                          <head>
                            <script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
                              window.__UNIVERSAL_DATA_FOR_REHYDRATION__ = {
                                "__DEFAULT_SCOPE__": {
                                  "webapp.video-detail": {
                                    "statusCode": 0,
                                    "itemInfo": {
                                      "itemStruct": {
                                        "video": {
                                          "playAddr": "https://cdn.example.com/wrapped-video.mp4"
                                        }
                                      }
                                    }
                                  }
                                }
                              };
                            </script>
                          </head>
                        </html>
                        """);
        transport.enqueueDownload("https://cdn.example.com/wrapped-video.mp4", "wrapped-video");

        var downloader = downloader(transport, 0);
        var downloaded = downloader.download("https://www.tiktok.com/@demo/video/7657657657657657657");
        createdFiles.add(downloaded);

        assertEquals("wrapped-video", Files.readString(downloaded));
        assertEquals(URI.create("https://cdn.example.com/wrapped-video.mp4"), transport.lastDownloadedUri());
    }

    @Test
    void downloadsVideoWhenPayloadIsInScriptWithoutKnownId() throws IOException {
        var transport = new StubTransport();
        transport.enqueueText(
                "https://www.tiktok.com/@demo/video/7766554433221100998",
                URI.create("https://www.tiktok.com/@demo/video/7766554433221100998"),
                """
                        <html>
                          <head>
                            <script>
                              window.__SOME_RUNTIME_STATE__ = {
                                "__DEFAULT_SCOPE__": {
                                  "webapp.video-detail": {
                                    "statusCode": 0,
                                    "itemInfo": {
                                      "itemStruct": {
                                        "video": {
                                          "playAddr": "https://cdn.example.com/no-id-video.mp4"
                                        }
                                      }
                                    }
                                  }
                                }
                              };
                            </script>
                          </head>
                        </html>
                        """);
        transport.enqueueDownload("https://cdn.example.com/no-id-video.mp4", "no-id-video");

        var downloader = downloader(transport, 0);
        var downloaded = downloader.download("https://www.tiktok.com/@demo/video/7766554433221100998");
        createdFiles.add(downloaded);

        assertEquals("no-id-video", Files.readString(downloaded));
        assertEquals(URI.create("https://cdn.example.com/no-id-video.mp4"), transport.lastDownloadedUri());
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
    void prefersHigherBitrateEvenIfLowerBitrateAlternativeUsesH265() throws IOException {
        var transport = new StubTransport();
        transport.enqueueText(
                "https://www.tiktok.com/@demo/video/3334445556667778889",
                URI.create("https://www.tiktok.com/@demo/video/3334445556667778889"),
                htmlWithUniversalData("""
                        {
                          "__DEFAULT_SCOPE__": {
                            "webapp.video-detail": {
                              "statusCode": 0,
                              "itemInfo": {
                                "itemStruct": {
                                  "id": "3334445556667778889",
                                  "video": {
                                    "bitrateInfo": [
                                      {
                                        "Bitrate": 1000000,
                                        "PlayAddr": {
                                          "UrlList": ["https://cdn.example.com/h265-lower-bitrate.mp4"],
                                          "UrlKey": "v100_bytevc1_720p_1000"
                                        }
                                      },
                                      {
                                        "Bitrate": 1500000,
                                        "PlayAddr": {
                                          "UrlList": ["https://cdn.example.com/h264-higher-bitrate.mp4"],
                                          "UrlKey": "v100_h264_720p_1500"
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
        transport.enqueueDownload("https://cdn.example.com/h264-higher-bitrate.mp4", "higher-bitrate-video");

        var downloader = downloader(transport, 0);
        var downloaded = downloader.download("https://www.tiktok.com/@demo/video/3334445556667778889");
        createdFiles.add(downloaded);

        assertEquals("higher-bitrate-video", Files.readString(downloaded));
        assertEquals(URI.create("https://cdn.example.com/h264-higher-bitrate.mp4"), transport.lastDownloadedUri());
    }

    @Test
    void prefersH264WhenResolutionAndBitrateAreEqualForTelegramPlayback() throws IOException {
        var transport = new StubTransport();
        transport.enqueueText(
                "https://www.tiktok.com/@demo/video/4445556667778889990",
                URI.create("https://www.tiktok.com/@demo/video/4445556667778889990"),
                htmlWithUniversalData("""
                        {
                          "__DEFAULT_SCOPE__": {
                            "webapp.video-detail": {
                              "statusCode": 0,
                              "itemInfo": {
                                "itemStruct": {
                                  "id": "4445556667778889990",
                                  "video": {
                                    "bitrateInfo": [
                                      {
                                        "Bitrate": 1200000,
                                        "PlayAddr": {
                                          "UrlList": ["https://cdn.example.com/h264-equal.mp4"],
                                          "UrlKey": "v101_h264_720p_1200"
                                        }
                                      },
                                      {
                                        "Bitrate": 1200000,
                                        "PlayAddr": {
                                          "UrlList": ["https://cdn.example.com/h265-equal.mp4"],
                                          "UrlKey": "v101_bytevc1_720p_1200"
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
        transport.enqueueDownload("https://cdn.example.com/h264-equal.mp4", "h264-preferred-video");

        var downloader = downloader(transport, 0);
        var downloaded = downloader.download("https://www.tiktok.com/@demo/video/4445556667778889990");
        createdFiles.add(downloaded);

        assertEquals("h264-preferred-video", Files.readString(downloaded));
        assertEquals(URI.create("https://cdn.example.com/h264-equal.mp4"), transport.lastDownloadedUri());
    }

    @Test
    void prefersH264OverHigherResolutionH265ForSmootherTelegramPlayback() throws IOException {
        var transport = new StubTransport();
        transport.enqueueText(
                "https://www.tiktok.com/@demo/video/6667778889990001112",
                URI.create("https://www.tiktok.com/@demo/video/6667778889990001112"),
                htmlWithUniversalData("""
                        {
                          "__DEFAULT_SCOPE__": {
                            "webapp.video-detail": {
                              "statusCode": 0,
                              "itemInfo": {
                                "itemStruct": {
                                  "id": "6667778889990001112",
                                  "video": {
                                    "bitrateInfo": [
                                      {
                                        "Bitrate": 1400000,
                                        "PlayAddr": {
                                          "UrlList": ["https://cdn.example.com/h264-720.mp4"],
                                          "UrlKey": "v103_h264_720p_1400"
                                        }
                                      },
                                      {
                                        "Bitrate": 2500000,
                                        "PlayAddr": {
                                          "UrlList": ["https://cdn.example.com/h265-1080.mp4"],
                                          "UrlKey": "v103_bytevc1_1080p_2500"
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
        transport.enqueueDownload("https://cdn.example.com/h264-720.mp4", "telegram-smooth-video");

        var downloader = downloader(transport, 0);
        var downloaded = downloader.download("https://www.tiktok.com/@demo/video/6667778889990001112");
        createdFiles.add(downloaded);

        assertEquals("telegram-smooth-video", Files.readString(downloaded));
        assertEquals(URI.create("https://cdn.example.com/h264-720.mp4"), transport.lastDownloadedUri());
    }

    @Test
    void skipsUnplayableBytevc2AndUsesPlayableFallback() throws IOException {
        var transport = new StubTransport();
        transport.enqueueText(
                "https://www.tiktok.com/@demo/video/5556667778889990001",
                URI.create("https://www.tiktok.com/@demo/video/5556667778889990001"),
                htmlWithUniversalData("""
                        {
                          "__DEFAULT_SCOPE__": {
                            "webapp.video-detail": {
                              "statusCode": 0,
                              "itemInfo": {
                                "itemStruct": {
                                  "id": "5556667778889990001",
                                  "video": {
                                    "bitrateInfo": [
                                      {
                                        "Bitrate": 5000000,
                                        "PlayAddr": {
                                          "UrlList": ["https://cdn.example.com/unplayable-bytevc2.mp4"],
                                          "UrlKey": "v102_bytevc2_1080p_5000"
                                        }
                                      },
                                      {
                                        "Bitrate": 2500000,
                                        "PlayAddr": {
                                          "UrlList": ["https://cdn.example.com/playable-h265.mp4"],
                                          "UrlKey": "v102_bytevc1_720p_2500"
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
        transport.enqueueDownload("https://cdn.example.com/playable-h265.mp4", "playable-fallback-video");

        var downloader = downloader(transport, 0);
        var downloaded = downloader.download("https://www.tiktok.com/@demo/video/5556667778889990001");
        createdFiles.add(downloaded);

        assertEquals("playable-fallback-video", Files.readString(downloaded));
        assertEquals(URI.create("https://cdn.example.com/playable-h265.mp4"), transport.lastDownloadedUri());
        assertEquals(0, transport.downloadAttempts("https://cdn.example.com/unplayable-bytevc2.mp4"));
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

    @Test
    void downloadMediaSupportsPhotoRouteWithDirectImagesSchema() throws IOException {
        var transport = new StubTransport();
        transport.enqueueText(
                "https://www.tiktok.com/@demo/photo/7615375482187435272",
                URI.create("https://www.tiktok.com/@demo/photo/7615375482187435272"),
                htmlWithUniversalData("""
                        {
                          "__DEFAULT_SCOPE__": {
                            "webapp.photo-detail": {
                              "statusCode": 0,
                              "itemInfo": {
                                "itemStruct": {
                                  "id": "7615375482187435272",
                                  "images": [
                                    {
                                      "imageURL": {
                                        "urlList": ["https://cdn.example.com/direct-1.jpg"]
                                      }
                                    },
                                    {
                                      "display_image": {
                                        "url_list": ["https://cdn.example.com/direct-2.jpg"]
                                      }
                                    }
                                  ],
                                  "music": {
                                    "playUrl": "https://cdn.example.com/direct-audio.mp3"
                                  }
                                }
                              }
                            }
                          }
                        }
                        """));
        transport.enqueueDownload("https://cdn.example.com/direct-audio.mp3", "direct-audio");
        transport.enqueueDownload("https://cdn.example.com/direct-1.jpg", "direct-photo-1");
        transport.enqueueDownload("https://cdn.example.com/direct-2.jpg", "direct-photo-2");

        var downloader = downloader(transport, 0);
        var media = downloader.downloadMedia("https://www.tiktok.com/@demo/photo/7615375482187435272");
        createdFiles.add(media.audioPath());
        for (var photoPath : media.photoPaths()) {
            createdFiles.add(photoPath);
        }

        assertTrue(media.gallery());
        assertNull(media.videoPath());
        assertEquals(2, media.photoPaths().size());
        assertEquals("direct-audio", Files.readString(media.audioPath()));
        assertEquals("direct-photo-1", Files.readString(media.photoPaths().get(0)));
        assertEquals("direct-photo-2", Files.readString(media.photoPaths().get(1)));
    }

    @Test
    void downloadMediaFallsBackToEmbedPageForPhotoRoutesWithoutHydrationItemStruct() throws IOException {
        var photoId = "7615375482187435272";
        var canonicalPhotoUrl = "https://www.tiktok.com/@canalguin/photo/" + photoId;

        var transport = new StubTransport();
        transport.enqueueText(
                canonicalPhotoUrl,
                URI.create(canonicalPhotoUrl),
                "<html><head><script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\" type=\"application/json\">{\"__DEFAULT_SCOPE__\":{\"seo.abtest\":{\"canonical\":\"" + canonicalPhotoUrl + "\"}}}</script></head></html>");

        transport.enqueueText(
                "https://www.tiktok.com/embed/v2/" + photoId,
                URI.create("https://www.tiktok.com/embed/v2/" + photoId),
                """
                        <html>
                          <head>
                            <script id="__FRONTITY_CONNECT_STATE__" type="application/json">
                              {
                                "source": {
                                  "data": {
                                    "/embed/v2/7615375482187435272": {
                                      "videoData": {
                                        "itemInfos": {
                                          "id": "7615375482187435272"
                                        },
                                        "musicInfos": {
                                          "playUrl": ["https://cdn.example.com/embed-audio.mp3"]
                                        },
                                        "imagePostInfo": {
                                          "images": [
                                            {
                                              "imageURL": {
                                                "urlList": [
                                                  "https://cdn.example.com/embed-photo-1.jpg",
                                                  "https://cdn.example.com/embed-photo-1-alt.jpg"
                                                ]
                                              }
                                            },
                                            {
                                              "imageURL": {
                                                "urlList": [
                                                  "https://cdn.example.com/embed-photo-2.jpg",
                                                  "https://cdn.example.com/embed-photo-2-alt.jpg"
                                                ]
                                              }
                                            }
                                          ],
                                          "displayImages": [
                                            {
                                              "urlList": ["https://cdn.example.com/embed-photo-1-display.jpg"]
                                            },
                                            {
                                              "urlList": ["https://cdn.example.com/embed-photo-2-display.jpg"]
                                            }
                                          ]
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

        transport.enqueueDownload("https://cdn.example.com/embed-audio.mp3", "embed-audio");
        transport.enqueueDownload("https://cdn.example.com/embed-photo-1.jpg", "embed-photo-1");
        transport.enqueueDownload("https://cdn.example.com/embed-photo-2.jpg", "embed-photo-2");

        var downloader = downloader(transport, 0);
        var media = downloader.downloadMedia(canonicalPhotoUrl);
        createdFiles.add(media.audioPath());
        createdFiles.addAll(media.photoPaths());

        assertTrue(media.gallery());
        assertEquals(2, media.photoPaths().size());
        assertEquals("embed-photo-1", Files.readString(media.photoPaths().get(0)));
        assertEquals("embed-photo-2", Files.readString(media.photoPaths().get(1)));
        assertEquals(0, transport.downloadAttempts("https://cdn.example.com/embed-photo-1-alt.jpg"));
        assertEquals(0, transport.downloadAttempts("https://cdn.example.com/embed-photo-1-display.jpg"));
        assertEquals(0, transport.downloadAttempts("https://cdn.example.com/embed-photo-2-alt.jpg"));
        assertEquals(0, transport.downloadAttempts("https://cdn.example.com/embed-photo-2-display.jpg"));
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


