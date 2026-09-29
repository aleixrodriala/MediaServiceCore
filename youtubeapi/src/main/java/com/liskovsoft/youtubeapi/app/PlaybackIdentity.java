package com.liskovsoft.youtubeapi.app;

import androidx.annotation.Nullable;

import com.liskovsoft.googlecommon.common.helpers.VisitorFingerprint;
import com.liskovsoft.youtubeapi.videoinfo.V2.DebugPlaybackWall;

/**
 * NEWTUBE(playback-identity): the anonymous PLAYBACK identity, as distinct from the app's
 * persistent (browse) identity, and its re-roll as the last resort against the one-minute wall.
 *
 * <p>What the playback identity is: the web BotGuard session's visitor (PoTokenProviderImpl), which
 * VISIONOS, ANDROID_VR and the Web family send with every /player request (PlayerSource.Identity
 * WEB_SESSION; X-Goog-Visitor-Id and context.client.visitorData, no cookie) and which the session's
 * streaming token is minted for. Normally it IS the app's persistent visitor (AppService.visitorData,
 * from youtube.com/tv), adopted verbatim so signed-out history stays on one identity. A re-roll makes
 * the session mint a fresh visitor (the visitor_id API) and rebuild BotGuard around it: pot, /player
 * and media URLs keep sharing one visitor. AppService.visitorData - Home, /next, search, the
 * TV_TIZEN / IOS / ANDROID_REEL requests (APP_VISITOR) - is never touched; WEB_EMBED keeps its own
 * embed page identity.
 *
 * <p>After a re-roll the fresh visitor lives until the session's next rebuild (expiry, a recovery's
 * token reset), then the session adopts the app's visitor again - unless keeping is on (the second
 * switch): the fresh visitor then stays the playback identity for PlaybackIdentityBook.WINDOW_MS,
 * persisted, so later videos do not meet the wall on the old visitor first.
 */
public final class PlaybackIdentity {
    private static final PlaybackIdentityBook sBook = new PlaybackIdentityBook();
    private static volatile boolean sKeepEnabled;

    private PlaybackIdentity() {
    }

    public static PlaybackIdentityBook book() {
        return sBook;
    }

    /** The second switch (default off): keep a re-rolled identity for the next opens. */
    public static void setKeepEnabled(boolean enabled) {
        sKeepEnabled = enabled;
        if (!enabled) {
            sBook.dropKept();
        }
    }

    public static boolean isKeepEnabled() {
        return sKeepEnabled;
    }

    /**
     * For PoTokenProviderImpl: the identity the web session adopts instead of the app's persistent
     * visitor, or null (keeping off, nothing kept, or it expired).
     */
    @Nullable
    public static String keptVisitor() {
        return sKeepEnabled ? sBook.kept(System.currentTimeMillis()) : null;
    }

    /**
     * A re-roll for {@code videoId} was armed (PoTokenGate.rerollPlaybackIdentity): the next session
     * build mints the fresh visitor, and {@link #onFreshVisitorAdopted} completes the log line.
     */
    static void arm(String videoId, @Nullable String fromVisitor, int budgetLeft) {
        sBook.setPending(videoId, VisitorFingerprint.of(fromVisitor), budgetLeft, System.currentTimeMillis());
    }

    /**
     * For PoTokenProviderImpl: a re-roll armed in this process or an earlier one is still to be
     * minted - the next session build mints a fresh visitor instead of adopting the app's.
     */
    public static boolean isRerollPending() {
        return sBook.pending(System.currentTimeMillis()) != null;
    }

    /**
     * For PoTokenProviderImpl, on the session build a rotation armed: {@code fresh} is the visitor
     * minted for it (null: the visitor_id API failed and the app's visitor was adopted again).
     */
    public static void onFreshVisitorAdopted(@Nullable String fresh) {
        PlaybackIdentityBook.Pending pending = sBook.takePending(System.currentTimeMillis());
        if (pending == null) {
            return; // a challenge rotation (dormant), or the background mint got there first
        }
        complete(pending, fresh, "session");
    }

    /**
     * NEWTUBE(playback-identity), the background mint (PoTokenGate.rerollPlaybackIdentity, keeping
     * on): {@code fresh} was minted off the playback path right after the wall, while the recovered
     * video plays, so the next open - in this process or after a restart - starts on a ready kept
     * visitor instead of minting it inside its first request (+1.7-3.3 s on the emulators, v22).
     * True when it completed the re-roll: the caller then retires the walled session. False when a
     * session build already completed it, or the mint failed (null): the re-roll stays pending and
     * the next session build mints it as before (refunded if that fails too).
     */
    public static boolean onFreshVisitorMinted(@Nullable String fresh) {
        if (fresh == null || !sKeepEnabled) {
            return false;
        }
        PlaybackIdentityBook.Pending pending = sBook.takePending(System.currentTimeMillis());
        if (pending == null) {
            return false;
        }
        complete(pending, fresh, "background");
        return true;
    }

    private static void complete(PlaybackIdentityBook.Pending pending, @Nullable String fresh, String mint) {
        boolean kept = false;
        if (fresh == null) {
            // The visitor API failed and the app's (walled) visitor was adopted again: nothing was
            // re-rolled, so nothing is spent (Codex sol review of v22).
            sBook.refundLatest(System.currentTimeMillis());
        } else {
            DebugPlaybackWall.lift(fresh);
            if (sKeepEnabled) {
                sBook.keep(fresh, System.currentTimeMillis());
                kept = true;
            }
        }
        android.util.Log.d("NetPath", "playback-identity reroll reason=wall video=" + pending.videoId
                + " from=" + pending.fromFingerprint + " to=" + VisitorFingerprint.of(fresh)
                + " budgetLeft=" + pending.budgetLeft + " fresh=" + (fresh != null ? "y" : "n")
                + " keep=" + (kept ? "y" : "n") + " mint=" + mint);
    }

    /** The re-rolled identity met the wall again: no reason to keep it. */
    public static boolean dropKept() {
        return sBook.dropKept();
    }

    /** Test hook. */
    public static void resetForTest() {
        sKeepEnabled = false;
        sBook.resetForTest();
    }
}
