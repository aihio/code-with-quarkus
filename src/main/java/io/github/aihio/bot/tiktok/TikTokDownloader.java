package io.github.aihio.bot.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

@ApplicationScoped
public class TikTokDownloader {
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36";
    private static final Pattern CANONICAL_LINK_PATTERN = Pattern.compile("<link[^>]*rel=[\"']canonical[\"'][^>]*href=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_URL_PATTERN = Pattern.compile("<meta[^>]*property=[\"']og:url[\"'][^>]*content=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("/(?:video|photo)/(\\d+)");

    private static final String AUDIO_SUFFIX = ".mp3";
    private static final String VIDEO_SUFFIX = ".mp4";
    private static final String PHOTO_SUFFIX = ".jpg";

    private final Config config;
    private final Transport transport;
    private final TikTokHydrationParser hydrationParser;
    private final TikTokVideoSelector videoSelector;
    private final TikTokMediaUrlExtractor mediaUrlExtractor;

    @Inject
    public TikTokDownloader(ObjectMapper objectMapper, TikTokDownloaderRuntimeConfig runtimeConfig, HttpClient httpClient) {
        this(objectMapper, configFrom(runtimeConfig), httpClient);
    }

    private TikTokDownloader(ObjectMapper objectMapper, Config config, HttpClient httpClient) {
        this(objectMapper, config, new TikTokHttpTransport(config, httpClient));
    }

    public TikTokDownloader(ObjectMapper objectMapper, Config config, Transport transport) {
        var checkedObjectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.hydrationParser = new TikTokHydrationParser(checkedObjectMapper);
        this.videoSelector = new TikTokVideoSelector();
        this.mediaUrlExtractor = new TikTokMediaUrlExtractor();
    }

    private static Config configFrom(TikTokDownloaderRuntimeConfig runtimeConfig) {
        return Config.from(runtimeConfig);
    }

    public Path download(String url) {
        var postContext = resolvePostContext(url);
        var itemStruct = hydrationParser.extractItemStruct(postContext.pageBody(), postContext.videoId());
        return downloadVideo(itemStruct);
    }

    public DownloadedMedia downloadMedia(String url) {
        var postContext = resolvePostContext(url);
        var itemStruct = extractItemStructWithEmbedFallback(
                postContext.pageBody(),
                postContext.videoId(),
                postContext.photoRoute());
        var audioPath = downloadAudio(itemStruct);
        var photoUrls = mediaUrlExtractor.selectPhotoUrls(itemStruct);
        var photoPaths = new ArrayList<Path>();

        if (!photoUrls.isEmpty()) {
            try {
                for (var photoUrl : photoUrls) {
                    var photoPath = withRetries("download TikTok gallery image",
                            () -> transport.downloadToTemp(URI.create(photoUrl), downloadHeaders(), PHOTO_SUFFIX));
                    photoPaths.add(photoPath);
                }
                return new DownloadedMedia(null, photoPaths, audioPath);
            } catch (RuntimeException e) {
                deleteAllQuietly(photoPaths);
                deleteQuietly(audioPath);
                throw e;
            }
        }

        try {
            return new DownloadedMedia(downloadVideo(itemStruct), List.of(), audioPath);
        } catch (RuntimeException e) {
            deleteQuietly(audioPath);
            throw e;
        }
    }

    private PostContext resolvePostContext(String url) {
        var inputUri = parseTikTokUri(url);
        var page = withRetries("fetch TikTok page", () -> transport.getText(inputUri, pageHeaders()));
        var pageUri = extractCanonicalUri(page.body())
                .filter(uri -> isTikTokHost(uri.getHost()))
                .orElse(page.uri());
        return new PostContext(page.body(), extractVideoId(pageUri).orElse(null), isPhotoRoute(pageUri));
    }

    private Path downloadVideo(JsonNode itemStruct) {
        return downloadFromCandidates(
                videoSelector.selectVideoUrls(itemStruct),
                "download TikTok video",
                VIDEO_SUFFIX,
                "Extraction failure: unable to find a playable TikTok video URL"
        );
    }

    private Path downloadAudio(JsonNode itemStruct) {
        return downloadFromCandidates(
                mediaUrlExtractor.selectAudioUrls(itemStruct),
                "download TikTok audio",
                AUDIO_SUFFIX,
                "Extraction failure: unable to find TikTok audio track"
        );
    }

    private Path downloadFromCandidates(List<String> urls, String action, String suffix, String noCandidatesMessage) {
        if (urls.isEmpty()) {
            throw new TikTokDownloadException(noCandidatesMessage);
        }

        TikTokDownloadException lastFailure = null;
        for (var candidateUrl : urls) {
            try {
                return withRetries(action,
                        () -> transport.downloadToTemp(URI.create(candidateUrl), downloadHeaders(), suffix));
            } catch (TikTokDownloadException e) {
                lastFailure = e;
            }
        }

        throw lastFailure;
    }

    private JsonNode extractItemStructWithEmbedFallback(String html, String expectedVideoId, boolean photoRoute) {
        try {
            return hydrationParser.extractItemStruct(html, expectedVideoId);
        } catch (TikTokDownloadException originalFailure) {
            if (!photoRoute || expectedVideoId == null || expectedVideoId.isBlank()) {
                throw originalFailure;
            }

            var embedUri = URI.create("https://www.tiktok.com/embed/v2/" + expectedVideoId);
            try {
                var embedPage = withRetries("fetch TikTok embed page", () -> transport.getText(embedUri, pageHeaders()));
                return hydrationParser.extractItemStruct(embedPage.body(), expectedVideoId);
            } catch (RuntimeException ignored) {
                throw originalFailure;
            }
        }
    }

    private boolean isPhotoRoute(URI uri) {
        if (uri == null || uri.getPath() == null) {
            return false;
        }
        return uri.getPath().contains("/photo/");
    }


    private void deleteAllQuietly(Collection<Path> paths) {
        for (var path : paths) {
            deleteQuietly(path);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort cleanup only
        }
    }

    private <T> T withRetries(String action, ThrowingSupplier<T> supplier) {
        TikTokDownloadException lastFailure = null;
        for (int attempt = 0; attempt <= config.retries(); attempt++) {
            try {
                return supplier.get();
            } catch (TikTokDownloadException e) {
                lastFailure = e;
                if (attempt == config.retries()) {
                    throw lastFailure;
                }
            }
        }
        throw lastFailure == null ? new TikTokDownloadException(action + " failed") : lastFailure;
    }

    private URI parseTikTokUri(String url) {
        if (url == null || url.isBlank()) {
            throw new TikTokDownloadException("Invalid URL: TikTok URL must not be blank");
        }

        try {
            var uri = new URI(url.trim());
            var scheme = Optional.ofNullable(uri.getScheme()).orElse("").toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new TikTokDownloadException("Invalid URL: only http(s) TikTok URLs are supported");
            }
            if (!isTikTokHost(uri.getHost())) {
                throw new TikTokDownloadException("Invalid URL: expected a tiktok.com URL");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new TikTokDownloadException("Invalid URL: " + e.getMessage(), e);
        }
    }

    private Optional<URI> extractCanonicalUri(String html) {
        for (var pattern : List.of(CANONICAL_LINK_PATTERN, OG_URL_PATTERN)) {
            var matcher = pattern.matcher(html);
            if (matcher.find()) {
                try {
                    return Optional.of(new URI(TikTokHtml.unescapeBasicHtml(matcher.group(1))));
                } catch (URISyntaxException ignored) {
                    // ignore malformed canonical URL and fall back to response URI
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> extractVideoId(URI uri) {
        if (uri == null || uri.getPath() == null) {
            return Optional.empty();
        }
        var matcher = VIDEO_ID_PATTERN.matcher(uri.getPath());
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private Map<String, String> pageHeaders() {
        var headers = new LinkedHashMap<String, String>();
        headers.put("User-Agent", config.userAgent());
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("Referer", "https://www.tiktok.com/");
        return headers;
    }

    private Map<String, String> downloadHeaders() {
        var headers = new LinkedHashMap<String, String>();
        headers.put("User-Agent", config.userAgent());
        headers.put("Accept", "*/*");
        headers.put("Accept-Encoding", "gzip, deflate, br");
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("Cache-Control", "no-cache");
        headers.put("Pragma", "no-cache");
        // Critical for CDN: use the actual TikTok domain as referer
        headers.put("Referer", "https://www.tiktok.com/");
        headers.put("Origin", "https://www.tiktok.com");
        headers.put("Sec-Fetch-Dest", "video");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Site", "same-site");
        headers.put("Sec-Fetch-User", "?1");
        headers.put("Upgrade-Insecure-Requests", "1");
        return headers;
    }

    private boolean isTikTokHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        var normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("tiktok.com") || normalized.endsWith(".tiktok.com");
    }


    public interface Transport {
        Response<String> getText(URI uri, Map<String, String> headers);

        Path downloadToTemp(URI uri, Map<String, String> headers, String suffix);
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get();
    }

    public record Response<T>(URI uri, int statusCode, T body) {
    }

    public record DownloadedMedia(Path videoPath, List<Path> photoPaths, Path audioPath) {
        public DownloadedMedia {
            photoPaths = photoPaths == null ? List.of() : List.copyOf(photoPaths);
        }

        public boolean gallery() {
            return !photoPaths.isEmpty();
        }
    }


    private record PostContext(String pageBody, String videoId, boolean photoRoute) {
    }

    public record Config(Duration connectTimeout, Duration readTimeout, int retries, String userAgent) {

        public Config {
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
            userAgent = userAgent == null || userAgent.isBlank() ? DEFAULT_USER_AGENT : userAgent;
            if (connectTimeout.isZero() || connectTimeout.isNegative()) {
                throw new IllegalArgumentException("connectTimeout must be positive");
            }
            if (readTimeout.isZero() || readTimeout.isNegative()) {
                throw new IllegalArgumentException("readTimeout must be positive");
            }
            if (retries < 0) {
                throw new IllegalArgumentException("retries must be >= 0");
            }
        }

        static Config from(TikTokDownloaderRuntimeConfig runtimeConfig) {
            return new Config(
                    runtimeConfig.connectTimeout(),
                    runtimeConfig.readTimeout(),
                    runtimeConfig.retries(),
                    runtimeConfig.userAgent()
            );
        }
    }

    public static class TikTokDownloadException extends RuntimeException {
        public TikTokDownloadException(String message) {
            super(message);
        }

        public TikTokDownloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}