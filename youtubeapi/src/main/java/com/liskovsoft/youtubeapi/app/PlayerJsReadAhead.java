package com.liskovsoft.youtubeapi.app;

import androidx.annotation.Nullable;

/**
 * NEWTUBE(player-js-gate): what a /player request needs from a NEW player JS - its
 * signatureTimestamp and its cpn code - published as soon as the JS is read, before the player is
 * validated.
 * <p>
 * Building a {@code PlayerDataExtractor} for a player the app has not seen (YouTube ships one every
 * few days) reads the ~2.1 MB JS, then preprocesses it in V8 and solves checkSigData's fixed
 * n/sig challenges: about 3 s on the emulator, measured, and around 1 s on a Pixel 9, estimated.
 * Every /player waited for all of it (under AppServiceIntCached's player lock), although the
 * request only carries the timestamp (a regex over the JS) and a cpn. The extractor's
 * construction publishes both here once the JS is read. A request for a source whose answers need
 * no signature/n solve (VideoInfoService decides which) takes them and goes out; the validation
 * carries on in the background, and anything that has to decipher still waits for it because it
 * asks the extractor itself.
 * <p>
 * Only the construction for the player URL the app info names publishes: that timestamp is the one
 * the finished extractor carries too, validated or not (firstValidExtractor copies it onto a
 * fallback player). The one case where they differ: no player validates at all (the app info's,
 * the persisted one, the built-in one). The old path then sent the timestamp of the last player
 * tried, the built-in one; a read-ahead request sends the app info player's, the one YouTube serves
 * now. The cpn is random either way. A build that throws discards what it read ahead.
 */
final class PlayerJsReadAhead {
    /** One player's read-ahead: the JS was read, its validation may still be running. */
    static final class Data {
        final String playerUrl;
        final String signatureTimestamp;
        @Nullable
        final String cpnCode;
        final long readAtMs;

        Data(String playerUrl, String signatureTimestamp, @Nullable String cpnCode, long readAtMs) {
            this.playerUrl = playerUrl;
            this.signatureTimestamp = signatureTimestamp;
            this.cpnCode = cpnCode;
            this.readAtMs = readAtMs;
        }
    }

    /** Why {@link #await} answered null. */
    enum Fallback {
        /** An extractor for the URL is built: asking it costs nothing. */
        READY,
        /** A build ended without a read-ahead for the URL (another URL, an error, an unreadable JS). */
        BUILD_ENDED,
        /** The waiting thread was interrupted (its attempt was abandoned). */
        INTERRUPTED,
        /** No player URL to wait for. */
        NO_URL,
        /** No build runs and none could be started. */
        NO_BUILD
    }

    interface Listener {
        void onFallback(Fallback reason, long waitedMs);
    }

    interface BuildStarter {
        /** Makes sure a build runs; false when none runs and none could be started. */
        boolean start();
    }

    private final Object mLock = new Object();
    @Nullable
    private Data mRead;
    @Nullable
    private String mReadyUrl;
    private long mBuildsEnded;

    /** The construction for {@code playerUrl} read the JS; wakes the requests waiting for it. */
    void publish(String playerUrl, String signatureTimestamp, @Nullable String cpnCode, long nowMs) {
        synchronized (mLock) {
            mRead = new Data(playerUrl, signatureTimestamp, cpnCode, nowMs);
            mLock.notifyAll();
        }
    }

    /** An extractor for {@code playerUrl} is built (validated or fallen back): asking it no longer waits. */
    void markReady(String playerUrl) {
        synchronized (mLock) {
            mReadyUrl = playerUrl;
            mLock.notifyAll();
        }
    }

    /** The build for {@code playerUrl} failed: what it read ahead no longer answers anything. */
    void discard(@Nullable String playerUrl) {
        synchronized (mLock) {
            if (mRead != null && mRead.playerUrl.equals(playerUrl)) {
                mRead = null;
            }
        }
    }

    /** A build ended, however it ended (also sent when the background build thread exits). */
    void buildEnded() {
        synchronized (mLock) {
            mBuildsEnded++;
            mLock.notifyAll();
        }
    }

    boolean isReady(@Nullable String playerUrl) {
        synchronized (mLock) {
            return playerUrl != null && playerUrl.equals(mReadyUrl);
        }
    }

    /** Something was read ahead or built: a construction is under way or done. */
    boolean hasPlayer() {
        synchronized (mLock) {
            return mRead != null || mReadyUrl != null;
        }
    }

    /** The last read-ahead has not been followed by a finished build for its URL yet. */
    boolean hasPending() {
        synchronized (mLock) {
            return mRead != null && !mRead.playerUrl.equals(mReadyUrl);
        }
    }

    /** The last read-ahead if it is {@code playerUrl}'s, validated since or not; else null. */
    @Nullable
    Data readFor(@Nullable String playerUrl) {
        synchronized (mLock) {
            return playerUrl != null && mRead != null && playerUrl.equals(mRead.playerUrl) ? mRead : null;
        }
    }

    /**
     * The read-ahead for {@code playerUrl}, waiting for it if a build has to read the JS first
     * ({@code startBuild} makes sure one runs). Null means "ask the extractor, as before": it is
     * built already, a build ended without reading this JS, or the thread was interrupted (the
     * interrupt is kept).
     */
    @Nullable
    Data await(@Nullable String playerUrl, BuildStarter startBuild, @Nullable Listener listener) {
        if (playerUrl == null) {
            notifyFallback(listener, Fallback.NO_URL, 0);
            return null;
        }
        long buildsEnded;
        synchronized (mLock) {
            if (playerUrl.equals(mReadyUrl)) {
                return null; // the everyday case: nothing to log
            }
            buildsEnded = mBuildsEnded;
        }
        // Outside the lock: it may start a thread, and a build publishes under this lock.
        boolean building = startBuild.start();
        long startMs = android.os.SystemClock.elapsedRealtime();
        Fallback fallback;
        synchronized (mLock) {
            while (true) {
                if (playerUrl.equals(mReadyUrl)) {
                    fallback = Fallback.READY;
                    break;
                }
                if (mRead != null && playerUrl.equals(mRead.playerUrl)) {
                    return mRead;
                }
                if (!building) {
                    fallback = Fallback.NO_BUILD;
                    break;
                }
                if (mBuildsEnded != buildsEnded) {
                    fallback = Fallback.BUILD_ENDED;
                    break;
                }
                try {
                    mLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fallback = Fallback.INTERRUPTED;
                    break;
                }
            }
        }
        notifyFallback(listener, fallback, android.os.SystemClock.elapsedRealtime() - startMs);
        return null;
    }

    private static void notifyFallback(@Nullable Listener listener, Fallback reason, long waitedMs) {
        if (listener != null) {
            listener.onFallback(reason, waitedMs);
        }
    }
}
