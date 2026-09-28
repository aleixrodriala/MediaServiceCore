package com.liskovsoft.youtubeapi.videoinfo.models;

import androidx.annotation.Nullable;

import com.liskovsoft.googlecommon.common.converters.jsonpath.JsonPath;

import java.util.List;

/**
 * NEWTUBE(readiness): the pre-roll ads a /player answer announces, and how long YouTube holds back
 * that answer's media because of them.
 *
 * <p>When an answer carries pre-roll ads, googlevideo refuses its content media for roughly the
 * ads' skippable time after the answer was issued; once that has passed the same URLs serve. Seen
 * on a Pixel 9 (2026-09-28): WEB_EMBED's first media request 0.3 s after /player got HTTP 403 every
 * time, the same cached URLs played a minute later. The official player waits too (its SABR server
 * answers "back off" first), and yt-dlp sleeps for the same sum before downloading
 * ({@code _get_available_at_timestamp}): for every pre-content in-stream video ad, its skip offset
 * if it is skippable, else its length. This class is that rule. The app honours the wait; it never
 * tries to shorten it.
 */
public final class PrerollAds {
    /**
     * Wait assumed for a pre-roll the answer announces but whose wait cannot be read: an ad with
     * neither skip offset nor length, or a pre-content slot in a shape this class does not map.
     * The longest common unskippable in-stream ad; the player asks first and waits only when
     * refused (ReadinessGate), so a generous guess costs nothing where media is not held back. None
     * of 206 pre-roll slots in 834 netbench answers (2026-09-28) needed it.
     */
    public static final long UNKNOWN_AD_WAIT_MS = 15_000;

    private static final String SLOT_BEFORE_CONTENT = "SLOT_TRIGGER_EVENT_BEFORE_CONTENT";
    private static final String PLACEMENT_START = "AD_PLACEMENT_KIND_START";

    private PrerollAds() {
    }

    /** One entry of {@code adSlots}. */
    public static class Slot {
        @JsonPath("$.adSlotRenderer.adSlotMetadata.triggerEvent")
        private String mTriggerEvent;

        @JsonPath("$.adSlotRenderer.fulfillmentContent.fulfilledLayout.playerBytesAdLayoutRenderer"
                + ".renderingContent.instreamVideoAdRenderer")
        private VideoAd mAd;

        @JsonPath("$.adSlotRenderer.fulfillmentContent.fulfilledLayout.playerBytesAdLayoutRenderer"
                + ".renderingContent.playerBytesSequentialLayoutRenderer.sequentialLayouts[*]"
                + ".playerBytesAdLayoutRenderer.renderingContent.instreamVideoAdRenderer")
        private List<VideoAd> mSequentialAds;
    }

    /** One entry of {@code adPlacements} (the older shape). */
    public static class Placement {
        @JsonPath("$.adPlacementRenderer.config.adPlacementConfig.kind")
        private String mKind;

        @JsonPath("$.adPlacementRenderer.renderer.instreamVideoAdRenderer")
        private VideoAd mAd;
    }

    /** An {@code instreamVideoAdRenderer}. */
    public static class VideoAd {
        // Presence only: the converter drops a nested object none of whose fields it finds, and an
        // ad whose wait is unreadable must still count (as UNKNOWN_AD_WAIT_MS). Every one of 80
        // pre-roll ads in the 2026-09-28 netbench answers carried all three.
        @JsonPath({"$.layoutId", "$.elementId", "$.trackingParams"})
        private String mId;

        // A JSON number arrives as Integer (JsonPathTypeAdapter.parsePrimitive); null = the ad has
        // no skip offset, i.e. it is not skippable and holds content back for its whole length.
        @JsonPath("$.skipOffsetMilliseconds")
        private Integer mSkipOffsetMs;

        @JsonPath("$.playerVars")
        private String mPlayerVars;

        /** The time this ad holds content back, or -1 when neither value can be read. */
        long waitMs() {
            if (mSkipOffsetMs != null && mSkipOffsetMs >= 0) {
                return mSkipOffsetMs;
            }
            Long lengthS = parseLong(queryValue(mPlayerVars, "length_seconds"));
            return lengthS != null && lengthS >= 0 ? lengthS * 1000 : -1;
        }
    }

    /**
     * The total pre-roll wait of an answer in ms: 0 when it announces no pre-roll ad. A pre-roll
     * whose wait cannot be read counts as {@link #UNKNOWN_AD_WAIT_MS} - unknown is not zero.
     */
    public static long waitMs(@Nullable List<Slot> slots, @Nullable List<Placement> placements) {
        long total = 0;
        if (slots != null) {
            for (Slot slot : slots) {
                if (slot == null || !SLOT_BEFORE_CONTENT.equals(slot.mTriggerEvent)) {
                    continue;
                }
                boolean sequential = slot.mSequentialAds != null && !slot.mSequentialAds.isEmpty();
                if (slot.mAd == null && !sequential) {
                    total += UNKNOWN_AD_WAIT_MS; // a pre-content slot all the same: unknown, not zero
                    continue;
                }
                total += adWaitMs(slot.mAd);
                if (sequential) {
                    for (VideoAd ad : slot.mSequentialAds) {
                        total += adWaitMs(ad);
                    }
                }
            }
        }
        if (placements != null) {
            for (Placement placement : placements) {
                if (placement != null && PLACEMENT_START.equals(placement.mKind)) {
                    total += adWaitMs(placement.mAd);
                }
            }
        }
        return total;
    }

    private static long adWaitMs(@Nullable VideoAd ad) {
        if (ad == null) {
            return 0;
        }
        long wait = ad.waitMs();
        return wait >= 0 ? wait : UNKNOWN_AD_WAIT_MS;
    }

    @Nullable
    private static String queryValue(@Nullable String query, String key) {
        if (query == null) {
            return null;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) {
                return part.substring(eq + 1);
            }
        }
        return null;
    }

    @Nullable
    private static Long parseLong(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return (long) Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
