package com.liskovsoft.youtubeapi.videoinfo.V2.sources;

import androidx.annotation.Nullable;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NEWTUBE(recovery-refusals): which sources refused which recent videos, so that a recovery walk
 * (the watched video's reload after a media failure) does not start by asking a source that
 * refused that same video a moment ago.
 *
 * <p>The case (Pixel LTE, v16, the kids video _WB5hh7WOb4): VISIONOS refused it ("not available"),
 * TV_TIZEN served it, an injected media 403 reloaded it, and the recovery plan - the lane's order
 * with the suspect last - put VISIONOS first again: one more refusal (~90 ms) before WEB_EMBED
 * served. A refusal like that is a property of the video for that identity (made for kids, an age
 * gate, members only, an embed policy), so the recovery asks such a source after everything else
 * (PhoneSourcePlanner.Context#recoveryRefused): only if nothing else serves.
 *
 * <p>A record is per video, source and identity (whether the request carried the account: TV_TIZEN
 * is anonymous signed out and carries the account signed in, and one says nothing about the
 * other), good for {@link #TTL_MS}, and dropped as soon as that source serves the video. Bounded
 * ({@link #VIDEO_CAPACITY} videos, least recently used out), per process, forgotten on an account
 * change ({@link #clear}) behind a generation guard. Thread-safe; pure: no requests, no logging.
 */
public final class RecentRefusals {
    /**
     * How long a refusal holds. A made-for-kids or age refusal does not change within a playback;
     * this is the per-video bench of the account route (BotWallBook) too, the other record a
     * recovery of the same video reads.
     */
    public static final long TTL_MS = 30 * 60 * 1000L;
    /** Videos remembered: the watched one, its preloaded next ones, a few before. */
    static final int VIDEO_CAPACITY = 16;

    private static final class Refusal {
        final long atMs;
        final boolean auth;
        final boolean madeForKids;

        Refusal(long atMs, boolean auth, boolean madeForKids) {
            this.atMs = atMs;
            this.auth = auth;
            this.madeForKids = madeForKids;
        }
    }

    private final Map<String, EnumMap<AppClient, Refusal>> mVideos =
            new LinkedHashMap<String, EnumMap<AppClient, Refusal>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, EnumMap<AppClient, Refusal>> eldest) {
                    return size() > VIDEO_CAPACITY;
                }
            };
    private long mGeneration;

    /** Taken when a walk begins: {@link #noteRefused} drops a refusal heard before an account change. */
    public synchronized long generation() {
        return mGeneration;
    }

    /**
     * {@code client} refused {@code videoId} at {@code nowMs} in a walk that began at
     * {@code generation}; {@code auth}: the request carried the account. Nothing is written if the
     * account changed since that walk began (its identity may be the previous account's).
     */
    public synchronized void noteRefused(@Nullable String videoId, @Nullable AppClient client, boolean auth,
            long nowMs, long generation) {
        noteRefused(videoId, client, auth, nowMs, generation, false);
    }

    /**
     * @param madeForKids the refusal is the one a made-for-kids video gets from VISIONOS and
     *                    ANDROID_VR (their anonymous content refusal; see {@link #hasMadeForKids})
     */
    public synchronized void noteRefused(@Nullable String videoId, @Nullable AppClient client, boolean auth,
            long nowMs, long generation, boolean madeForKids) {
        if (videoId == null || client == null || generation != mGeneration) {
            return;
        }
        EnumMap<AppClient, Refusal> refusals = mVideos.get(videoId);
        if (refusals == null) {
            refusals = new EnumMap<>(AppClient.class);
            mVideos.put(videoId, refusals);
        }
        refusals.put(client, new Refusal(nowMs, auth, madeForKids));
    }

    /** {@code client} served {@code videoId}: whatever it answered before no longer holds. */
    public synchronized void noteServed(@Nullable String videoId, @Nullable AppClient client) {
        EnumMap<AppClient, Refusal> refusals = videoId != null ? mVideos.get(videoId) : null;
        if (refusals != null && client != null && refusals.remove(client) != null && refusals.isEmpty()) {
            mVideos.remove(videoId);
        }
    }

    /**
     * The sources that refused {@code videoId} in the last {@link #TTL_MS} with the identity they
     * have in {@code lane} (the account route carries the account signed in; every other source is
     * anonymous), each with the refusal's age in ms, in AppClient order. Empty if none.
     */
    public synchronized Map<AppClient, Long> recent(@Nullable String videoId, PhoneSourcePlanner.Lane lane,
            long nowMs) {
        EnumMap<AppClient, Refusal> refusals = videoId != null ? mVideos.get(videoId) : null;
        if (refusals == null) {
            return Collections.emptyMap();
        }
        Map<AppClient, Long> recent = new EnumMap<>(AppClient.class);
        for (Map.Entry<AppClient, Refusal> entry : refusals.entrySet()) {
            AppClient client = entry.getKey();
            Refusal refusal = entry.getValue();
            long ageMs = nowMs - refusal.atMs;
            boolean auth = lane == PhoneSourcePlanner.Lane.SIGNED_IN && client == PhoneSourcePlanner.ACCOUNT_ROUTE;
            if (ageMs >= 0 && ageMs < TTL_MS && refusal.auth == auth) {
                recent.put(client, ageMs);
            }
        }
        return recent;
    }

    /**
     * NEWTUBE(recovery-kids): VISIONOS or ANDROID_VR refused {@code videoId} as made for kids in the
     * last {@link #TTL_MS} (whatever the lane: those refusals are anonymous in both). Its recovery
     * asks the sources that never serve such a video after the suspect (PhoneSourcePlanner).
     */
    public synchronized boolean hasMadeForKids(@Nullable String videoId, long nowMs) {
        EnumMap<AppClient, Refusal> refusals = videoId != null ? mVideos.get(videoId) : null;
        if (refusals == null) {
            return false;
        }
        for (Refusal refusal : refusals.values()) {
            long ageMs = nowMs - refusal.atMs;
            if (refusal.madeForKids && ageMs >= 0 && ageMs < TTL_MS) {
                return true;
            }
        }
        return false;
    }

    /**
     * A sign-in, account switch or sign-out: the records were the previous identity's, and walks
     * already in flight can no longer write.
     */
    public synchronized int clear() {
        mGeneration++;
        int videos = mVideos.size();
        mVideos.clear();
        return videos;
    }
}
