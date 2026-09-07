package com.liskovsoft.youtubeapi.common.helpers;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.SystemClock;

import com.liskovsoft.sharedutils.cronet.CronetManager;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;

import org.chromium.net.CronetEngine;
import org.chromium.net.CronetException;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
    private static final long WARM_TIMEOUT_MS = 8_000;
    private static final int MAX_WARM_BODY_BYTES = 4_096;
    private static final int MAX_WARM_REDIRECTS = 3;

    private static volatile boolean sEnabled;
    private static volatile boolean sEarlyEnabled = true;
    // Guarded by MediaHostPreconnect.class, including request callbacks and deadline delivery.
    private static final PreconnectGate sGate = new PreconnectGate();
    private static final Map<PreconnectGate.Attempt, WarmJob> sJobs = new HashMap<>();
    private static ScheduledThreadPoolExecutor sExecutor;

    private MediaHostPreconnect() {
    }

    public static synchronized void setEnabled(boolean enabled) {
        sEnabled = enabled;
        if (!enabled) {
            updateNetwork(null);
        }
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
     * Warms the host of {@code url}. Early/late calls share an in-flight request or a recently
     * successful warm on this network; failure and idle connections become eligible again.
     */
    public static void warmUrl(String url) {
        if (!sEnabled || url == null) {
            return;
        }

        try {
            warmHost(Uri.parse(url).getHost());
        } catch (Throwable e) {
            Log.d(TAG, "media host preconnect skipped: %s", e.getClass().getSimpleName());
        }
    }

    private static synchronized void warmHost(String host) {
        if (!sEnabled || host == null || !GlobalPreferences.isInitialized()) {
            return;
        }

        Context context = GlobalPreferences.sInstance.getContext();
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = manager != null ? manager.getActiveNetwork() : null;
        updateNetwork(network);
        if (network == null) {
            return; // do not spend a speculative request while disconnected
        }

        CronetEngine engine = CronetManager.getEngine(context);
        if (engine == null) {
            return;
        }

        if (sExecutor == null) {
            sExecutor = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "MediaHostPreconnect");
                t.setDaemon(true);
                return t;
            });
            sExecutor.setRemoveOnCancelPolicy(true);
        }

        PreconnectGate.Attempt attempt = sGate.tryStart(host, SystemClock.elapsedRealtime());
        if (attempt == null) {
            return;
        }
        WarmJob job = new WarmJob(attempt);
        try {
            job.mRequest = engine.newUrlRequestBuilder("https://" + host + "/generate_204",
                    job, sExecutor).build();
            sJobs.put(attempt, job);
            job.mDeadline = sExecutor.schedule(() -> job.finish(false, "timeout", true),
                    WARM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            job.mRequest.start();
            Log.d(TAG, "preconnecting media host: %s", host);
        } catch (RuntimeException | LinkageError e) {
            job.finish(false, "start-error", true);
        }
    }

    /** Called under the class lock, so discarded requests are cancelled before new ones start. */
    private static void updateNetwork(Network network) {
        for (PreconnectGate.Attempt attempt : sGate.changeNetwork(network)) {
            WarmJob job = sJobs.get(attempt);
            if (job != null) {
                job.finish(false, "network-change", true);
            }
        }
    }

    private static class WarmJob extends UrlRequest.Callback {
        private final PreconnectGate.Attempt mAttempt;
        private final long mStartMs = SystemClock.elapsedRealtime();
        private UrlRequest mRequest;
        private ScheduledFuture<?> mDeadline;
        private boolean mFinished;
        private int mBodyBytes;
        private int mRedirects;

        WarmJob(PreconnectGate.Attempt attempt) {
            mAttempt = attempt;
        }

        @Override
        public void onRedirectReceived(UrlRequest request, UrlResponseInfo info, String newLocationUrl) {
            synchronized (MediaHostPreconnect.class) {
                if (mFinished) {
                    return;
                }
                if (++mRedirects > MAX_WARM_REDIRECTS) {
                    finish(false, "redirect-limit", true);
                } else {
                    request.followRedirect();
                }
            }
        }

        @Override
        public void onResponseStarted(UrlRequest request, UrlResponseInfo info) {
            synchronized (MediaHostPreconnect.class) {
                if (!mFinished) {
                    request.read(java.nio.ByteBuffer.allocateDirect(1024));
                }
            }
        }

        @Override
        public void onReadCompleted(UrlRequest request, UrlResponseInfo info, java.nio.ByteBuffer byteBuffer) {
            synchronized (MediaHostPreconnect.class) {
                if (mFinished) {
                    return;
                }
                mBodyBytes += byteBuffer.position();
                if (mBodyBytes >= MAX_WARM_BODY_BYTES) {
                    finish(false, "body-limit", true);
                } else {
                    byteBuffer.clear();
                    request.read(byteBuffer);
                }
            }
        }

        @Override
        public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
            finish(true, null, false);
        }

        @Override
        public void onFailed(UrlRequest request, UrlResponseInfo info, CronetException error) {
            finish(false, "request-error", false);
        }

        @Override
        public void onCanceled(UrlRequest request, UrlResponseInfo info) {
            finish(false, "cancelled", false);
        }

        void finish(boolean succeeded, String reason, boolean cancelRequest) {
            synchronized (MediaHostPreconnect.class) {
                if (mFinished) {
                    return;
                }
                mFinished = true;
                if (mDeadline != null) {
                    mDeadline.cancel(false);
                }
                sJobs.remove(mAttempt);
                long nowMs = SystemClock.elapsedRealtime();
                boolean current = sGate.complete(mAttempt, succeeded, nowMs);
                if (cancelRequest && mRequest != null) {
                    try {
                        mRequest.cancel();
                    } catch (RuntimeException ignored) {
                        // The job is already retired; a provider cancellation error cannot pin it.
                    }
                }
                if (current) {
                    // Host only: signed input URLs and response/error details never reach this log.
                    android.util.Log.d("NetPath", (succeeded ? "warm " : "warm-failed ")
                            + mAttempt.host + " +" + (nowMs - mStartMs) + "ms"
                            + (succeeded ? "" : " reason=" + reason));
                }
            }
        }
    }
}
