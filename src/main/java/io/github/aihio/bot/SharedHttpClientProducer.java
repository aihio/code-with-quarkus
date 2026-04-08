package io.github.aihio.bot;

import io.github.aihio.bot.tiktok.TikTokDownloaderRuntimeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.net.CookieManager;
import java.net.http.HttpClient;

@ApplicationScoped
public class SharedHttpClientProducer {

    @Produces
    @ApplicationScoped
    HttpClient httpClient(TikTokDownloaderRuntimeConfig runtimeConfig) {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(runtimeConfig.connectTimeout())
                .cookieHandler(new CookieManager())
                .build();
    }
}

