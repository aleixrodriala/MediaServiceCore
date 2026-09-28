package com.liskovsoft.youtubeapi.videoinfo.V2;

import com.liskovsoft.googlecommon.common.helpers.VisitorFingerprint;
import com.liskovsoft.youtubeapi.app.AppService;
import com.liskovsoft.youtubeapi.app.PoTokenGate;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.common.helpers.QueryBuilder;
import com.liskovsoft.youtubeapi.innertube.ytcfg.YtCfgService;

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
        // The /player REQUEST body, not a media URL: this is the one site allowed to present a
        // Web-minted token for a non-Web client (see PoTokenGate.getPlayerRequestPoToken). The
        // other three PoTokenGate.getPoToken call sites decorate googlevideo URLs and must keep
        // using the media-URL entry point, which refuses to hand a Web token to any non-Web client.
        String poToken = PoTokenGate.getPlayerRequestPoToken(client, videoId);
        // NEWTUBE(web-embed-identity): WEB_EMBED presents the embed page's visitor with the
        // encryptedHostFlags bound to it (see YtCfgService.EmbedIdentity). If the page couldn't be
        // fetched it goes out on the shared Web visitor with no flags and gets the old 152-18
        // refusal; QueryBuilder never fetches flags of its own for it (they'd belong to another
        // visitor).
        YtCfgService.EmbedIdentity embed = client == AppClient.WEB_EMBED
                ? YtCfgService.getEmbedIdentity(videoId) : null;
        String visitorData = embed != null ? embed.visitorData : getPlayerVisitorData(client);
        boolean webVisitor = embed == null && usesWebVisitorData(client);
        long visitorAgeMs = webVisitor ? PoTokenGate.getWebVisitorAgeMs() : -1;
        android.util.Log.d("NetPath", "player-context video=" + safeVideoId(videoId)
                + " client=" + client + " cver=" + client.getClientVersion()
                + " visitorSource=" + (embed != null ? "embed-page" : webVisitor ? "web-pot" : "app")
                + " visitor=" + VisitorFingerprint.of(visitorData)
                + " visitorAgeMs=" + visitorAgeMs
                + " playerPot=" + (poToken != null && !poToken.isEmpty() ? "y" : "n"));
        String query = createCheckedQuery(client, videoId, clickTrackingParams,
                client == AppClient.GEO, poToken, visitorData,
                embed != null ? embed.encryptedHostFlags : null);
        return new PlayerRequest(query, visitorData);
    }

    public static String getPlayerVisitorData(AppClient client) {
        if (usesWebVisitorData(client)) {
            return PoTokenGate.getWebVisitorDataForPlayer();
        }
        return AppService.instance().getVisitorData();
    }

    static boolean usesWebVisitorData(AppClient client) {
        return client.isWebPotRequired() || client == AppClient.ANDROID_VR
                || client == AppClient.VISIONOS;
    }

    private static String safeVideoId(String videoId) {
        return videoId != null ? videoId : "?";
    }

    /**
     * NOTE: enableGeoFix - Should use protobuf to bypass geo blocking.
     */
    private static String createCheckedQuery(AppClient client, String videoId, String clickTrackingParams,
                                             boolean enableGeoFix, String poToken, String visitorData,
                                             String encryptedHostFlags) {
        // Important: use only for the clients that don't support auth.
        // Otherwise, google suggestions and history won't work (visitor data bug)
        return new QueryBuilder(client)
                .setVideoId(videoId)
                .setClickTrackingParams(clickTrackingParams)
                .setPoToken(poToken)
                .setVisitorData(visitorData)
                .setEncryptedHostFlags(encryptedHostFlags)
                .enableGeoFix(enableGeoFix) // may broke other functionality
                .build();
    }
}
