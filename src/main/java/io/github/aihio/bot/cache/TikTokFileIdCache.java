package io.github.aihio.bot.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * SQLite-backed implementation of FileIdCache using quarkus-jdbc-sqlite4j.
 * Persists TikTok post ID → {video/gallery file_ids + audio file_id} for instant repeat delivery.
 */
@ApplicationScoped
public class TikTokFileIdCache implements FileIdCache {
    private static final Logger log = Logger.getLogger(TikTokFileIdCache.class.getName());
    private static final String TABLE_NAME = "tiktok_file_cache";

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    @Inject
    public TikTokFileIdCache(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        initializeDatabase();
    }

    private void initializeDatabase() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {

            // Recreate with full schema (drops old single-column schema)
            stmt.execute("DROP TABLE IF EXISTS " + TABLE_NAME);
            stmt.execute("CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (" +
                    "tiktok_post_id TEXT PRIMARY KEY, " +
                    "is_gallery INTEGER NOT NULL DEFAULT 0, " +
                    "media_file_ids TEXT NOT NULL, " +
                    "audio_file_id TEXT, " +
                    "cached_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");

        } catch (SQLException e) {
            log.warning("Failed to initialize cache database: " + e.getMessage());
        }
    }

    @Override
    public Optional<CachedMedia> getCachedMedia(String tiktokPostId) {
        if (tiktokPostId == null || tiktokPostId.isBlank()) {
            return Optional.empty();
        }

        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT is_gallery, media_file_ids, audio_file_id FROM " + TABLE_NAME
                             + " WHERE tiktok_post_id = ?")) {

            stmt.setString(1, tiktokPostId);
            var rs = stmt.executeQuery();

            if (rs.next()) {
                var isGallery = rs.getInt("is_gallery") == 1;
                var mediaFileIds = fromJson(rs.getString("media_file_ids"));
                var audioFileId = rs.getString("audio_file_id");
                return Optional.of(new CachedMedia(isGallery, mediaFileIds, audioFileId));
            }

            return Optional.empty();

        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    @Override
    public void cacheMedia(String tiktokPostId, CachedMedia media) {
        if (tiktokPostId == null || tiktokPostId.isBlank()
                || media == null || media.mediaFileIds().isEmpty()) {
            return;
        }

        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "INSERT OR REPLACE INTO " + TABLE_NAME
                             + " (tiktok_post_id, is_gallery, media_file_ids, audio_file_id) VALUES (?, ?, ?, ?)")) {

            stmt.setString(1, tiktokPostId);
            stmt.setInt(2, media.isGallery() ? 1 : 0);
            stmt.setString(3, toJson(media.mediaFileIds()));
            stmt.setString(4, media.audioFileId());
            stmt.executeUpdate();


        } catch (SQLException e) {
            log.warning("Failed to cache media for " + tiktokPostId + ": " + e.getMessage());
        }
    }

    @Override
    public void invalidateCachedMedia(String tiktokPostId) {
        if (tiktokPostId == null || tiktokPostId.isBlank()) {
            return;
        }

        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "DELETE FROM " + TABLE_NAME + " WHERE tiktok_post_id = ?")) {

            stmt.setString(1, tiktokPostId);
            if (stmt.executeUpdate() > 0) {
                log.info("Invalidated cache for post=" + tiktokPostId);
            }

        } catch (SQLException e) {
            log.warning("Failed to invalidate cache for " + tiktokPostId + ": " + e.getMessage());
        }
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}


