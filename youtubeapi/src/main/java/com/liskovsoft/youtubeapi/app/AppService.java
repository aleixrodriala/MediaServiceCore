package com.liskovsoft.youtubeapi.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.youtubeapi.app.playerdata.PlayerDataExtractor;
import com.liskovsoft.youtubeapi.auth.V1.AuthApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import kotlin.Pair;

public class AppService {
    private static volatile AppService sInstance;
    private final AppServiceInt mAppServiceInt;
    private String mClientPlaybackNonce;

    private AppService() {
        mAppServiceInt = new AppServiceIntCached();
    }

    public static AppService instance() {
        AppService result = sInstance;
        if (result == null) {
            synchronized (AppService.class) {
                result = sInstance;
                if (result == null) {
                    result = new AppService();
                    sInstance = result;
                }
            }
        }

        return result;
    }

    /**
     * Extracts signature used in music videos
     */
    public String extractSig(String sParam) {
        if (sParam == null) {
            return null;
        }

        return extractSig(Collections.singletonList(sParam)).get(0);
    }

    /**
     * Extracts signature used in music videos
     */
    public List<String> extractSig(List<String> sParams) {
        if (mAppServiceInt.getPlayerDataExtractor() == null) {
            return null;
        }

        return mAppServiceInt.getPlayerDataExtractor().extractSig(sParams);
    }

    public String extractNSig(String nParam) {
        if (nParam == null || mAppServiceInt.getPlayerDataExtractor() == null) {
            return null;
        }

        return mAppServiceInt.getPlayerDataExtractor().extractNSig(nParam);
    }

    /**
     * nParams - throttle params<br/>
     * sParams - signature used in music videos
     */
    public Pair<List<String>, List<String>> bulkSigExtract(List<String> nParams, List<String> sParams) {
        if (Helpers.allNulls(nParams, sParams) || mAppServiceInt.getPlayerDataExtractor() == null) {
            return null;
        }

        return mAppServiceInt.getPlayerDataExtractor().bulkSigExtract(nParams, sParams);
    }

    public List<String> extractNSig(List<String> nParams) {
        if (Helpers.allNulls(nParams)) {
            return null;
        }

        List<String> result = new ArrayList<>();

        String previousNParam = null;
        String previousNSig = null;

        for (String nParam : nParams) {
            if (Helpers.equals(nParam, previousNParam)) {
                result.add(previousNSig);
                continue;
            }

            String nSig = extractNSig(nParam);

            result.add(nSig);

            previousNParam = nParam;
            previousNSig = nSig;
        }

        return result;
    }

    public synchronized void resetClientPlaybackNonce() {
        mClientPlaybackNonce = null;
    }

    /**
     * NOTE: Unique per video info instance<br/>
     * A nonce is a unique value chosen by an entity in a protocol, and it is used to protect that entity against attacks which fall under the very large umbrella of "replay".
     */
    public synchronized String getClientPlaybackNonce() {
        if (mClientPlaybackNonce != null) {
            return mClientPlaybackNonce;
        }

        if (mAppServiceInt.getPlayerDataExtractor() == null) {
            return null;
        }

        long startMs = android.os.SystemClock.elapsedRealtime();
        mClientPlaybackNonce = mAppServiceInt.getPlayerDataExtractor().createClientPlaybackNonce();
        android.util.Log.d("NetPath", "player-cpn complete ms="
                + (android.os.SystemClock.elapsedRealtime() - startMs)
                + " result=" + (mClientPlaybackNonce != null ? "ok" : "missing"));

        return mClientPlaybackNonce;
    }

    /**
     * NEWTUBE(player-js-gate): the cpn and signatureTimestamp of a /player request, when the
     * current player is still being validated. See {@link PlayerJsReadAhead}.
     */
    public static final class ReadAheadPlayerData {
        public final String clientPlaybackNonce;
        public final String signatureTimestamp;

