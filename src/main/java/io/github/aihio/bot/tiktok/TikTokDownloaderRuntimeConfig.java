package io.github.aihio.bot.tiktok;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;

@ConfigMapping(prefix = "tiktok.downloader")
public interface TikTokDownloaderRuntimeConfig {
    @WithDefault("10S")
    Duration connectTimeout();

    @WithDefault("30S")
    Duration readTimeout();

    @WithDefault("2")
    int retries();

    @WithDefault("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36")
    String userAgent();
}


