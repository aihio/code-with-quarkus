package io.github.aihio.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

@ApplicationScoped
public class TikTokDownloader {
    private static final List<String> QUALITY_ORDER = List.of("360p", "540p", "720p", "1080p");
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36";
    private static final Pattern CANONICAL_LINK_PATTERN = Pattern.compile("<link[^>]*rel=[\"']canonical[\"'][^>]*href=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_URL_PATTERN = Pattern.compile("<meta[^>]*property=[\"']og:url[\"'][^>]*content=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("/video/(\\d+)");
    private static final Pattern URL_KEY_PATTERN = Pattern.compile("v[^_]+_(?<codec>[^_]+)_(?<resolution>\\d+p)_(?<bitrate>\\d+)");
    private static final Set<String> JSON_SCRIPT_IDS = Set.of(
            "SIGI_STATE",
            "sigi-persisted-data",
            "__UNIVERSAL_DATA_FOR_REHYDRATION__",
            "__NEXT_DATA__"
    );

    private final ObjectMapper objectMapper;
    private final Config config;
    private final Transport transport;

    @Inject
    public TikTokDownloader(ObjectMapper objectMapper, TikTokDownloaderRuntimeConfig runtimeConfig, HttpClient httpClient) {
        this(objectMapper, configFrom(runtimeConfig), httpClient);
    }

    private TikTokDownloader(ObjectMapper objectMapper, Config config, HttpClient httpClient) {
        this(objectMapper, config, new HttpTransport(config, httpClient));
    }

    TikTokDownloader(ObjectMapper objectMapper, Config config, Transport transport) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
    }

    private static Config configFrom(TikTokDownloaderRuntimeConfig runtimeConfig) {
        return Config.from(runtimeConfig);
    }

    public Path download(String url) {
        var inputUri = parseTikTokUri(url);
        var page = withRetries("fetch TikTok page", () -> transport.getText(inputUri, pageHeaders()));

        var pageUri = extractCanonicalUri(page.body())
                .filter(uri -> isTikTokHost(uri.getHost()))
                .orElse(page.uri());

        var videoId = extractVideoId(pageUri).orElse(null);
        var itemStruct = extractItemStruct(page.body(), videoId);
        var videoUrls = selectVideoUrls(itemStruct);
        if (videoUrls.isEmpty()) {
            throw new TikTokDownloadException("Extraction failure: unable to find a playable TikTok video URL");
        }

        TikTokDownloadException lastFailure = null;
        for (var videoUrl : videoUrls) {
            try {
                return withRetries("download TikTok video",
                        () -> transport.downloadToTemp(URI.create(videoUrl), downloadHeaders(), ".mp4"));
            } catch (TikTokDownloadException e) {
                lastFailure = e;
            }
        }

        throw lastFailure;
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

    private JsonNode extractItemStruct(String html, String expectedVideoId) {

        var roots = extractJsonRoots(html);
        for (var root : roots) {
            detectStatusFailure(root).ifPresent(message -> {
                throw new TikTokDownloadException(message);
            });

            var candidate = findItemStruct(root, expectedVideoId);
            if (candidate.isPresent()) {
                return candidate.get();
            }
        }

        throw new TikTokDownloadException("Extraction failure: unable to locate embedded TikTok video data");
    }

    private List<JsonNode> extractJsonRoots(String html) {
        var roots = new ArrayList<JsonNode>();
        for (var scriptId : JSON_SCRIPT_IDS) {
            findScriptJson(html, scriptId).ifPresent(roots::add);
        }
        return roots;
    }

    private Optional<JsonNode> findScriptJson(String html, String scriptId) {
        var pattern = Pattern.compile(
                "<script[^>]*id=[\"']" + Pattern.quote(scriptId) + "[\"'][^>]*>(.*?)</script>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        var matcher = pattern.matcher(html);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return parseJson(matcher.group(1));
    }

    private Optional<JsonNode> parseJson(String rawJson) {
        var candidate = rawJson == null ? "" : rawJson.trim();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }

        for (var attempt : List.of(candidate, unescapeBasicHtml(candidate))) {
            try {
                return Optional.of(objectMapper.readTree(attempt));
            } catch (IOException ignored) {
                // try next variant
            }
        }
        return Optional.empty();
    }

    private Optional<JsonNode> findItemStruct(JsonNode root, String expectedVideoId) {
        for (var candidate : directCandidates(root, expectedVideoId)) {
            if (isItemStruct(candidate)) {
                return Optional.of(candidate);
            }
        }

        var nestedItemStruct = findFieldRecursive(root, "itemStruct");
        if (nestedItemStruct.filter(this::isItemStruct).isPresent()) {
            return nestedItemStruct.filter(this::isItemStruct);
        }

        return findObjectWithVideo(root);
    }

    private List<JsonNode> directCandidates(JsonNode root, String expectedVideoId) {
        var candidates = new ArrayList<JsonNode>();
        addIfPresent(candidates, at(root, "__DEFAULT_SCOPE__", "webapp.video-detail", "itemInfo", "itemStruct"));
        addIfPresent(candidates, at(root, "webapp.video-detail", "itemInfo", "itemStruct"));
        addIfPresent(candidates, at(root, "props", "pageProps", "itemInfo", "itemStruct"));
        addIfPresent(candidates, at(root, "props", "pageProps", "itemStruct"));

        var itemModule = at(root, "ItemModule");
        if (expectedVideoId != null && itemModule.has(expectedVideoId)) {
            addIfPresent(candidates, itemModule.get(expectedVideoId));
        }
        if (itemModule.isObject()) {
            itemModule.elements().forEachRemaining(candidates::add);
        }
        return candidates;
    }

    private Optional<String> detectStatusFailure(JsonNode root) {
        var statusMessage = firstText(root, List.of(
                List.of("__DEFAULT_SCOPE__", "webapp.video-detail", "statusMsg"),
                List.of("webapp.video-detail", "statusMsg"),
                List.of("VideoPage", "statusMsg"),
                List.of("props", "pageProps", "statusMsg")
        )).orElse("");

        var statusCode = firstInt(root, List.of(
                List.of("__DEFAULT_SCOPE__", "webapp.video-detail", "statusCode"),
                List.of("webapp.video-detail", "statusCode"),
                List.of("VideoPage", "statusCode"),
                List.of("props", "pageProps", "statusCode")
        )).orElse(null);

        if (statusCode == null || statusCode == 0) {
            return Optional.empty();
        }

        var normalized = statusMessage.toLowerCase(Locale.ROOT);
        if (normalized.contains("private") || normalized.contains("friends")) {
            return Optional.of("TikTok video is private or restricted");
        }
        if (normalized.contains("removed") || normalized.contains("unavailable") || normalized.contains("exist")) {
            return Optional.of("TikTok video is unavailable or removed");
        }
        return Optional.of("TikTok video is unavailable (statusCode=" + statusCode + ")");
    }

    private List<String> selectVideoUrls(JsonNode itemStruct) {
        var video = at(itemStruct, "video");
        if (!video.isObject()) {
            return List.of();
        }

        var hasWatermark = booleanValue(video);
        var width = intValue(video, "width");
        var height = intValue(video, "height");
        var candidates = new LinkedHashMap<String, VideoCandidate>();
        var baseQualityRank = qualityRank(width, height, null);

        addCandidate(candidates, textValue(video, "playAddr").orElse(null), true, width, height, 0, 50, baseQualityRank);
        addCandidate(candidates, textValue(video, "playAddrH264").orElse(null), true, width, height, 0, 49, baseQualityRank);
        addCandidate(candidates, textValue(video, "playAddrBytevc1").orElse(null), true, width, height, 0, 48, baseQualityRank);
        addCandidate(candidates, textValue(video, "downloadAddr").orElse(null), !hasWatermark, width, height, 0, hasWatermark ? 10 : 40, baseQualityRank);

        addAddressCandidates(candidates, at(video, "play_addr"), true, width, height, 0, 50);
        addAddressCandidates(candidates, at(video, "play_addr_h264"), true, width, height, 0, 49);
        addAddressCandidates(candidates, at(video, "play_addr_bytevc1"), true, width, height, 0, 48);
        addAddressCandidates(candidates, at(video, "download_addr"), !hasWatermark, width, height, 0, hasWatermark ? 10 : 40);

        var bitrateInfo = firstArray(video);
        if (bitrateInfo != null) {
            for (var variant : bitrateInfo) {
                var playAddr = at(variant, "PlayAddr");
                if (playAddr.isMissingNode() || playAddr.isNull()) {
                    playAddr = at(variant, "play_addr");
                }

                var variantBitrate = firstInt(variant, List.of(
                        List.of("Bitrate"),
                        List.of("bitrate"),
                        List.of("bit_rate")
                )).orElse(0);
                var variantWidth = firstInt(variant, List.of(
                        List.of("PlayAddr", "Width"),
                        List.of("play_addr", "width")
                )).orElse(width);
                var variantHeight = firstInt(variant, List.of(
                        List.of("PlayAddr", "Height"),
                        List.of("play_addr", "height")
                )).orElse(height);

                addAddressCandidates(candidates, playAddr, true, variantWidth, variantHeight, variantBitrate, 60);
            }
        }

        return candidates.values().stream()
                .sorted(candidateComparator().reversed())
                .map(VideoCandidate::url)
                .distinct()
                .toList();
    }

    private void addAddressCandidates(Map<String, VideoCandidate> candidates, JsonNode address, boolean noWatermark,
                                      int fallbackWidth, int fallbackHeight, int fallbackBitrate, int sourcePriority) {
        if (address == null || address.isMissingNode() || address.isNull()) {
            return;
        }

        var width = intValue(address, "width", fallbackWidth);
        width = intValue(address, "Width", width);

        var height = intValue(address, "height", fallbackHeight);
        height = intValue(address, "Height", height);

        var urlKey = textValue(address, "url_key")
                .or(() -> textValue(address, "urlKey"))
                .or(() -> textValue(address, "UrlKey"))
                .orElse(null);
        var parsedUrlKey = parseUrlKey(urlKey);
        int bitrate = parsedUrlKey.map(UrlKeyMetadata::bitrate).filter(value -> value > 0).orElse(fallbackBitrate);
        var qualityRank = qualityRank(width, height, parsedUrlKey.map(UrlKeyMetadata::resolution).orElse(null));

        addUrlListCandidates(candidates, at(address, "UrlList"), noWatermark, width, height, bitrate, sourcePriority, qualityRank);
        addUrlListCandidates(candidates, at(address, "url_list"), noWatermark, width, height, bitrate, sourcePriority, qualityRank);
    }

    private void addUrlListCandidates(Map<String, VideoCandidate> candidates, JsonNode urlList, boolean noWatermark,
                                      int width, int height, int bitrate, int sourcePriority, int qualityRank) {
        if (urlList == null || !urlList.isArray()) {
            return;
        }
        for (var entry : urlList) {
            if (entry.isTextual()) {
                addCandidate(candidates, entry.asText(), noWatermark, width, height, bitrate, sourcePriority, qualityRank);
            }
        }
    }

    private void addCandidate(Map<String, VideoCandidate> candidates, String rawUrl, boolean noWatermark,
                              int width, int height, int bitrate, int sourcePriority, int qualityRank) {
        normalizeMediaUrl(rawUrl).ifPresent(url -> candidates.merge(
                url,
                new VideoCandidate(url, noWatermark, width, height, bitrate, sourcePriority, qualityRank),
                this::betterCandidate
        ));
    }

    private VideoCandidate betterCandidate(VideoCandidate left, VideoCandidate right) {
        var comparator = candidateComparator();
        return comparator.compare(left, right) >= 0 ? left : right;
    }

    private Comparator<VideoCandidate> candidateComparator() {
        return Comparator
                .comparing(VideoCandidate::noWatermark)
                .thenComparingInt(VideoCandidate::qualityRank)
                .thenComparingInt(VideoCandidate::height)
                .thenComparingInt(VideoCandidate::width)
                .thenComparingInt(VideoCandidate::bitrate)
                .thenComparingInt(VideoCandidate::sourcePriority);
    }

    private Optional<UrlKeyMetadata> parseUrlKey(String urlKey) {
        if (urlKey == null || urlKey.isBlank()) {
            return Optional.empty();
        }

        var matcher = URL_KEY_PATTERN.matcher(urlKey);
        if (!matcher.find()) {
            return Optional.empty();
        }

        var resolution = matcher.group("resolution");
        var bitrate = parsePositiveInt(matcher.group("bitrate")).orElse(0);
        return Optional.of(new UrlKeyMetadata(resolution, bitrate, qualityRank(0, 0, resolution)));
    }

    private int qualityRank(int width, int height, String resolution) {
        var resolutionRank = qualityRankForLabel(resolution);
        if (resolutionRank >= 0) {
            return resolutionRank;
        }

        var dimension = effectiveQualityDimension(width, height);
        if (dimension >= 1080) {
            return qualityRankForLabel("1080p");
        }
        if (dimension >= 720) {
            return qualityRankForLabel("720p");
        }
        if (dimension >= 540) {
            return qualityRankForLabel("540p");
        }
        if (dimension >= 360) {
            return qualityRankForLabel("360p");
        }
        return -1;
    }

    private int effectiveQualityDimension(int width, int height) {
        if (width > 0 && height > 0) {
            return Math.min(width, height);
        }
        return Math.max(width, height);
    }

    private int qualityRankForLabel(String resolution) {
        if (resolution == null || resolution.isBlank()) {
            return -1;
        }
        return QUALITY_ORDER.indexOf(resolution.toLowerCase(Locale.ROOT));
    }

    private Optional<Integer> parsePositiveInt(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private Optional<String> normalizeMediaUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return Optional.empty();
        }
        var normalized = rawUrl.trim();
        if (normalized.startsWith("//")) {
            normalized = "https:" + normalized;
        }
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            return Optional.of(normalized);
        }
        return Optional.empty();
    }

