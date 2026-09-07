package com.liskovsoft.youtubeapi.videoinfo.models;

import androidx.annotation.Nullable;

import java.text.Normalizer;
import java.util.Locale;

/** Classifies YouTube anti-bot player responses without depending on the user's locale. */
public final class BotCheckDetector {
    private static final String STATUS_LOGIN_REQUIRED = "LOGIN_REQUIRED";
    private static final String STATUS_UNPLAYABLE = "UNPLAYABLE";
    /**
     * Localized forms of the "reload the page" verdict YouTube returns to authenticated TVHTML5
     * /player requests (see {@link #isReloadPageVerdict}). Normalized the same way as every other
     * reason here, so accents and whitespace do not matter.
     */
    private static final String[] RELOAD_PAGE_REASONS = {
            "the page needs to be reloaded",            // en
            "es necesario volver a cargar la pagina",   // es
            "la page doit etre rechargee",              // fr
            "die seite muss neu geladen werden",        // de
            "e necessario ricaricare la pagina",        // it
            "a pagina precisa ser recarregada",         // pt-BR
    };

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

    /**
     * The verdict an authenticated TVHTML5 /player request gets while the account-bearing TV route
     * is broken server-side: HTTP 200, {@code UNPLAYABLE}, zero formats, reason "The page needs to
     * be reloaded." Observed on the Pixel 9 on 2026-09-07 for both TV_DOWNGRADED (TVHTML5 5.x) and
     * TV (TVHTML5 7.x), on every video tried, in Spanish. yt-dlp carries the same report
     * (yt-dlp/yt-dlp#17389) and demoted the client on 2026-08-18 in commit 5d5b634, which moved
     * {@code web_embedded} ahead of {@code tv_downgraded} in {@code _DEFAULT_AUTHED_CLIENTS}.
     * <p>
     * DIAGNOSTIC ONLY. The list above cannot cover every UI language, so routing must never depend
     * on a hit here - {@code VideoInfoService.isAuthRouteReloadVerdict} keys the actual quarantine
     * off the locale-independent SHAPE of the response and uses this purely to say why in the log.
     */
    public static boolean isReloadPageVerdict(@Nullable String status, @Nullable String reason) {
        if (!STATUS_UNPLAYABLE.equals(status) || reason == null) {
            return false;
        }

        String normalized = normalize(reason);
        for (String known : RELOAD_PAGE_REASONS) {
            if (normalized.contains(known)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
