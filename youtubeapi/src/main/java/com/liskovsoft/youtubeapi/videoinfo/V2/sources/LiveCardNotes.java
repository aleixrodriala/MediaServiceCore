package com.liskovsoft.youtubeapi.videoinfo.V2.sources;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NEWTUBE(live-card): the videos the app is about to open from an item that says "live" (a card's
 * live badge, the next-video slot, /next after the open). The walk of such a video asks the
 * live-DASH source first (PhoneSourcePlanner.Context#liveCardHinted); a video the app later names
 * not live (a stream that ended, per /next) is forgotten.
 *
 * <p>A note is only a hint: the walk checks the answer (VideoInfoService sets aside a non-live answer
 * from the live source and goes on in the lane's order), so a stale note costs one request.
 * Bounded ({@link #CAPACITY} videos, least recently noted out), per process. Thread-safe; pure: no
 * requests, no logging.
 */
public final class LiveCardNotes {
    /** The opened video, its preloads and a few before them. */
    static final int CAPACITY = 32;

    private final Map<String, Boolean> mLive = new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > CAPACITY;
        }
    };

    /**
     * The app's item for {@code videoId} says live ({@code live}) or not. Returns whether this
     * changed what is noted, so the caller logs each change once.
     */
    public synchronized boolean note(@Nullable String videoId, boolean live) {
        if (videoId == null) {
            return false;
        }
        if (live) {
            return mLive.put(videoId, Boolean.TRUE) == null;
        }
        return mLive.remove(videoId) != null;
    }

    public synchronized boolean isLive(@Nullable String videoId) {
        return videoId != null && mLive.containsKey(videoId);
    }

    public synchronized void clear() {
        mLive.clear();
    }
}
