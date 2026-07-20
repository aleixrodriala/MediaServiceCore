package com.liskovsoft.youtubeapi.lounge.sender;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.liskovsoft.mediaserviceinterfaces.RemoteControlService;
import com.liskovsoft.mediaserviceinterfaces.data.CastEvent;
import com.liskovsoft.sharedutils.mylogger.Log;

/**
 * Converts incoming Lounge status messages to {@link CastEvent}s.<br/>
 * All payload values arrive as STRINGS, times in SECONDS (fractional allowed) —
 * parsed defensively: a bad/missing required field skips the whole event with a log.
 */
public class SenderEvents {
    private static final String TAG = SenderEvents.class.getSimpleName();

    // Lounge state ints (plaincast wire values). Anything else is treated as not-playing.
    private static final int LOUNGE_STATE_STOPPED = 0;
    private static final int LOUNGE_STATE_PLAYING = 1;
    private static final int LOUNGE_STATE_PAUSED = 2;
    private static final int LOUNGE_STATE_BUFFERING = 3;

    private SenderEvents() {
    }

    /**
     * @return the corresponding event or null when the message isn't a status event
     *         (or its payload is unusable)
     */
    @Nullable
    public static CastEvent toCastEvent(LoungeMessage message) {
        if (message == null || message.getType() == null) {
            return null;
        }

        JsonObject payload = message.getPayload();

        switch (message.getType()) {
            case LoungeMessage.TYPE_NOW_PLAYING:
                return toNowPlaying(payload);
            case LoungeMessage.TYPE_ON_STATE_CHANGE:
                return toStateChange(payload);
            case LoungeMessage.TYPE_ON_VOLUME_CHANGED:
                return toVolumeChange(payload);
            default:
                return null;
        }
    }

    @Nullable
    private static CastEvent toNowPlaying(@Nullable JsonObject payload) {
        // Sent as empty {} when nothing is playing — nothing to report
        String videoId = getString(payload, "videoId");

        if (videoId == null || videoId.isEmpty()) {
            Log.d(TAG, "nowPlaying: no videoId (idle screen?). Skipping.");
            return null;
        }

        long positionMs = parseTimeMs(getString(payload, "currentTime"));

        if (positionMs < 0) {
            Log.e(TAG, "nowPlaying: bad currentTime. Skipping.");
            return null;
        }

        long durationMs = parseTimeMs(getString(payload, "duration")); // -1 when absent (e.g. live)
        int state = toAppState(parseInt(getString(payload, "state"), -1));

        return CastEvent.nowPlaying(videoId, positionMs, durationMs, state);
    }

    @Nullable
    private static CastEvent toStateChange(@Nullable JsonObject payload) {
        long positionMs = parseTimeMs(getString(payload, "currentTime"));

        if (positionMs < 0) {
            Log.e(TAG, "onStateChange: bad currentTime. Skipping.");
            return null;
        }

        long durationMs = parseTimeMs(getString(payload, "duration"));
        int state = toAppState(parseInt(getString(payload, "state"), -1));

        return CastEvent.stateChange(positionMs, durationMs, state);
    }

    @Nullable
    private static CastEvent toVolumeChange(@Nullable JsonObject payload) {
        int volume = parseInt(getString(payload, "volume"), -1);

        if (volume < 0) {
            Log.e(TAG, "onVolumeChanged: bad volume. Skipping.");
            return null;
        }

        return CastEvent.volumeChange(volume);
    }

    /**
     * Lounge state to RemoteControlService.STATE_*.<br/>
     * Buffering counts as playing; unknown values (incl. possible "ended"/"cued" codes)
     * fall back to idle.
     */
    public static int toAppState(int loungeState) {
        switch (loungeState) {
            case LOUNGE_STATE_PLAYING:
            case LOUNGE_STATE_BUFFERING:
                return RemoteControlService.STATE_PLAYING;
            case LOUNGE_STATE_PAUSED:
                return RemoteControlService.STATE_PAUSED;
            case LOUNGE_STATE_STOPPED:
            default:
                return RemoteControlService.STATE_IDLE;
        }
    }

    @Nullable
    private static String getString(@Nullable JsonObject payload, String key) {
        if (payload == null) {
            return null;
        }

        JsonElement value = payload.get(key);

        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /**
     * Seconds-as-string (fractional ok) to ms.
     *
     * @return -1 when missing/unparsable
     */
    private static long parseTimeMs(@Nullable String seconds) {
        if (seconds == null || seconds.isEmpty()) {
            return -1;
        }

        try {
            return (long) (Double.parseDouble(seconds) * 1_000);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int parseInt(@Nullable String value, int defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
