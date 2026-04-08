package io.github.aihio.bot.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

final class TikTokHydrationParser {
    private static final Set<String> JSON_SCRIPT_IDS = Set.of(
            "SIGI_STATE",
            "sigi-persisted-data",
            "__UNIVERSAL_DATA_FOR_REHYDRATION__",
            "__NEXT_DATA__",
            "__FRONTITY_CONNECT_STATE__"
    );

    private final ObjectMapper objectMapper;

    TikTokHydrationParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JsonNode extractItemStruct(String html, String expectedVideoId) {
        return locateItemStruct(extractJsonRoots(html), expectedVideoId);
    }

    private JsonNode locateItemStruct(List<JsonNode> roots, String expectedVideoId) {
        for (var root : roots) {
            detectStatusFailure(root).ifPresent(message -> {
                throw new TikTokDownloader.TikTokDownloadException(message);
            });

            var candidate = findItemStruct(root, expectedVideoId);
            if (candidate.isPresent()) {
                return candidate.get();
            }
        }
        throw new TikTokDownloader.TikTokDownloadException("Extraction failure: unable to locate embedded TikTok video data");
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

        for (var attempt : List.of(candidate, TikTokHtml.unescapeBasicHtml(candidate))) {
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
        for (var child : children) {
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


    private static final class MissingJsonNodeHolder {
        private static final JsonNode INSTANCE = MissingNode.getInstance();
    }
}


