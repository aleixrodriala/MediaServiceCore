package com.liskovsoft.youtubeapi.app;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * NEWTUBE(playback-identity): the budget of playback identity re-rolls and the identity kept after
 * one. Pure bookkeeping: no requests, no logging, no Android types (plain unit tests).
 *
 * <p>Budget: at most {@link #getBudget()} re-rolls in any {@link #WINDOW_MS} window, counted on the
 * wall clock and persisted, so one IP never churns through visitors (a farm is exactly what a
 * wall-per-visitor server would look for). A restart does not refill it.
 *
 * <p>Kept identity (the second switch): after a re-roll the fresh visitor can stay the playback
 * identity for the next opens, for {@link #WINDOW_MS} from the re-roll, instead of every later
 * video meeting the wall on the old visitor first. Persisted with the budget.
 *
 * <p>Pending re-roll: armed but not yet minted (the web session is rebuilt on its next use - after
 * a wall whose recovery TV_TIZEN served, that can be the next process). Persisted, so a restart
 * still mints the fresh visitor instead of adopting the walled one again; it lapses with
 * {@link #WINDOW_MS}.
 *
 * <p>Snapshot format: {@code {"v":1,"rerolls":[ms,...],"kept":"<visitorData>","keptAt":ms,
 * "pending":{"video":"<id>","from":"<fingerprint>","budgetLeft":n,"at":ms}}}.
 */
public final class PlaybackIdentityBook {
    /** The budget's window, and a kept identity's lifetime. */
    public static final long WINDOW_MS = 6 * 60 * 60 * 1000L;
    /** Re-rolls per window unless the app sets another budget. */
    public static final int DEFAULT_BUDGET = 2;
    private static final int VERSION = 1;

    /** Supplied by the phone app; without one the book lives in memory only. */
    public interface Store {
        @Nullable
        String load();

        /** Must not block (SharedPreferences.apply). */
        void save(@Nullable String snapshot);
    }

    private final List<Long> mRerolls = new ArrayList<>();
    @Nullable
    private String mKept;
    private long mKeptAtMs;
    private int mBudget = DEFAULT_BUDGET;
    @Nullable
    private Pending mPending;
    @Nullable
    private Store mStore;
    private boolean mRestored;

    /** A re-roll armed and not yet minted (see the class comment). */
    public static final class Pending {
        public final String videoId;
        public final String fromFingerprint;
        public final int budgetLeft;
        final long atMs;

        Pending(String videoId, String fromFingerprint, int budgetLeft, long atMs) {
            this.videoId = videoId;
            this.fromFingerprint = fromFingerprint;
            this.budgetLeft = budgetLeft;
            this.atMs = atMs;
        }
    }

    public synchronized void setPending(String videoId, String fromFingerprint, int budgetLeft, long nowMs) {
        restoreOnce(nowMs);
        mPending = new Pending(videoId, fromFingerprint, budgetLeft, nowMs);
        persist();
    }

    /** The re-roll still to mint, or null (none, or it lapsed). */
    @Nullable
    public synchronized Pending pending(long nowMs) {
        restoreOnce(nowMs);
        if (mPending != null && !(nowMs - mPending.atMs >= 0 && nowMs - mPending.atMs < WINDOW_MS)) {
            mPending = null;
            persist();
        }
        return mPending;
    }

    /** The pending re-roll was minted (or given up): returns it, or null. */
    @Nullable
    public synchronized Pending takePending(long nowMs) {
        Pending pending = pending(nowMs);
        if (pending != null) {
            mPending = null;
            persist();
        }
        return pending;
    }

    public synchronized void setStore(@Nullable Store store) {
        mStore = store;
        mRestored = false;
    }

    public synchronized void setBudget(int budget) {
        mBudget = Math.max(0, budget);
    }

    public synchronized int getBudget() {
        return mBudget;
    }

    /** Re-rolls still allowed at {@code nowMs}. */
    public synchronized int budgetLeft(long nowMs) {
        restoreOnce(nowMs);
        prune(nowMs);
        return Math.max(0, mBudget - mRerolls.size());
    }

    /** Spends one re-roll at {@code nowMs}; false (nothing spent) when the budget is spent. */
    public synchronized boolean spend(long nowMs) {
        if (budgetLeft(nowMs) <= 0) {
            return false;
        }
        mRerolls.add(nowMs);
        persist();
        return true;
    }

    /** Gives back the latest re-roll (its fresh visitor could not be minted). */
    public synchronized void refundLatest(long nowMs) {
        restoreOnce(nowMs);
        if (!mRerolls.isEmpty()) {
            mRerolls.remove(mRerolls.size() - 1);
            persist();
        }
    }

    /** The identity kept after a re-roll, or null (none, expired, or dropped). */
    @Nullable
    public synchronized String kept(long nowMs) {
        restoreOnce(nowMs);
        if (mKept != null && !(nowMs - mKeptAtMs >= 0 && nowMs - mKeptAtMs < WINDOW_MS)) {
            mKept = null;
            mKeptAtMs = 0;
            persist();
        }
        return mKept;
    }

    /** Keep {@code visitorData} as the playback identity from {@code nowMs}. */
    public synchronized void keep(@Nullable String visitorData, long nowMs) {
        restoreOnce(nowMs);
        if (visitorData == null || visitorData.isEmpty()) {
            return;
        }
        mKept = visitorData;
        mKeptAtMs = nowMs;
        persist();
    }

    /** The kept identity met the wall too (or keeping was switched off): back to the app's. */
    public synchronized boolean dropKept() {
        if (mKept == null) {
            return false;
        }
        mKept = null;
        mKeptAtMs = 0;
        persist();
        return true;
    }

    /** Test hook: empty, in memory, the default budget. */
    synchronized void resetForTest() {
        mRerolls.clear();
        mKept = null;
        mKeptAtMs = 0;
        mPending = null;
        mBudget = DEFAULT_BUDGET;
        mStore = null;
        mRestored = false;
    }

    private void prune(long nowMs) {
        for (Iterator<Long> it = mRerolls.iterator(); it.hasNext(); ) {
            long at = it.next();
            // A time in the future (a clock set back) counts until the window passes it by.
            if (nowMs - at >= WINDOW_MS) {
                it.remove();
            }
        }
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
            // Persistence is an optimisation of the budget, never a reason to fail an open.
        }
        if (snapshot == null) {
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(snapshot).getAsJsonObject();
            if (json.get("v") == null || json.get("v").getAsInt() != VERSION) {
                return;
            }
            JsonArray rerolls = json.getAsJsonArray("rerolls");
            if (rerolls != null) {
                for (JsonElement at : rerolls) {
                    long ms = at.getAsLong();
                    if (!mRerolls.contains(ms)) {
                        mRerolls.add(ms);
                    }
                }
            }
            JsonElement kept = json.get("kept");
            JsonElement keptAt = json.get("keptAt");
            if (mKept == null && kept != null && keptAt != null && !kept.isJsonNull()) {
                mKept = kept.getAsString();
                mKeptAtMs = keptAt.getAsLong();
            }
            JsonElement pending = json.get("pending");
            if (mPending == null && pending != null && pending.isJsonObject()) {
                JsonObject p = pending.getAsJsonObject();
                mPending = new Pending(p.get("video").getAsString(), p.get("from").getAsString(),
                        p.get("budgetLeft").getAsInt(), p.get("at").getAsLong());
            }
        } catch (RuntimeException e) {
            // A damaged snapshot: start empty (the next spend rewrites it).
        }
        prune(nowMs);
    }

    private void persist() {
        Store store = mStore;
        if (store == null) {
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("v", VERSION);
        JsonArray rerolls = new JsonArray();
        for (long at : mRerolls) {
            rerolls.add(at);
        }
        json.add("rerolls", rerolls);
        if (mKept != null) {
            json.addProperty("kept", mKept);
            json.addProperty("keptAt", mKeptAtMs);
        }
        if (mPending != null) {
            JsonObject pending = new JsonObject();
            pending.addProperty("video", mPending.videoId);
            pending.addProperty("from", mPending.fromFingerprint);
            pending.addProperty("budgetLeft", mPending.budgetLeft);
            pending.addProperty("at", mPending.atMs);
            json.add("pending", pending);
        }
        try {
            store.save(json.toString());
        } catch (RuntimeException e) {
            // See restoreOnce.
        }
    }
}