    private Optional<URI> extractCanonicalUri(String html) {
        for (var pattern : List.of(CANONICAL_LINK_PATTERN, OG_URL_PATTERN)) {
            var matcher = pattern.matcher(html);
            if (matcher.find()) {
                try {
                    return Optional.of(new URI(unescapeBasicHtml(matcher.group(1))));
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

    private boolean isItemStruct(JsonNode node) {
        return node != null && node.isObject() && at(node, "video").isObject();
    }

    private Optional<JsonNode> findObjectWithVideo(JsonNode root) {
        if (isItemStruct(root)) {
            return Optional.of(root);
        }
        if (root == null || root.isValueNode()) {
            return Optional.empty();
        }
        if (root.isArray()) {
            for (var child : root) {
                var found = findObjectWithVideo(child);
                if (found.isPresent()) {
                    return found;
                }
            }
        } else {
            for (var entry : root.properties()) {
                var found = findObjectWithVideo(entry.getValue());
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<JsonNode> findFieldRecursive(JsonNode root, String fieldName) {
        if (root == null || root.isValueNode()) {
            return Optional.empty();
        }
        if (root.isObject() && root.has(fieldName)) {
            return Optional.of(root.get(fieldName));
        }
        Iterable<JsonNode> children = root::elements;
        for (JsonNode child : children) {
            var found = findFieldRecursive(child, fieldName);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private JsonNode at(JsonNode root, String... path) {
        var current = root;
        for (var segment : path) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return MissingJsonNodeHolder.INSTANCE;
            }
            current = current.path(segment);
        }
        return current == null ? MissingJsonNodeHolder.INSTANCE : current;
    }

    private void addIfPresent(List<JsonNode> values, JsonNode node) {
        if (node != null && !node.isMissingNode() && !node.isNull()) {
            values.add(node);
        }
    }

    private Optional<Integer> firstInt(JsonNode root, List<List<String>> paths) {
        for (var path : paths) {
            var node = at(root, path.toArray(String[]::new));
            if (node.isIntegralNumber()) {
                return Optional.of(node.asInt());
            }
            if (node.isTextual()) {
                try {
                    return Optional.of(Integer.parseInt(node.asText()));
                } catch (NumberFormatException ignored) {
                    // try next path
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> firstText(JsonNode root, List<List<String>> paths) {
        for (var path : paths) {
            var node = at(root, path.toArray(String[]::new));
            if (node.isTextual() && !node.asText().isBlank()) {
                return Optional.of(node.asText());
            }
        }
        return Optional.empty();
    }

    private JsonNode firstArray(JsonNode root) {
        for (var fieldName : new String[]{"bitrateInfo", "bit_rate", "bitrate_info"}) {
            var node = at(root, fieldName);
            if (node.isArray()) {
                return node;
            }
        }
        return null;
    }

    private Optional<String> textValue(JsonNode root, String fieldName) {
        var node = at(root, fieldName);
        return node.isTextual() && !node.asText().isBlank() ? Optional.of(node.asText()) : Optional.empty();
    }

    private boolean booleanValue(JsonNode root) {
        for (var fieldName : new String[]{"hasWatermark", "has_watermark"}) {
            var node = at(root, fieldName);
            if (node.isBoolean()) {
                return node.asBoolean();
            }
            if (node.isTextual()) {
                return Boolean.parseBoolean(node.asText());
            }
        }
        return false;
    }

    private int intValue(JsonNode root, String fieldName) {
        return intValue(root, fieldName, 0);
    }

    private int intValue(JsonNode root, String fieldName, int fallback) {
        var node = at(root, fieldName);
        if (node.isIntegralNumber()) {
            return node.asInt();
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String unescapeBasicHtml(String value) {
        return value
                .replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    interface Transport {
        Response<String> getText(URI uri, Map<String, String> headers);

        Path downloadToTemp(URI uri, Map<String, String> headers, String suffix);
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get();
    }

    record Response<T>(URI uri, int statusCode, T body) {
    }

    private record VideoCandidate(String url, boolean noWatermark, int width, int height, int bitrate,
                                  int sourcePriority, int qualityRank) {
    }

    private record UrlKeyMetadata(String resolution, int bitrate, int qualityRank) {
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

    private record HttpTransport(Config config, HttpClient client) implements Transport {

        @Override
        public Response<String> getText(URI uri, Map<String, String> headers) {
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
                throw new TikTokDownloadException("Download failure: unable to stream video to disk", e);
            }
        }

        private <T> Response<T> send(URI uri, Map<String, String> headers, HttpResponse.BodyHandler<T> bodyHandler,
                                     String action) {
            var requestBuilder = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(config.readTimeout());
            headers.forEach(requestBuilder::header);

            try {
                var response = client.send(requestBuilder.build(), bodyHandler);
                var statusCode = response.statusCode();
                if (statusCode >= 200 && statusCode < 300) {
                    return new Response<>(response.uri(), statusCode, response.body());
                }
                throw new TikTokDownloadException(statusMessage(action, statusCode));
            } catch (IOException e) {
                throw new TikTokDownloadException(action + " failed: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TikTokDownloadException(action + " interrupted", e);
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

    private static final class MissingJsonNodeHolder {
        private static final JsonNode INSTANCE = MissingNode.getInstance();
    }
}