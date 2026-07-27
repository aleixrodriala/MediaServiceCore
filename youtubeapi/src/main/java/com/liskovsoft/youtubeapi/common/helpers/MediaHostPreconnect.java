package com.liskovsoft.youtubeapi.common.helpers;

import android.net.Uri;

import com.liskovsoft.sharedutils.cronet.CronetManager;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;

import org.chromium.net.CronetEngine;
import org.chromium.net.CronetException;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mobile TTFF: opens a throwaway request to the per-video googlevideo host through the SAME
 * singleton Cronet engine that ExoPlayer's CronetDataSourceFactory wraps. Cronet pools QUIC/H2
 * sessions per host inside the engine, so the DNS + TLS/QUIC handshake is already paid for when the
 * first real media request goes out. Best-effort throughout: every failure is swallowed.
 *
 * <p>Timing is the entire point. A measured cold open on a Pixel 9 warmed the host only after the
 * signature transform had finished, which left the warm request 114ms ahead of the first init
 * segment - not nearly enough, so that segment opened its OWN connection ({@code reused=n}) and paid
 * a 400ms TTFB. Warming from the moment the /player response is parsed instead hands the handshake
 * the whole transform window (200-750ms) as a head start.</p>
 *
 * <p>The host lives in the raw streaming URL and is NOT affected by deciphering - descrambling only
 * rewrites the {@code n} and {@code sig} query params - so the pre-transform URL is a valid source
 * for it.</p>
 *
 * <p>Off by default so the TV path stays unchanged; the mobile flavor enables it at startup.</p>
 */
public final class MediaHostPreconnect {
    private static final String TAG = MediaHostPreconnect.class.getSimpleName();

    private static volatile boolean sEnabled;
    private static volatile boolean sEarlyEnabled = true;
    private static volatile String sLastWarmedHost;
    private static ExecutorService sExecutor;

    private MediaHostPreconnect() {
    }

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    /**
     * Debug-playground hook (debuggable builds only). Turning this off restores the old behaviour
     * where the host is warmed only once format info is published, so both arms of a paired A/B can
     * run from ONE apk - a reinstall between arms takes long enough that the cellular link itself
     * moves, which invalidated an earlier comparison.
     */
    public static void setEarlyEnabled(boolean enabled) {
        sEarlyEnabled = enabled;
    }

    /** Pre-transform warm: a no-op when the early path is disabled for measurement. */
    public static void warmUrlEarly(String url) {
        if (sEarlyEnabled) {
            warmUrl(url);
        }
    }

    /**
     * Warms the host of {@code url}, unless it is already the last host warmed. Repeat calls for the
     * same video (once pre-transform, once when format info is published) collapse into one warm.
     */
    public static void warmUrl(String url) {
        if (!sEnabled || url == null) {
            return;
        }

        try {
            warmHost(Uri.parse(url).getHost());
        } catch (Throwable e) {
            Log.d(TAG, "media host preconnect skipped: %s", e.getMessage());
        }
    }

    private static synchronized void warmHost(String host) {
        if (host == null || host.equals(sLastWarmedHost) || !GlobalPreferences.isInitialized()) {
            return; // nothing to warm, already warm, or too early to reach the Cronet engine
        }

        CronetEngine engine = CronetManager.getEngine(GlobalPreferences.sInstance.getContext());
        if (engine == null) {
            return;
        }

        if (sExecutor == null) {
            sExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "MediaHostPreconnect");
                t.setDaemon(true);
                return t;
            });
        }

        sLastWarmedHost = host;
        engine.newUrlRequestBuilder("https://" + host + "/generate_204",
                new NoopUrlCallback(host), sExecutor).build().start();
        Log.d(TAG, "preconnecting media host: %s", host);
    }

    private static class NoopUrlCallback extends UrlRequest.Callback {
        private final String mHost;
        private final long mStartMs = android.os.SystemClock.elapsedRealtime();

        NoopUrlCallback(String host) {
            mHost = host;
        }

        @Override
        public void onRedirectReceived(UrlRequest request, UrlResponseInfo info, String newLocationUrl) {
            request.followRedirect();
        }

        @Override
        public void onResponseStarted(UrlRequest request, UrlResponseInfo info) {
            request.read(java.nio.ByteBuffer.allocateDirect(1024));
        }

        @Override
        public void onReadCompleted(UrlRequest request, UrlResponseInfo info, java.nio.ByteBuffer byteBuffer) {
            byteBuffer.clear();
            request.read(byteBuffer);
        }

        @Override
        public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
            // Connection is warm. One line under the NetPath tag so a drive session can tell whether
            // the first media chunk pays a handshake (warm-complete vs first-chunk order).
            android.util.Log.d("NetPath", "warm " + mHost + " +"
                    + (android.os.SystemClock.elapsedRealtime() - mStartMs) + "ms");
        }

        @Override
        public void onFailed(UrlRequest request, UrlResponseInfo info, CronetException error) {
            // best-effort warm; ignore
        }
    }
}
