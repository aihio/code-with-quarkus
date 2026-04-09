package io.github.aihio.bot.cache;

import java.util.List;

/**
 * Immutable cache entry for a TikTok post.
 * Holds Telegram file_ids for all media — video or gallery photos, plus audio.
 */
public record CachedMedia(
        boolean isGallery,
        List<String> mediaFileIds,  // [videoFileId] for video, [photo0..photoN] for gallery
        String audioFileId          // null if audio was not available when cached
) {
    public CachedMedia {
        mediaFileIds = mediaFileIds == null ? List.of() : List.copyOf(mediaFileIds);
    }
}

