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
