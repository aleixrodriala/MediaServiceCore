package com.liskovsoft.youtubeapi.videoinfo.V2;

import com.liskovsoft.youtubeapi.app.AppService;
import com.liskovsoft.youtubeapi.app.PoTokenGate;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.common.helpers.QueryBuilder;

public class VideoInfoApiHelper {
    public static final class PlayerRequest {
        public final String query;
        public final String visitorData;

        private PlayerRequest(String query, String visitorData) {
            this.query = query;
            this.visitorData = visitorData;
        }
    }

    public static String getVideoInfoQuery(AppClient client, String videoId, String clickTrackingParams) {
        return getVideoInfoRequest(client, videoId, clickTrackingParams).query;
    }

    public static PlayerRequest getVideoInfoRequest(AppClient client, String videoId, String clickTrackingParams) {
        String poToken = PoTokenGate.getPoToken(client, videoId);
        String visitorData = getPlayerVisitorData(client);
        boolean webVisitor = usesWebVisitorData(client);
        long visitorAgeMs = webVisitor ? PoTokenGate.getWebVisitorAgeMs() : -1;
        android.util.Log.d("NetPath", "player-context video=" + safeVideoId(videoId)
                + " client=" + client + " cver=" + client.getClientVersion()
                + " visitorSource=" + (webVisitor ? "web-pot" : "app")
                + " visitor=" + fingerprint(visitorData)
                + " visitorAgeMs=" + visitorAgeMs
                + " playerPot=" + (poToken != null && !poToken.isEmpty() ? "y" : "n"));
        String query = createCheckedQuery(client, videoId, clickTrackingParams,
                client == AppClient.GEO, poToken, visitorData);
        return new PlayerRequest(query, visitorData);
    }

    public static String getPlayerVisitorData(AppClient client) {
        if (usesWebVisitorData(client)) {
            return PoTokenGate.getWebVisitorDataForPlayer();
        }
        return AppService.instance().getVisitorData();
    }

    static boolean usesWebVisitorData(AppClient client) {
        return client.isWebPotRequired() || client == AppClient.ANDROID_VR;
    }

    private static String fingerprint(String value) {
        if (value == null || value.isEmpty()) {
            return "none";
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(10);
            for (int i = 0; i < 5; i++) {
                result.append(String.format(java.util.Locale.US, "%02x", hash[i] & 0xff));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String safeVideoId(String videoId) {
        return videoId != null ? videoId : "?";
    }

    /**
     * NOTE: enableGeoFix - Should use protobuf to bypass geo blocking.
     */
    private static String createCheckedQuery(AppClient client, String videoId, String clickTrackingParams,
                                             boolean enableGeoFix, String poToken, String visitorData) {
        // Important: use only for the clients that don't support auth.
        // Otherwise, google suggestions and history won't work (visitor data bug)
        return new QueryBuilder(client)
                .setVideoId(videoId)
                .setClickTrackingParams(clickTrackingParams)
                .setPoToken(poToken)
                .setVisitorData(visitorData)
                .enableGeoFix(enableGeoFix) // may broke other functionality
                .build();
    }
}