        ReadAheadPlayerData(String clientPlaybackNonce, String signatureTimestamp) {
            this.clientPlaybackNonce = clientPlaybackNonce;
            this.signatureTimestamp = signatureTimestamp;
        }
    }

    /** NEWTUBE(player-js-gate): see {@link AppServiceIntCached#setPlayerJsReadAhead}. */
    public static void setPlayerJsReadAhead(boolean enabled) {
        AppServiceIntCached.setPlayerJsReadAhead(enabled);
    }

    /**
     * NEWTUBE(player-js-gate): for a /player request whose answer needs no signature/n solve. When
     * the current player has not been validated yet, waits only until its JS is read and returns
     * that player's signatureTimestamp with this video's cpn; the validation goes on in the
     * background. Null when the extractor is built already or nothing could be read ahead: then
     * {@link #getClientPlaybackNonce()} and {@link #getSignatureTimestamp()} answer, as before.
     * <p>
     * The cpn is the one {@link #getClientPlaybackNonce()} keeps for this video (history reports
     * it): made here from the read-ahead cpn code when this video has none yet. The monitor is taken
     * to make and keep it, never while waiting for the player.
     */
    @Nullable
    public ReadAheadPlayerData getReadAheadPlayerData() {
        PlayerJsReadAhead.Data read = mAppServiceInt.awaitPlayerJsReadAhead();
        if (read == null) {
            return null;
        }

        String cpn;
        synchronized (this) {
            // Made and kept in one hold, like getClientPlaybackNonce (a few ms of J2V8, no wait).
            if (mClientPlaybackNonce == null) {
                long startMs = android.os.SystemClock.elapsedRealtime();
                mClientPlaybackNonce = PlayerDataExtractor.clientPlaybackNonceFromCode(read.cpnCode);
                android.util.Log.d("NetPath", "player-cpn complete ms="
                        + (android.os.SystemClock.elapsedRealtime() - startMs)
                        + " result=" + (mClientPlaybackNonce != null ? "ok" : "missing") + " readAhead=y");
            }
            cpn = mClientPlaybackNonce;
        }

        return new ReadAheadPlayerData(cpn, read.signatureTimestamp);
    }

    /** NEWTUBE(player-js-gate): a player read ahead is still being validated in the background. */
    public boolean isPlayerJsValidationPending() {
        return mAppServiceInt.isPlayerJsValidationPending();
    }

    /**
     * Constant used in {@link AuthApi}
     */
    public String getClientId() {
        return mAppServiceInt.getClientId();
    }

    /**
     * Constant used in {@link AuthApi}
     */
    public String getClientSecret() {
        return mAppServiceInt.getClientSecret();
    }

    /**
     * Used in get_video_info
     */
    public String getSignatureTimestamp() {
        if (mAppServiceInt.getPlayerDataExtractor() == null) {
            return null;
        }

        return mAppServiceInt.getPlayerDataExtractor().getSignatureTimestamp();
    }

    /**
     * Used with get_video_info, anonymous search and suggestions
     */
    public String getVisitorData() {
        return mAppServiceInt.getVisitorData();
    }

    public void invalidateCache() {
        mAppServiceInt.invalidateCache();
    }

    public void refreshCacheIfNeeded() {
        mAppServiceInt.refreshCacheIfNeeded();
    }

    /**
     * Visitor data is bound to specific js files versions.<br/>
     * After reset user will get the latest js file versions.
     */
    public void invalidateVisitorData() {
        mAppServiceInt.invalidateVisitorData();
    }

    /** See {@link AppServiceInt#rotateVisitorData()}. */
    public void rotateVisitorData() {
        mAppServiceInt.rotateVisitorData();
    }

    public boolean isPlayerCacheActual() {
        return mAppServiceInt.isPlayerCacheActual();
    }

    @NonNull
    public Context getContext() {
        Context context = GlobalPreferences.isInitialized() ? GlobalPreferences.sInstance.getContext() : null;

        if (context == null) {
            throw new IllegalStateException("The Context isn't initialized yet");
        }

        return context;
    }
}
