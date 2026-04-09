package io.github.aihio.bot.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.util.*;
import java.util.regex.Pattern;

final class TikTokVideoSelector {
    private static final List<String> QUALITY_ORDER = List.of("360p", "540p", "720p", "1080p");
    private static final Pattern URL_KEY_PATTERN = Pattern.compile("v[^_]+_(?<codec>[^_]+)_(?<resolution>\\d+p)_(?<bitrate>\\d+)");

    List<String> selectVideoUrls(JsonNode itemStruct) {
        return selectVideo(itemStruct).urls();
    }

    VideoSelection selectVideo(JsonNode itemStruct) {
        var video = at(itemStruct, "video");
        if (!video.isObject()) {
            return new VideoSelection(List.of(), 0, 0);
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

        var sorted = candidates.values().stream()
                .sorted(candidateComparator().reversed())
                .toList();

        var topWidth = sorted.isEmpty() ? 0 : sorted.getFirst().width();
        var topHeight = sorted.isEmpty() ? 0 : sorted.getFirst().height();
        var urls = sorted.stream().map(VideoCandidate::url).distinct().toList();

        return new VideoSelection(urls, topWidth, topHeight);
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
        int bitrate = parsedUrlKey.map(UrlKeyMetadata::bitrate).filter(value -> value > 0).orElse(fallbackBitrate);
        var qualityRank = qualityRank(width, height, parsedUrlKey.map(UrlKeyMetadata::resolution).orElse(null));
        int codecRank = parsedUrlKey.map(UrlKeyMetadata::codec).map(this::codecRank)
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

    private record VideoCandidate(String url, boolean noWatermark, int width, int height, int bitrate,
                                  int sourcePriority, int qualityRank, int codecRank) {
    }

    private record UrlKeyMetadata(String codec, String resolution, int bitrate, int qualityRank) {
    }

    record VideoSelection(List<String> urls, int width, int height) {
    }

    private static final class MissingJsonNodeHolder {
        private static final JsonNode INSTANCE = MissingNode.getInstance();
    }
}


