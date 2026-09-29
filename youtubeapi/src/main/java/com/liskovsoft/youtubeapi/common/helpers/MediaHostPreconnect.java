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
import org.chromium.net.RequestFinishedInfo;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;

import java.util.Date;
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
    /**
     * NEWTUBE(warm-metrics): how long a finished warm's line may wait for its Cronet metrics. They
     * are delivered on this class's executor right after the request's final callback, so this only
     * bounds a provider that never reports them.
     */
    private static final long METRICS_WAIT_MS = 1_000;

    private static volatile boolean sEnabled;
    private static volatile boolean sEarlyEnabled = true;
    private static volatile RouteAdvisor sRouteAdvisor;
    // Guarded by MediaHostPreconnect.class, including request callbacks and deadline delivery.
    private static final PreconnectGate sGate = new PreconnectGate();
    private static final Map<PreconnectGate.Attempt, WarmJob> sJobs = new HashMap<>();
    private static ScheduledThreadPoolExecutor sExecutor;

    private MediaHostPreconnect() {
    }

    /**
     * NEWTUBE(media-path): lets the app keep the warm on the transport its media will actually use.
     * Measured on Movistar LTE (Pixel 9, 2026-09-25): on a network where the app had already proven
     * that Cronet stalls in TLS to googlevideo (so its media starts on OkHttp), this Cronet warm
     * timed out after 8 s on every open - warming nothing, and holding one of the two in-flight
     * slots the whole time.
     */
    public interface RouteAdvisor {
        /**
         * Called before a Cronet warm of {@code host}, on the caller's thread and without this
         * class's lock. Return true to warm through Cronet as usual; false when the app warmed the
         * host its own way (or wants no warm on this network): no Cronet request is made.
         */
        boolean warmThroughCronet(String host);
    }

    /** Null restores the plain Cronet warm. */
    public static void setRouteAdvisor(RouteAdvisor advisor) {
        sRouteAdvisor = advisor;
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
            String host = Uri.parse(url).getHost();
            RouteAdvisor advisor = sRouteAdvisor;
            if (host != null && advisor != null && !advisor.warmThroughCronet(host)) {
                return;
            }
            warmHost(host);
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
            UrlRequest.Builder builder = engine.newUrlRequestBuilder("https://" + host + "/generate_204",
                    job, sExecutor);
            boolean metrics = attachMetricsListener(builder, job);
            job.mRequest = builder.build();
            sJobs.put(attempt, job);
            job.mDeadline = sExecutor.schedule(() -> job.finish(false, "timeout", true),
                    WARM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            job.mRequest.start();
            // Only a started request reports metrics. Its callbacks need this class's lock, which
            // warmHost holds, so the job cannot finish before this is set.
            job.mMetricsExpected = metrics;
            Log.d(TAG, "preconnecting media host: %s", host);
        } catch (RuntimeException | LinkageError e) {
            job.finish(false, "start-error", true);
        }
    }

    /**
     * NEWTUBE(warm-metrics): the warm's connection setup, split by Cronet. On the Pixel over LTE
     * (2026-09-29, netbench ttff-analysis section 3.2) the warm took ~137 ms on the cjoe edges and
     * ~483 ms on cjol (39 of 52 cjol warms at 400 ms or more), and when it was not ready at the first
     * media request, media-load to first frame was ~490-510 ms instead of ~280. The tight ~650 ms
     * cluster looks like a fallback timer (IPv6 to IPv4, or QUIC to TCP) more than server load, but
     * the release lines could not tell DNS from connect from TLS. Logging only: the request itself
     * is unchanged, and the listener sees just this one request per warm.
     */
    private static boolean attachMetricsListener(UrlRequest.Builder builder, WarmJob job) {
        try {
            builder.setRequestFinishedListener(new RequestFinishedInfo.Listener(sExecutor) {
                @Override
                public void onRequestFinished(RequestFinishedInfo info) {
                    String metrics;
                    try {
                        metrics = describeMetrics(info);
                    } catch (RuntimeException e) {
                        metrics = " metrics=none";
                    }
                    job.onMetrics(metrics);
                }
            });
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false; // a provider without per-request metrics: the line goes out without them
        }
    }

    /**
     * {@code dns= connect= ssl= wait=} in ms (-1 when Cronet reports no such phase, e.g. all three on
     * a reused socket), {@code reused=y|n} and {@code proto=} (h3, h2, http/1.1, or ? when there was
     * no response). connect includes TLS on TCP, and QUIC's handshake: the phases overlap and must
     * not be added up. wait is request sent to response start. No URL: the host is already on the
     * line.
     */
    static String describeMetrics(RequestFinishedInfo info) {
        UrlResponseInfo response = info.getResponseInfo();
        String proto = protocolToken(response != null ? response.getNegotiatedProtocol() : null);
        RequestFinishedInfo.Metrics metrics = info.getMetrics();
        if (metrics == null) {
            return " metrics=none proto=" + proto;
        }
        return " dns=" + elapsedMs(metrics.getDnsStart(), metrics.getDnsEnd())
                + " connect=" + elapsedMs(metrics.getConnectStart(), metrics.getConnectEnd())
                + " ssl=" + elapsedMs(metrics.getSslStart(), metrics.getSslEnd())
                + " wait=" + elapsedMs(metrics.getSendingEnd(), metrics.getResponseStart())
                + " reused=" + (metrics.getSocketReused() ? "y" : "n")
                + " proto=" + proto;
    }

    static long elapsedMs(Date start, Date end) {
        return start == null || end == null || end.before(start) ? -1 : end.getTime() - start.getTime();
    }

    /** One short log token. */
    static String protocolToken(String protocol) {
        if (protocol == null || protocol.isEmpty()) {
            return "?";
        }
        String token = protocol.length() > 16 ? protocol.substring(0, 16) : protocol;
        return token.replaceAll("[^A-Za-z0-9/._+-]", "_");
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
        /** NEWTUBE(warm-metrics): set once the request started with a metrics listener. */
        private boolean mMetricsExpected;
        /** The metrics, when they arrived before {@link #finish} (not the usual order). */
        private String mMetrics;
        /** The finished warm's line, waiting for its metrics (see METRICS_WAIT_MS). */
        private String mPendingLine;
        private ScheduledFuture<?> mLineFallback;

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
                    String line = (succeeded ? "warm " : "warm-failed ")
                            + mAttempt.host + " +" + (nowMs - mStartMs) + "ms"
                            + (succeeded ? "" : " reason=" + reason);
                    if (mMetrics != null || !mMetricsExpected) {
                        logLine(line + (mMetrics != null ? mMetrics : " metrics=none"));
                    } else {
                        // NEWTUBE(warm-metrics): Cronet reports them right after the final callback
                        // (a cancelled request's after its onCanceled), on this same executor, so
                        // the line goes out within a few ms of where it used to, with the same +ms.
                        mPendingLine = line;
                        try {
                            mLineFallback = sExecutor.schedule(() -> onMetrics(" metrics=none"),
                                    METRICS_WAIT_MS, TimeUnit.MILLISECONDS);
                        } catch (RuntimeException e) {
                            onMetrics(" metrics=none");
                        }
                    }
                }
            }
        }

        /** The request's metrics, or the fallback's " metrics=none": whichever comes first counts. */
        void onMetrics(String metrics) {
            synchronized (MediaHostPreconnect.class) {
                if (mPendingLine == null) {
                    if (!mFinished && mMetrics == null) {
                        mMetrics = metrics; // arrived ahead of the final callback
                    }
                    return;
                }
                String line = mPendingLine;
                mPendingLine = null;
                if (mLineFallback != null) {
                    mLineFallback.cancel(false);
                }
                logLine(line + metrics);
            }
        }

        private static void logLine(String line) {
            android.util.Log.d("NetPath", line);
        }
    }
}
