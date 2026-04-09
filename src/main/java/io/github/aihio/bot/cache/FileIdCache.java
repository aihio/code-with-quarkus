package io.github.aihio.bot.cache;

import java.util.Optional;

/**
 * Contract for TikTok post ID → cached Telegram media (video/gallery + audio) mapping.
 */
public interface FileIdCache {
    Optional<CachedMedia> getCachedMedia(String tiktokPostId);

    void cacheMedia(String tiktokPostId, CachedMedia media);

    void invalidateCachedMedia(String tiktokPostId);
}



