package com.liskovsoft.youtubeapi.lounge.sender;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.CastEvent;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * A live sender-side (device=REMOTE_CONTROL) Lounge session: remote-role bind,
 * streaming long-poll (raw OkHttp, like the receiver-side
 * {@link com.liskovsoft.youtubeapi.lounge.LoungeService} stream handling) and command POSTs.<br/>
 * <br/>
 * Counters (don't conflate): RID per POST request, ofs per outgoing message
 * (resets to 0 on re-bind), AID per received message (dedupe + replay cursor).<br/>
 * <br/>
 * Reconnect ladder: 400/404 (Unknown SID) — re-bind with the same token;
 * 410/401/403 or client-side expiration — re-mint the lounge token, then re-bind;
 * transient errors — bounded quadratic backoff; give up — terminal disconnected event.<br/>
 * <br/>
 * Threading: {@link #start()} blocks on the caller's (io) thread until the session dies.
 * {@link #stop()} and the send methods may be called from any thread.
 */
public class LoungeSenderSession {
    private static final String TAG = LoungeSenderSession.class.getSimpleName();
    private static final long COMMAND_TIMEOUT_MS = 30_000; // ytcast's Lounge call timeout
    // noop keepalives arrive roughly every 30s; a whole minute of silence is a hang
    private static final long STREAM_READ_TIMEOUT_MS = 60_000;
    private static final int MAX_RETRIES = 10;
    private static final long MAX_BACKOFF_MS = 30_000;

    public interface EventListener {
        void onEvent(CastEvent event);
    }

    public interface TokenRefresher {
        /**
         * Re-mint the lounge token (get_lounge_token_batch by screenId).
         *
         * @return fresh token or null when the screen is unknown/unpaired
         */
        @Nullable
        String refreshToken();
    }

    private final String mScreenName;
    private final String mDeviceId;
    private final TokenRefresher mTokenRefresher;
    private final EventListener mListener;
    private final OkHttpClient mCommandClient;
    private final OkHttpClient mStreamClient;
    /**
     * Guards mSid/mGSessionId/mRid/mOfs and serializes bind vs command POSTs.
     * Never held while blocked in the stream read.
     */
    private final Object mBindLock = new Object();

    private volatile String mLoungeToken;
    private volatile boolean mStopped;
    private volatile boolean mConnected;
    private volatile boolean mDisconnectEmitted;
    private volatile Call mStreamCall;
    private volatile int mBindGeneration;
    private String mSessionId;
    private String mGSessionId;
    private int mRid;
    private int mOfs;
    private volatile int mAid = -1;

    public LoungeSenderSession(String loungeToken, String screenName, String deviceId,
                               TokenRefresher tokenRefresher, EventListener listener) {
        mLoungeToken = loungeToken;
        mScreenName = screenName;
        mDeviceId = deviceId;
        mTokenRefresher = tokenRefresher;
        mListener = listener;
        mCommandClient = new OkHttpClient.Builder()
                .connectTimeout(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
        mStreamClient = new OkHttpClient.Builder()
                .readTimeout(STREAM_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * Bind and stream events until the session dies or {@link #stop()} is called.<br/>
     * Throws only when the very FIRST bind fails; later failures run the reconnect
     * ladder and, when unrecoverable, surface as a disconnected event + normal return.
     */
    public void start() throws IOException {
        synchronized (mBindLock) {
            bind(); // first bind: let errors propagate to the caller
        }

        mListener.onEvent(CastEvent.connected());

        int retries = 0;

        while (!mStopped) {
            int generation = mBindGeneration;

            try {
                streamOnce();
                retries = 0; // clean EOF is normal long-poll termination: re-poll with updated AID
            } catch (HttpStatusException e) {
                if (mStopped) {
                    break;
                }

                if (generation != mBindGeneration) {
                    continue; // a command thread already re-bound while we were reading
                }

                Log.e(TAG, "Stream failed: http %s", e.getCode());

                if (isSessionError(e.getCode())) {
                    if (!recoverSession(e.getCode())) {
                        return; // terminal, disconnected already emitted
                    }
                    retries = 0;
                } else { // 502 and friends: transient
                    retries++;

                    if (retries > MAX_RETRIES) {
                        emitDisconnected("http " + e.getCode());
                        return;
                    }

                    if (!backoff(retries)) {
                        break;
                    }
                }
            } catch (IOException e) {
                if (mStopped) {
                    break;
                }

                retries++;
                Log.e(TAG, "Stream error (retry %s): %s", retries, e.getMessage());

                if (retries > MAX_RETRIES) {
                    emitDisconnected("network error");
                    return;
                }

                if (!backoff(retries)) {
                    break;
                }
            }
        }

        Log.d(TAG, "Session stopped");
        mConnected = false;
    }

    /**
     * Interrupts the long-poll and tears the session down. Safe from any thread.
     */
    public void stop() {
        mStopped = true;
        mConnected = false;

        Call streamCall = mStreamCall;

        if (streamCall != null) {
            streamCall.cancel();
        }
    }

    public boolean isConnected() {
        return mConnected && !mStopped;
    }

    /**
     * Posts a command. On session errors runs the reconnect ladder and retries once.
     * Failures beyond that are logged and dropped (fire-and-forget, like ytcast).
     */
    public void sendCommand(SenderCommand command) {
        if (mStopped) {
            Log.w(TAG, "Can't send %s. Session is stopped.", command.getName());
            return;
        }

        synchronized (mBindLock) {
            try {
                int code = postCommand(command);

                if (isSuccess(code)) {
                    return;
                }

                if (!isSessionError(code)) {
                    Log.e(TAG, "Command %s failed: http %s. Dropping.", command.getName(), code);
                    return;
                }

                Log.w(TAG, "Command %s failed: http %s. Recovering session...", command.getName(), code);

                if (!recoverSession(code)) {
                    return; // terminal, disconnected already emitted
                }

                int retryCode = postCommand(command);

                if (!isSuccess(retryCode)) {
                    Log.e(TAG, "Command %s failed again: http %s. Dropping.", command.getName(), retryCode);
                }
            } catch (IOException e) {
                Log.e(TAG, "Command %s failed: %s", command.getName(), e.getMessage());
            }
        }
    }

    /**
     * One step of the reconnect ladder for a session-level http error.
     * May be called with or without mBindLock held (re-acquires as needed).
     *
     * @return true when a fresh bind succeeded
     */
    private boolean recoverSession(int code) {
        try {
            if (needsTokenRefresh(code) && !refreshToken()) {
                emitDisconnected("lounge token expired (unpaired?)");
                stop();
                return false;
            }

            synchronized (mBindLock) {
                bind();
            }

            return true;
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Re-bind failed: %s", e.getMessage());

            // Last rung: 400/404 didn't need a fresh token — try once more with one
            if (!needsTokenRefresh(code) && refreshToken()) {
                try {
                    synchronized (mBindLock) {
                        bind();
                    }

                    return true;
                } catch (IOException | RuntimeException e2) {
                    Log.e(TAG, "Re-bind with fresh token failed: %s", e2.getMessage());
                }
            }

            emitDisconnected("session recovery failed");
            stop();
            return false;
        }
    }

    /**
     * Remote-role bind (B.1 ytcast variant): everything in the query, empty POST body.
     * Resets ofs/AID; SID/gsessionid parsed from the chunked framing.<br/>
     * Call with mBindLock held.
     */
    private void bind() throws IOException {
        Log.d(TAG, "Binding...");

        // A concurrent long-poll on the old SID would corrupt the AID cursor — cut it
        Call streamCall = mStreamCall;

        if (streamCall != null) {
            streamCall.cancel();
        }

        mSessionId = null;
        mGSessionId = null;
        mAid = -1;

        String url = SenderParams.createInitialBindUrl(mScreenName, mDeviceId, mLoungeToken, ++mRid);
        Request request = withHeaders(new Request.Builder().url(url))
                .post(RequestBody.create(null, new byte[0])) // empty body, no Content-Type
                .build();

        try (Response response = mCommandClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new HttpStatusException(response.code());
            }

            // Single framed chunk; remaining messages describe current receiver state
            LoungeStreamParser.readStream(response.body().byteStream(), this::handleMessage);
        }

        if (mSessionId == null || mGSessionId == null) {
            throw new IOException("Bind response missing SID/gsessionid");
        }

        mOfs = 0;
        mBindGeneration++;
        mConnected = true;

        Log.d(TAG, "Bound. SID: %s, gsessionid: %s", mSessionId, mGSessionId);
    }

    /**
     * One long-poll GET (CI=0): blocks streaming events until server EOF, error or cancel.
     */
    private void streamOnce() throws IOException {
        String url;

        synchronized (mBindLock) {
            url = SenderParams.createStreamUrl(mScreenName, mDeviceId, mLoungeToken, mSessionId, mGSessionId, mAid);
        }

        Request request = withHeaders(new Request.Builder().url(url)).get().build();
        Call call = mStreamClient.newCall(request);
        mStreamCall = call;

        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                throw new HttpStatusException(response.code());
            }

            Log.d(TAG, "Listening for events (AID %s)...", mAid);

            LoungeStreamParser.readStream(response.body().byteStream(), this::handleMessage);
        } finally {
            mStreamCall = null;
        }
    }

    private void handleMessage(LoungeMessage message) {
        int index = message.getIndex();
        int aid = mAid;

        if (index <= aid) {
            Log.d(TAG, "Skipping replayed message %s (AID %s)", index, aid);
            return; // duplicate delivery on reconnect
        }

        if (aid >= 0 && index > aid + 1) {
            Log.d(TAG, "Missed messages between %s and %s", aid, index);
        }

        mAid = index;

        String type = message.getType();

        if (type == null) {
            return;
        }

        switch (type) {
            case LoungeMessage.TYPE_SESSION_ID:
                mSessionId = message.getStringArg();
                break;
            case LoungeMessage.TYPE_G_SESSION_ID:
                mGSessionId = message.getStringArg();
                break;
            case LoungeMessage.TYPE_NOOP:
                break; // keepalive (still advances AID)
            case LoungeMessage.TYPE_LOUNGE_SCREEN_DISCONNECTED:
                Log.d(TAG, "Screen ended the session");
                emitDisconnected("screen disconnected");
                stop();
                break;
            default:
                CastEvent event = SenderEvents.toCastEvent(message);

                if (event != null) {
                    mListener.onEvent(event);
                } else {
                    // Unrecognized events are ignorable by design (see spec UNKNOWNs)
                    Log.d(TAG, "Ignoring event: %s", type);
                }
        }
    }

    /**
     * @return http status code (ofs advances only on success — a failed POST
     *         triggers a re-bind which resets the counter anyway)
     */
    private int postCommand(SenderCommand command) throws IOException {
        String url = SenderParams.createCommandUrl(mLoungeToken, mSessionId, mGSessionId, ++mRid);

        FormBody.Builder form = new FormBody.Builder();

        for (Map.Entry<String, String> entry : command.encode(mOfs).entrySet()) {
            form.add(entry.getKey(), entry.getValue());
        }

        Request request = withHeaders(new Request.Builder().url(url)).post(form.build()).build();

        try (Response response = mCommandClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                Log.d(TAG, "Command %s sent (ofs %s)", command.getName(), mOfs);
                mOfs++; // count=1 per POST
            }

            return response.code();
        }
    }

    private boolean refreshToken() {
        Log.d(TAG, "Re-minting lounge token...");

        String token = null;

        try {
            token = mTokenRefresher.refreshToken();
        } catch (RuntimeException e) { // e.g. network wrapped as IllegalStateException
            Log.e(TAG, "Token refresh error: %s", e.getMessage());
        }

        if (token == null) {
            return false;
        }

        mLoungeToken = token;
        return true;
    }

    /**
     * @return false when interrupted (disposal) — stop looping
     */
    private boolean backoff(int retry) {
        long delayMs = Math.min(retry * retry * 500L, MAX_BACKOFF_MS);

        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException e) {
            Log.d(TAG, "Backoff interrupted. Stopping.");
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void emitDisconnected(String reason) {
        mConnected = false;

        if (mDisconnectEmitted) {
            return;
        }

        mDisconnectEmitted = true;
        mListener.onEvent(CastEvent.disconnected(reason));
    }

    private static boolean isSessionError(int code) {
        return code == 400 || code == 404 || code == 410 || code == 401 || code == 403;
    }

    private static boolean needsTokenRefresh(int code) {
        return code == 410 || code == 401 || code == 403;
    }

    private static boolean isSuccess(int code) {
        return code >= 200 && code < 300;
    }

    private static Request.Builder withHeaders(Request.Builder builder) {
        return builder
                .header("Origin", SenderParams.ORIGIN)
                .header("User-Agent", SenderParams.USER_AGENT);
    }

    private static class HttpStatusException extends IOException {
        private final int mCode;

        HttpStatusException(int code) {
            super("HTTP " + code);
            mCode = code;
        }

        public int getCode() {
            return mCode;
        }
    }
}
