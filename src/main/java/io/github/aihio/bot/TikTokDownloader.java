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
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("/(?:video|photo)/(\\d+)");
    private static final Pattern URL_KEY_PATTERN = Pattern.compile("v[^_]+_(?<codec>[^_]+)_(?<resolution>\\d+p)_(?<bitrate>\\d+)");
    private static final Set<String> JSON_SCRIPT_IDS = Set.of(
            "SIGI_STATE",
            "sigi-persisted-data",
            "__UNIVERSAL_DATA_FOR_REHYDRATION__",
            "__NEXT_DATA__",
            "__FRONTITY_CONNECT_STATE__"
    );

    private static final String AUDIO_SUFFIX = ".mp3";
    private static final String VIDEO_SUFFIX = ".mp4";
    private static final String PHOTO_SUFFIX = ".jpg";

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
                        () -> transport.downloadToTemp(URI.create(videoUrl), downloadHeaders(), VIDEO_SUFFIX));
            } catch (TikTokDownloadException e) {
                lastFailure = e;
            }
        }

        throw lastFailure;
    }

    public DownloadedMedia downloadMedia(String url) {
        var inputUri = parseTikTokUri(url);
        var page = withRetries("fetch TikTok page", () -> transport.getText(inputUri, pageHeaders()));

        var pageUri = extractCanonicalUri(page.body())
                .filter(uri -> isTikTokHost(uri.getHost()))
                .orElse(page.uri());

        var videoId = extractVideoId(pageUri).orElse(null);
        var photoRoute = isPhotoRoute(pageUri);
        var itemStruct = extractItemStructWithEmbedFallback(page.body(), videoId, photoRoute);
        var audioPath = downloadAudio(itemStruct);
        var photoUrls = selectPhotoUrls(itemStruct);
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

        var videoUrls = selectVideoUrls(itemStruct);
        if (videoUrls.isEmpty()) {
            deleteQuietly(audioPath);
            throw new TikTokDownloadException("Extraction failure: unable to find a playable TikTok video URL");
        }

        TikTokDownloadException lastFailure = null;
        for (var videoUrl : videoUrls) {
            try {
                var videoPath = withRetries("download TikTok video",
                        () -> transport.downloadToTemp(URI.create(videoUrl), downloadHeaders(), VIDEO_SUFFIX));
                return new DownloadedMedia(videoPath, List.of(), audioPath);
            } catch (TikTokDownloadException e) {
                lastFailure = e;
            }
        }

        deleteQuietly(audioPath);
        throw lastFailure;
    }

    private Path downloadAudio(JsonNode itemStruct) {
        var audioUrls = selectAudioUrls(itemStruct);
        if (audioUrls.isEmpty()) {
            throw new TikTokDownloadException("Extraction failure: unable to find TikTok audio track");
        }

        TikTokDownloadException lastFailure = null;
        for (var audioUrl : audioUrls) {
            try {
                return withRetries("download TikTok audio",
                        () -> transport.downloadToTemp(URI.create(audioUrl), downloadHeaders(), AUDIO_SUFFIX));
            } catch (TikTokDownloadException e) {
                lastFailure = e;
            }
        }

        throw lastFailure;
    }

    private JsonNode extractItemStructWithEmbedFallback(String html, String expectedVideoId, boolean photoRoute) {
        try {
            return extractItemStruct(html, expectedVideoId);
        } catch (TikTokDownloadException originalFailure) {
            if (!photoRoute || expectedVideoId == null || expectedVideoId.isBlank()) {
                throw originalFailure;
            }

            var embedUri = URI.create("https://www.tiktok.com/embed/v2/" + expectedVideoId);
            try {
                var embedPage = withRetries("fetch TikTok embed page", () -> transport.getText(embedUri, pageHeaders()));
                return extractItemStruct(embedPage.body(), expectedVideoId);
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

    private List<String> selectAudioUrls(JsonNode itemStruct) {
        var music = at(itemStruct, "music");
        if (!music.isObject()) {
            music = at(itemStruct, "musicInfos");
        }
        if (!music.isObject()) {
            return List.of();
        }

        var candidates = new LinkedHashSet<String>();
        addNormalizedUrl(candidates, textValue(music, "playUrl").orElse(null));
        addNormalizedUrl(candidates, textValue(music, "play_url").orElse(null));
        addNormalizedUrl(candidates, textValue(music, "playUrlHq").orElse(null));
        addNormalizedUrl(candidates, textValue(music, "play_url_hq").orElse(null));
        addNormalizedUrl(candidates, textValue(at(music, "playUrl"), "uri").orElse(null));
        addNormalizedUrl(candidates, textValue(at(music, "play_url"), "uri").orElse(null));
        addUrlArray(candidates, at(music, "playUrl"));
        addUrlArray(candidates, at(music, "play_url"));
        addUrlArray(candidates, at(music, "playUrl", "urlList"));
        addUrlArray(candidates, at(music, "play_url", "url_list"));
        addUrlArray(candidates, at(music, "playUrl", "UrlList"));
        addUrlArray(candidates, at(music, "play_url", "UrlList"));
        return candidates.stream().toList();
    }

    private List<String> selectPhotoUrls(JsonNode itemStruct) {
        var imagePost = firstImagePostNode(itemStruct);
        if (imagePost == null) {
            return List.of();
        }

        var urls = new LinkedHashSet<String>();
        var images = at(imagePost, "images");
        if (images.isArray() && !images.isEmpty()) {
            for (var image : images) {
                addPreferredImageUrl(urls, image);
            }
            return urls.stream().toList();
        }

        var displayImages = at(imagePost, "displayImages");
        if (displayImages.isArray()) {
            for (var image : displayImages) {
                addPreferredDisplayImageUrl(urls, image);
            }
        }

        return urls.stream().toList();
    }

    private void addPreferredImageUrl(Set<String> urls, JsonNode image) {
        addPreferredUrl(urls,
                at(image, "imageURL", "urlList"),
                at(image, "imageURL", "url_list"),
                at(image, "displayImage", "urlList"),
                at(image, "displayImage", "url_list"),
                at(image, "display_image", "url_list"),
                at(image, "imageUrl", "urlList"),
                at(image, "imageUrl", "url_list"),
                at(image, "image_url", "url_list"),
                at(image, "ownerWatermarkImage", "urlList"),
                at(image, "ownerWatermarkImage", "url_list"),
                at(image, "urlList"),
                at(image, "url_list")
        );
    }

    private void addPreferredDisplayImageUrl(Set<String> urls, JsonNode image) {
        addPreferredUrl(urls,
                at(image, "urlList"),
                at(image, "url_list"),
                at(image, "displayImage", "urlList"),
                at(image, "displayImage", "url_list"),
                at(image, "imageURL", "urlList"),
                at(image, "imageURL", "url_list")
        );
    }

    private void addPreferredUrl(Set<String> urls, JsonNode... candidates) {
        for (var candidate : candidates) {
            var preferred = firstNormalizedUrl(candidate);
            if (preferred.isPresent()) {
                urls.add(preferred.get());
                return;
            }
        }
    }

    private Optional<String> firstNormalizedUrl(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Optional.empty();
        }
        for (var entry : node) {
            if (!entry.isTextual()) {
                continue;
            }
            var normalized = normalizeMediaUrl(entry.asText());
            if (normalized.isPresent()) {
                return normalized;
            }
        }
        return Optional.empty();
    }

    private JsonNode firstImagePostNode(JsonNode itemStruct) {
        for (var path : List.of(
                new String[]{"imagePost"},
                new String[]{"image_post_info"},
                new String[]{"imagePostInfo"},
                new String[]{"imagePostInfo", "imagePost"},
                new String[]{}
        )) {
            var candidate = at(itemStruct, path);
            if (candidate.isObject() && hasPhotoMedia(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean hasPhotoMedia(JsonNode node) {
        return containsPhotoImages(node) || containsDisplayImages(node);
    }

    private boolean containsPhotoImages(JsonNode node) {
        var images = at(node, "images");
        if (!images.isArray() || images.isEmpty()) {
            return false;
        }

        for (var image : images) {
            if (at(image, "imageURL", "urlList").isArray()
                    || at(image, "imageURL", "url_list").isArray()
                    || at(image, "displayImage", "urlList").isArray()
                    || at(image, "displayImage", "url_list").isArray()
                    || at(image, "ownerWatermarkImage", "urlList").isArray()
                    || at(image, "ownerWatermarkImage", "url_list").isArray()
                    || at(image, "imageUrl", "urlList").isArray()
                    || at(image, "imageUrl", "url_list").isArray()
                    || at(image, "image_url", "url_list").isArray()
                    || at(image, "urlList").isArray()
                    || at(image, "url_list").isArray()) {
                return true;
            }
        }
        return false;
    }

    private boolean containsDisplayImages(JsonNode node) {
        var displayImages = at(node, "displayImages");
        if (!displayImages.isArray() || displayImages.isEmpty()) {
            return false;
        }
        for (var image : displayImages) {
            if (at(image, "urlList").isArray() || at(image, "url_list").isArray()) {
                return true;
            }
        }
        return false;
    }

    private void addUrlArray(Set<String> urls, JsonNode node) {
        if (node == null || !node.isArray()) {
            return;
        }
        for (var entry : node) {
            if (entry.isTextual()) {
                addNormalizedUrl(urls, entry.asText());
            }
        }
    }

    private void addNormalizedUrl(Set<String> urls, String rawUrl) {
        normalizeMediaUrl(rawUrl).ifPresent(urls::add);
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
        if (!roots.isEmpty()) {
            return roots;
        }

        // Some TikTok pages no longer expose hydration data under stable script IDs.
        for (var scriptBody : extractAllScriptBodies(html)) {
            parseJson(scriptBody).ifPresent(roots::add);
            for (var candidateJson : extractJsonObjects(scriptBody)) {
                parseJson(candidateJson).ifPresent(roots::add);
            }
        }
        return roots;
    }

    private List<String> extractAllScriptBodies(String html) {
        var scripts = new ArrayList<String>();
        var pattern = Pattern.compile("<script[^>]*>(.*?)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        var matcher = pattern.matcher(html);
        while (matcher.find()) {
            scripts.add(matcher.group(1));
        }
        return scripts;
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
        var scriptBody = matcher.group(1);
        var parsed = parseJson(scriptBody);
        if (parsed.isPresent()) {
            return parsed;
        }
        return extractFirstJsonObject(scriptBody).flatMap(this::parseJson);
    }

    private Optional<String> extractFirstJsonObject(String scriptBody) {
        if (scriptBody == null || scriptBody.isBlank()) {
            return Optional.empty();
        }

        var start = scriptBody.indexOf('{');
        if (start < 0) {
            return Optional.empty();
        }

        var depth = 0;
        var inString = false;
        var escaped = false;
        for (var index = start; index < scriptBody.length(); index++) {
            var ch = scriptBody.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return Optional.of(scriptBody.substring(start, index + 1));
                }
            }
        }
        return Optional.empty();
    }

    private List<String> extractJsonObjects(String scriptBody) {
        var jsonObjects = new ArrayList<String>();
        if (scriptBody == null || scriptBody.isBlank()) {
            return jsonObjects;
        }

        var inString = false;
        var escaped = false;
        var depth = 0;
        var start = -1;
        for (var index = 0; index < scriptBody.length(); index++) {
            var ch = scriptBody.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                if (depth == 0) {
                    start = index;
                }
                depth++;
            } else if (ch == '}') {
                if (depth == 0) {
                    continue;
                }
                depth--;
                if (depth == 0 && start >= 0) {
                    jsonObjects.add(scriptBody.substring(start, index + 1));
                    start = -1;
                }
            }
        }
        return jsonObjects;
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
        addIfPresent(candidates, at(root, "__DEFAULT_SCOPE__", "webapp.video-detail", "itemInfo"));
        addIfPresent(candidates, at(root, "__DEFAULT_SCOPE__", "webapp.photo-detail", "itemInfo", "itemStruct"));
        addIfPresent(candidates, at(root, "__DEFAULT_SCOPE__", "webapp.photo-detail", "itemInfo"));
        addIfPresent(candidates, at(root, "webapp.video-detail", "itemInfo", "itemStruct"));
        addIfPresent(candidates, at(root, "webapp.video-detail", "itemInfo"));
        addIfPresent(candidates, at(root, "webapp.photo-detail", "itemInfo", "itemStruct"));
        addIfPresent(candidates, at(root, "webapp.photo-detail", "itemInfo"));
        addIfPresent(candidates, at(root, "props", "pageProps", "itemInfo", "itemStruct"));
        addIfPresent(candidates, at(root, "props", "pageProps", "itemInfo"));
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
                List.of("__DEFAULT_SCOPE__", "webapp.photo-detail", "statusMsg"),
                List.of("webapp.video-detail", "statusMsg"),
                List.of("webapp.photo-detail", "statusMsg"),
                List.of("VideoPage", "statusMsg"),
                List.of("props", "pageProps", "statusMsg")
        )).orElse("");

        var statusCode = firstInt(root, List.of(
                List.of("__DEFAULT_SCOPE__", "webapp.video-detail", "statusCode"),
                List.of("__DEFAULT_SCOPE__", "webapp.photo-detail", "statusCode"),
                List.of("webapp.video-detail", "statusCode"),
                List.of("webapp.photo-detail", "statusCode"),
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
        var directCodecRank = codecRankForNode(video, codecRank("h264"));

        addCandidate(candidates, textValue(video, "playAddr").orElse(null), true, width, height, 0, 50, baseQualityRank, directCodecRank);
        addCandidate(candidates, textValue(video, "playAddrH264").orElse(null), true, width, height, 0, 49, baseQualityRank, codecRank("h264"));
        addCandidate(candidates, textValue(video, "playAddrBytevc1").orElse(null), true, width, height, 0, 48, baseQualityRank, codecRank("h265"));
        addCandidate(candidates, textValue(video, "downloadAddr").orElse(null), !hasWatermark, width, height, 0, hasWatermark ? 10 : 40, baseQualityRank, codecRank("h264"));

        addAddressCandidates(candidates, at(video, "play_addr"), true, width, height, 0, 50, directCodecRank);
        addAddressCandidates(candidates, at(video, "play_addr_h264"), true, width, height, 0, 49, codecRank("h264"));
        addAddressCandidates(candidates, at(video, "play_addr_bytevc1"), true, width, height, 0, 48, codecRank("h265"));
        addAddressCandidates(candidates, at(video, "download_addr"), !hasWatermark, width, height, 0, hasWatermark ? 10 : 40, codecRank("h264"));

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
                var variantCodecRank = codecRankForNode(playAddr, codecRankForNode(variant, directCodecRank));

                addAddressCandidates(candidates, playAddr, true, variantWidth, variantHeight, variantBitrate, 60, variantCodecRank);
            }
        }

        return candidates.values().stream()
                .sorted(candidateComparator().reversed())
                .map(VideoCandidate::url)
                .distinct()
                .toList();
    }

    private void addAddressCandidates(Map<String, VideoCandidate> candidates, JsonNode address, boolean noWatermark,
                                      int fallbackWidth, int fallbackHeight, int fallbackBitrate, int sourcePriority,
                                      int fallbackCodecRank) {
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
        var bitrate = parsedUrlKey.map(UrlKeyMetadata::bitrate).filter(value -> value > 0).orElse(fallbackBitrate);
        var qualityRank = qualityRank(width, height, parsedUrlKey.map(UrlKeyMetadata::resolution).orElse(null));
        var codecRank = parsedUrlKey.map(UrlKeyMetadata::codec).map(this::codecRank)
                .orElseGet(() -> codecRankForNode(address, fallbackCodecRank));

        addUrlListCandidates(candidates, at(address, "UrlList"), noWatermark, width, height, bitrate, sourcePriority, qualityRank, codecRank);
        addUrlListCandidates(candidates, at(address, "url_list"), noWatermark, width, height, bitrate, sourcePriority, qualityRank, codecRank);
    }

    private void addUrlListCandidates(Map<String, VideoCandidate> candidates, JsonNode urlList, boolean noWatermark,
                                      int width, int height, int bitrate, int sourcePriority, int qualityRank,
                                      int codecRank) {
        if (urlList == null || !urlList.isArray()) {
            return;
        }
        for (var entry : urlList) {
            if (entry.isTextual()) {
                addCandidate(candidates, entry.asText(), noWatermark, width, height, bitrate, sourcePriority, qualityRank, codecRank);
            }
        }
    }

    private void addCandidate(Map<String, VideoCandidate> candidates, String rawUrl, boolean noWatermark,
                              int width, int height, int bitrate, int sourcePriority, int qualityRank,
                              int codecRank) {
        if (codecRank < 0) {
            return;
        }
        normalizeMediaUrl(rawUrl).ifPresent(url -> candidates.merge(
                url,
                new VideoCandidate(url, noWatermark, width, height, bitrate, sourcePriority, qualityRank, codecRank),
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
                .thenComparingInt(VideoCandidate::codecRank)
                .thenComparingInt(VideoCandidate::qualityRank)
                .thenComparingInt(VideoCandidate::bitrate)
                .thenComparingInt(VideoCandidate::height)
                .thenComparingInt(VideoCandidate::width)
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
        var codec = normalizeCodec(matcher.group("codec"));
        var bitrate = parsePositiveInt(matcher.group("bitrate")).orElse(0);
        return Optional.of(new UrlKeyMetadata(codec, resolution, bitrate, qualityRank(0, 0, resolution)));
    }

    private int codecRankForNode(JsonNode node, int fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }

        if (booleanValue(node, "is_bytevc2", "isBytevc2")) {
            return codecRank("bytevc2");
        }
        if (booleanValue(node, "is_bytevc1", "isBytevc1", "is_h265", "isH265")) {
            return codecRank("h265");
        }

        var codec = textValue(node, "codec_type")
                .or(() -> textValue(node, "CodecType"))
                .or(() -> textValue(node, "codec"))
                .or(() -> textValue(node, "Codec"))
                .or(() -> textValue(node, "vcodec"));
        return codec.map(this::codecRank).orElse(fallback);
    }

    private String normalizeCodec(String codec) {
        if (codec == null || codec.isBlank()) {
            return "";
        }
        return codec.toLowerCase(Locale.ROOT);
    }

    private int codecRank(String codec) {
        var normalized = normalizeCodec(codec);
        return switch (normalized) {
            case "bytevc2", "h266", "vvc" -> -100;
            case "h264", "avc", "avc1" -> 3;
            case "bytevc1", "h265", "hevc" -> 2;
            case "" -> 0;
            default -> 1;
        };
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
        if (node == null || !node.isObject()) {
            return false;
        }
        return at(node, "video").isObject()
                || hasPhotoMedia(at(node, "imagePost"))
                || hasPhotoMedia(at(node, "image_post_info"))
                || hasPhotoMedia(at(node, "imagePostInfo"))
                || hasPhotoMedia(node);
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
        return booleanValue(root, "hasWatermark", "has_watermark");
    }

    private boolean booleanValue(JsonNode root, String... fieldNames) {
        for (var fieldName : fieldNames) {
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

    public record DownloadedMedia(Path videoPath, List<Path> photoPaths, Path audioPath) {
        public DownloadedMedia {
            photoPaths = photoPaths == null ? List.of() : List.copyOf(photoPaths);
        }

        public boolean gallery() {
            return !photoPaths.isEmpty();
        }
    }

    private record VideoCandidate(String url, boolean noWatermark, int width, int height, int bitrate,
                                  int sourcePriority, int qualityRank, int codecRank) {
    }

    private record UrlKeyMetadata(String codec, String resolution, int bitrate, int qualityRank) {
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

