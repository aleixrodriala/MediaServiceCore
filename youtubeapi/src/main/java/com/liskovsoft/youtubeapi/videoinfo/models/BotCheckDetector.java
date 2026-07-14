package com.liskovsoft.youtubeapi.videoinfo.models;

import androidx.annotation.Nullable;

import java.text.Normalizer;
import java.util.Locale;

/** Classifies YouTube anti-bot player responses without depending on the user's locale. */
public final class BotCheckDetector {
    private static final String STATUS_LOGIN_REQUIRED = "LOGIN_REQUIRED";

    private BotCheckDetector() {
    }

    public static boolean isExplicitBotCheck(@Nullable String status, @Nullable String reason) {
        if (!STATUS_LOGIN_REQUIRED.equals(status) || reason == null) {
            return false;
        }

        String normalized = normalize(reason);
        return normalized.contains("bot")
                || normalized.contains("robot")
                || normalized.contains("captcha");
    }

    /**
     * A localized anti-bot message may not contain a Latin keyword. Two different clients
     * returning the same LOGIN_REQUIRED reason is the safe fallback signal: an age gate normally
     * changes outcome on the embedded client, while a blocked guest/IP session does not.
     */
    public static boolean isRepeatedLoginRequired(@Nullable String firstStatus,
            @Nullable String firstReason, @Nullable String currentStatus,
            @Nullable String currentReason) {
        if (!STATUS_LOGIN_REQUIRED.equals(firstStatus)
                || !STATUS_LOGIN_REQUIRED.equals(currentStatus)
                || firstReason == null || currentReason == null) {
            return false;
        }

        return normalize(firstReason).equals(normalize(currentReason));
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
