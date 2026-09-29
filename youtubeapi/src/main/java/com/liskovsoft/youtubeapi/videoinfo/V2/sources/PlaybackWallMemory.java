package com.liskovsoft.youtubeapi.videoinfo.V2.sources;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * NEWTUBE(wall-memory): which (visitor, source) pairs met the one-minute wall. Pure bookkeeping: no
 * requests, no logging, no Android types (plain unit tests).
 *
 * <p>The wall (netbench r11 at home, 2026-09-29): googlevideo serves a walled visitor's media to
 * 60.0 s and then refuses every chunk with 403, per visitor AND source. On two walled visitors
 * VISIONOS and ANDROID_VR walled (they send the visitor as the web session's), while TV_TIZEN sent
 * the SAME visitor and played 137 s, and WEB_EMBED (its own embed identity) played. A replay 16.5 min
 * after a wall walled again. So a pair that walled is asked only after every other source until
 * {@link #getTtlMs()} passes, for this video's recovery and every later open; a re-rolled playback
 * identity is another visitor and starts clean.
 *
 * <p>Visitors are kept as their fingerprint (VisitorFingerprint, a hash), never the identity itself.
 * Snapshot: {@code {"v":1,"walls":[{"f":"<fp>","c":"VISIONOS","until":ms},...]}}.
 */
public final class PlaybackWallMemory {
    /**
     * How long a wall is remembered: the playback identity's own window (PlaybackIdentityBook), so a
     * kept re-rolled identity and the wall it left behind lapse together. r11: still walled 16.5 min
     * later; longer expiry checks are running.
     */
    public static final long DEFAULT_TTL_MS = 6 * 60 * 60 * 1000L;
    /** Pairs kept at most (the oldest go first). */
    static final int CAPACITY = 32;
    private static final int VERSION = 1;

    /** Where the wall stands in the media (r11: 60.0 s of stream position, not of elapsed time). */
    public static final long WALL_MS = 60_000;
    /**
     * How far from it a refused request may start and still meet the wall: the real one refuses the
     * first request past 60.0 s; the synthetic one (debug.arc.poison_wall_s) cuts by bytes, so a
     * variable-bitrate stream's cut lands a few seconds either side.
     */
    public static final long TOLERANCE_MS = 5_000;

    /** What one media 403 says about the wall; see {@link #classify}. */
    public enum Signature {
        /** Not the wall: before it, or past media already served (a stale link minutes in). */
        NONE,
        /** The wall: refused past it after media before it was served. */
        WALL,
        /** Refused past it with nothing before it served (a resume or seek straight past it). */
        AMBIGUOUS
    }

    /**
     * NEWTUBE(wall-memory): the wall's signature on one open's media requests (r11 expiry recheck,
     * YQHsXMglC9A on both walled visitors: first frame at 0, a jump to 73.7 s, and the first request
     * past 60 s - audio start=70001 ms, video 73699 - refused at once; every reload resumed at 73.7 s
     * and was refused again). The wall is on stream position: a request that starts at or past it
     * ({@code forbiddenStartMs}) is refused while this (visitor, source) had requests before it
     * served ({@code lowestServedStartMs}). Nothing served past it ({@code highestServedStartMs}):
     * past-the-wall media that played means this 403 is something else. -1: unknown/none.
     */
    public static Signature classify(long forbiddenStartMs, long lowestServedStartMs,
            long highestServedStartMs) {
        if (forbiddenStartMs < WALL_MS - TOLERANCE_MS) {
            return Signature.NONE;
        }
        if (highestServedStartMs >= WALL_MS + TOLERANCE_MS) {
            return Signature.NONE;
        }
        return lowestServedStartMs >= 0 && lowestServedStartMs < WALL_MS
                ? Signature.WALL : Signature.AMBIGUOUS;
    }

    /** Supplied by the phone app; without one the memory lives in this process only. */
    public interface Store {
        @Nullable
        String load();

        /** Must not block (SharedPreferences.apply). */
        void save(@Nullable String snapshot);
    }

    // key "<fingerprint>|<client>" -> expiry (wall clock)
    private final Map<String, Long> mWalls = new LinkedHashMap<>();
    private long mTtlMs = DEFAULT_TTL_MS;
    @Nullable
    private Store mStore;
    private boolean mRestored;

    public synchronized void setStore(@Nullable Store store) {
        mStore = store;
        mRestored = false;
    }

    public synchronized void setTtlMs(long ttlMs) {
        mTtlMs = Math.max(0, ttlMs);
    }

    public synchronized long getTtlMs() {
        return mTtlMs;
    }

    /** The wall met {@code client}'s media for the visitor {@code fingerprint} at {@code nowMs}. */
    public synchronized void record(@Nullable String fingerprint, @Nullable AppClient client, long nowMs) {
        if (fingerprint == null || fingerprint.isEmpty() || client == null) {
            return;
        }
        restoreOnce(nowMs);
        String key = key(fingerprint, client);
        mWalls.remove(key); // re-inserted last: the newest wall is the last to go
        mWalls.put(key, nowMs + mTtlMs);
        while (mWalls.size() > CAPACITY) {
            Iterator<String> it = mWalls.keySet().iterator();
            it.next();
            it.remove();
        }
        persist();
    }

    /** Whether anything is remembered at all (a cheap check before resolving visitors). */
    public synchronized boolean isEmpty(long nowMs) {
        restoreOnce(nowMs);
        if (prune(nowMs)) {
            persist();
        }
        return mWalls.isEmpty();
    }

    /** The sources walled for the visitor {@code fingerprint} at {@code nowMs}. */
    public synchronized Set<AppClient> walledFor(@Nullable String fingerprint, long nowMs) {
        if (fingerprint == null || fingerprint.isEmpty() || isEmpty(nowMs)) {
            return Collections.emptySet();
        }
        Set<AppClient> walled = EnumSet.noneOf(AppClient.class);
        String prefix = fingerprint + "|";
        for (String key : mWalls.keySet()) {
            if (key.startsWith(prefix)) {
                try {
                    walled.add(AppClient.valueOf(key.substring(prefix.length())));
                } catch (IllegalArgumentException e) {
                    // a source this build no longer has
                }
            }
        }
        return walled;
    }

    public synchronized boolean isWalled(@Nullable String fingerprint, @Nullable AppClient client, long nowMs) {
        return client != null && walledFor(fingerprint, nowMs).contains(client);
    }

    /** For the log: {@code fp|CLIENT} pairs still remembered. */
    public synchronized String describe(long nowMs) {
        isEmpty(nowMs);
        return mWalls.keySet().toString();
    }

    /** Test hook: empty, in memory, the default TTL. */
    public synchronized void resetForTest() {
        mWalls.clear();
        mTtlMs = DEFAULT_TTL_MS;
        mStore = null;
        mRestored = false;
    }

    private static String key(String fingerprint, AppClient client) {
        return fingerprint + "|" + client.name();
    }

    /** Drops the expired walls; true when any went. A time in the future (a clock set back) counts. */
    private boolean prune(long nowMs) {
        boolean changed = false;
        for (Iterator<Map.Entry<String, Long>> it = mWalls.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Long> wall = it.next();
            if (nowMs >= wall.getValue() || wall.getValue() - nowMs > mTtlMs) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    private void restoreOnce(long nowMs) {
        if (mRestored) {
            return;
        }
        mRestored = true;
        String snapshot = null;
        try {
            snapshot = mStore != null ? mStore.load() : null;
        } catch (RuntimeException e) {
            // Persistence is an optimisation, never a reason to fail an open.
        }
        if (snapshot == null) {
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(snapshot).getAsJsonObject();
            if (json.get("v") == null || json.get("v").getAsInt() != VERSION) {
                return;
            }
            JsonArray walls = json.getAsJsonArray("walls");
            if (walls == null) {
                return;
            }
            for (JsonElement element : walls) {
                JsonObject wall = element.getAsJsonObject();
                String fingerprint = wall.get("f").getAsString();
                String client = wall.get("c").getAsString();
                long until = wall.get("until").getAsLong();
                String key = fingerprint + "|" + client;
                if (!mWalls.containsKey(key)) {
                    mWalls.put(key, until);
                }
            }
        } catch (RuntimeException e) {
            // A damaged snapshot: start empty (the next record rewrites it).
        }
        if (prune(nowMs)) {
            persist();
        }
    }

    private void persist() {
        Store store = mStore;
        if (store == null) {
            return;
        }
        String snapshot = null;
        if (!mWalls.isEmpty()) {
            JsonObject json = new JsonObject();
            json.addProperty("v", VERSION);
            JsonArray walls = new JsonArray();
            for (Map.Entry<String, Long> entry : mWalls.entrySet()) {
                int bar = entry.getKey().lastIndexOf('|');
                JsonObject wall = new JsonObject();
                wall.addProperty("f", entry.getKey().substring(0, bar));
                wall.addProperty("c", entry.getKey().substring(bar + 1));
                wall.addProperty("until", entry.getValue());
                walls.add(wall);
            }
            json.add("walls", walls);
            snapshot = json.toString();
        }
        try {
            store.save(snapshot);
        } catch (RuntimeException e) {
            // See restoreOnce.
        }
    }
}
