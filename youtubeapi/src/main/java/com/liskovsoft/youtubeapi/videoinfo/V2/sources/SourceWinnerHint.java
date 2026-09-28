package com.liskovsoft.youtubeapi.videoinfo.V2.sources;

import androidx.annotation.Nullable;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

/**
 * NEWTUBE(source-catalog): the persisted "last winner" hint - which source a cold start's walk
 * begins with - keyed by source id ("VISIONOS@1").
 *
 * <p>It used to be the winning AppClient's ORDINAL inside upstream's MediaServiceData
 * (setVideoInfoType), so an enum entry inserted by an upstream merge would silently re-point it to
 * another client. The id is stable, and it names a source rather than a client, so a second source
 * of one client (phase 6) cannot be confused with the first.
 *
 * <p>Migration: the legacy ordinal is read exactly once, through a table frozen at the time of the
 * move ({@link #LEGACY_ORDINALS}, never {@code AppClient.values()}), and the result is written
 * straight back, so it is never consulted again. An explicit empty value means "no hint" and stays
 * that way; an id this build does not know is no hint either.
 */
public final class SourceWinnerHint {
    /** Where the hint lives; the app's preferences, or a map in tests. */
    public interface Prefs {
        /** The stored id; null when nothing was ever stored (not yet migrated). */
        @Nullable
        String get();

        void put(String id);

        /** Upstream's persisted AppClient ordinal (MediaServiceData.getVideoInfoType), -1 if none. */
        int legacyOrdinal();
    }

    static final String NONE = "";

    /**
     * AppClient in ordinal order as of 2026-09-28 (MediaServiceCore 247e2981): the meaning of every
     * ordinal a phone may have persisted. History, not a mirror of the enum - never edit it.
     */
    static final String[] LEGACY_ORDINALS = {
            "TV", "TV_LEGACY", "TV_EMBED", "TV_SIMPLY", "TV_KIDS", "TV_DOWNGRADED", "WEB", "WEB_EMBED",
            "WEB_CREATOR", "WEB_MUSIC", "WEB_SAFARI", "MWEB", "ANDROID", "ANDROID_SDK_LESS",
            "ANDROID_REEL", "ANDROID_VR", "IOS", "INITIAL", "GEO", "VISIONOS", "TV_TIZEN"
    };

    private SourceWinnerHint() {
    }

    /** The hinted source, or null for none (never stored, cleared, or unknown to this build). */
    @Nullable
    public static PlayerSource read(Prefs prefs) {
        String id = prefs.get();
        if (id == null) {
            int ordinal = prefs.legacyOrdinal();
            id = ordinal >= 0 && ordinal < LEGACY_ORDINALS.length ? LEGACY_ORDINALS[ordinal] + "@1" : NONE;
            prefs.put(id);
        }
        return PlayerSourceCatalog.byId(id);
    }

    /** Stores {@code winner}'s source, or clears the hint for null. */
    public static void write(Prefs prefs, @Nullable AppClient winner) {
        prefs.put(winner != null ? PlayerSourceCatalog.defaultFor(winner).id : NONE);
    }
}
