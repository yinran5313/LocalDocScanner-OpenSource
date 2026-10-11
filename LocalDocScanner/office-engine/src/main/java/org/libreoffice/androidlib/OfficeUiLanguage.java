package org.libreoffice.androidlib;

import java.util.Locale;
import java.io.InputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Match the pinned browser bundle's Chinese locale aliases, including Android script tags. */
public final class OfficeUiLanguage {
    private OfficeUiLanguage() {}
    public static String normalize(Locale locale) {
        if (!"zh".equals(locale.getLanguage())) return locale.toLanguageTag();
        String script = locale.getScript();
        if ("Hant".equals(script)) return "zh-TW";
        if ("Hans".equals(script)) return "zh-CN";
        String region = locale.getCountry();
        return ("TW".equals(region) || "HK".equals(region) || "MO".equals(region)) ? "zh-TW" : "zh-CN";
    }
    public static String attachTranslations(String html) {
        String marker = "<script src=\"bundle.js\" defer></script>";
        if (!html.contains(marker)) throw new IllegalStateException("Office browser entry changed; localization insertion unavailable");
        return html.replace(marker, marker + "\n<script src=\"file:///android_asset/localdoc-office-ui.js\" defer></script>"
            + "\n<script src=\"file:///android_asset/localdoc-office-zh-data.js\" defer></script>"
            + "\n<script src=\"file:///android_asset/localdoc-office-zh.js\" defer></script>");
    }
    /** Asset URLs bypass shouldInterceptRequest; load this HTML explicitly with its original base URL. */
    public static String readEntry(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
        return attachTranslations(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }
}
