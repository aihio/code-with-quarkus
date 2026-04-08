package io.github.aihio.bot.tiktok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class TikTokMediaUrlExtractor {

    List<String> selectAudioUrls(JsonNode itemStruct) {
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

    List<String> selectPhotoUrls(JsonNode itemStruct) {
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

    private Optional<String> textValue(JsonNode root, String fieldName) {
        var node = at(root, fieldName);
        return node.isTextual() && !node.asText().isBlank() ? Optional.of(node.asText()) : Optional.empty();
    }

    private static final class MissingJsonNodeHolder {
        private static final JsonNode INSTANCE = MissingNode.getInstance();
    }
}


