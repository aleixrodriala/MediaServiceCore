package com.liskovsoft.youtubeapi.videoinfo.V2;

import androidx.annotation.Nullable;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;

/**
 * NEWTUBE(walk-replay): debug and benchmark builds only - one NetPath line per parsed /player answer
 * with what the walk classifies it by and the player-result line does not carry: YouTube's reason
 * and subreason as sent (each bounded at 1000 characters, {@code "cut":true} when one was, where
 * player-result joins them and cuts at 160), desktopLegacyAgeGateReason (the age gate,
 * VideoInfo.isAgeGate), the live signals hasLiveSignal reads (isLive, isLiveContent, a start time)
 * and a rental's trailer (isRent). These are the fields the answer's errorScreen and videoDetails
 * give the walk; nothing else of them is parsed.
 *
 * <p>tools/netbench/appbench/replay_fixtures.py reads it, so a walk-replay fixture
 * (VideoInfoReplayTest) rebuilds such an answer from YouTube's own fields instead of inferring its
 * age gate and live signals from the walk's own lines. Off unless the phone app turns it on: the
 * main repo's MobileMainApplication does, in debug and benchmark builds only (like the other debug
 * hooks here, the build check is the app's), so release builds never log it. Credential-free: the
 * video id is the only id, and a URL inside a reason is logged as {@code <url>}.
 */
public final class PlayabilityLog {
    private static final int MAX_TEXT = 1000;
    private static volatile boolean sEnabled;

    private PlayabilityLog() {
    }

    /** Phone debug and benchmark builds, once at process start. */
    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    static void log(String videoId, AppClient client, int attempt, @Nullable VideoInfo result) {
        if (!sEnabled || result == null) {
            return;
        }
        android.util.Log.d("NetPath", "player-playability video=" + videoId + " client=" + client
                + " attempt=" + attempt + " " + json(result));
    }

    /** The compact JSON of the line: fixed keys, strings bounded ("cut" says so), no URLs. */
    static String json(VideoInfo result) {
        StringBuilder out = new StringBuilder("{");
        boolean cut = text(out, "status", result.getRawPlayabilityStatus());
        cut |= text(out, "reason", result.getPlayabilityReason());
        cut |= text(out, "subreason", result.getPlayabilitySubreason());
        out.append(",\"desktopLegacyAgeGateReason\":").append(result.getDesktopLegacyAgeGateReason());
        flag(out, "live", result.isLive());
        flag(out, "liveContent", result.getVideoDetails() != null && result.getVideoDetails().isLiveContent());
        flag(out, "liveStart", result.getStartTimestamp() != null);
        flag(out, "trailer", result.getTrailerVideoId() != null);
        flag(out, "cut", cut);
        return out.append('}').toString();
    }

    /** @return whether the value had to be cut */
    private static boolean text(StringBuilder out, String key, @Nullable String value) {
        if (out.length() > 1) {
            out.append(',');
        }
        out.append('"').append(key).append("\":");
        if (value == null) {
            out.append("null");
            return false;
        }
        String printable = value.replaceAll("https?://\\S+", "<url>")
                .replaceAll("[\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
        boolean cut = printable.length() > MAX_TEXT;
        if (cut) {
            printable = printable.substring(0, MAX_TEXT) + "…";
        }
        out.append('"');
        for (int i = 0; i < printable.length(); i++) {
            char c = printable.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        out.append('"');
        return cut;
    }

    private static void flag(StringBuilder out, String key, boolean value) {
        out.append(",\"").append(key).append("\":").append(value);
    }
}
