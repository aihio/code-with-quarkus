package io.github.aihio.bot.tiktok;

final class TikTokHtml {
    private TikTokHtml() {
    }

    static String unescapeBasicHtml(String value) {
        return value
                .replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }
}


