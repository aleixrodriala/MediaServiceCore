package com.liskovsoft.youtubeapi.lounge.sender;

import androidx.annotation.Nullable;

import okhttp3.HttpUrl;

import java.security.SecureRandom;
import java.util.Random;

/**
 * Sender-side (device=REMOTE_CONTROL) Lounge urls and params.<br/>
 * Counterpart of the receiver-side {@link com.liskovsoft.youtubeapi.lounge.BindParams}
 * (device=LOUNGE_SCREEN) — kept separate on purpose.
 */
public class SenderParams {
    public static final String ORIGIN = "https://www.youtube.com";
    // Browser UA is the ytcast-verified safe choice for Lounge calls
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/96.0.4664.45 Safari/537.36";
    private static final String BIND_URL = "https://www.youtube.com/api/lounge/bc/bind";
    private static final String DEVICE = "REMOTE_CONTROL";
    private static final String APP = "youtube-desktop";
    private static final String VER = "8";
    private static final String CVER = "1";
    private static final int ZX_LENGTH = 12;
    private static final Random sRandom = new SecureRandom();

    private SenderParams() {
    }

    /**
     * Initial bind (obtain SID/gsessionid): everything in the query, EMPTY post body.
     */
    public static String createInitialBindUrl(String screenName, String deviceId, String loungeToken, int rid) {
        return baseBuilder(screenName, deviceId, loungeToken)
                .addQueryParameter("CVER", CVER)
                .addQueryParameter("RID", String.valueOf(rid))
                .addQueryParameter("zx", generateZx())
                .build()
                .toString();
    }

    /**
     * Persistent long-poll GET: RID=rpc, TYPE=xmlhttp, CI=0 keeps the connection open,
     * AID = last processed message index (server replays anything newer).
     */
    public static String createStreamUrl(String screenName, String deviceId, String loungeToken,
                                         String sessionId, String gSessionId, int aid) {
        return baseBuilder(screenName, deviceId, loungeToken)
                .addQueryParameter("RID", "rpc")
                .addQueryParameter("SID", sessionId)
                .addQueryParameter("CI", "0")
                .addQueryParameter("AID", String.valueOf(aid))
                .addQueryParameter("gsessionid", gSessionId)
                .addQueryParameter("TYPE", "xmlhttp")
                .addQueryParameter("zx", generateZx())
                .build()
                .toString();
    }

    /**
     * Command POST url (body carries the form-encoded message batch).
     */
    public static String createCommandUrl(String loungeToken, String sessionId, String gSessionId, int rid) {
        return HttpUrl.get(BIND_URL).newBuilder()
                .addQueryParameter("CVER", CVER)
                .addQueryParameter("RID", String.valueOf(rid))
                .addQueryParameter("SID", sessionId)
                .addQueryParameter("VER", VER)
                .addQueryParameter("gsessionid", gSessionId)
                .addQueryParameter("loungeIdToken", loungeToken)
                .build()
                .toString();
    }

    private static HttpUrl.Builder baseBuilder(String screenName, String deviceId, String loungeToken) {
        return HttpUrl.get(BIND_URL).newBuilder()
                .addQueryParameter("device", DEVICE)
                .addQueryParameter("id", deviceId)
                .addQueryParameter("name", screenName)
                .addQueryParameter("app", APP)
                .addQueryParameter("loungeIdToken", loungeToken)
                .addQueryParameter("VER", VER);
    }

    /**
     * Cache-busting string: 12 random lowercase letters.
     */
    public static String generateZx() {
        StringBuilder result = new StringBuilder(ZX_LENGTH);

        for (int i = 0; i < ZX_LENGTH; i++) {
            result.append((char) ('a' + sRandom.nextInt(26)));
        }

        return result.toString();
    }

    /**
     * Strip all whitespace (unicode incl.) and dashes from a user-entered TV code.
     */
    @Nullable
    public static String normalizePairingCode(@Nullable String tvCode) {
        if (tvCode == null) {
            return null;
        }

        StringBuilder result = new StringBuilder(tvCode.length());

        for (int i = 0; i < tvCode.length(); i++) {
            char c = tvCode.charAt(i);

            if (!Character.isWhitespace(c) && c != ' ' && c != '-') {
                result.append(c);
            }
        }

        return result.toString();
    }
}
