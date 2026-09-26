package com.liskovsoft.youtubeapi.app;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.youtubeapi.app.models.AppInfo;
import com.liskovsoft.youtubeapi.app.models.ClientData;
import com.liskovsoft.youtubeapi.app.playerdata.PlayerDataExtractor;
import com.liskovsoft.googlecommon.common.helpers.DefaultHeaders;
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper;
import com.liskovsoft.youtubeapi.common.helpers.AppConstants;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;

import retrofit2.Call;
import retrofit2.Response;

public class AppServiceInt {
    private static final String TAG = AppServiceInt.class.getSimpleName();
    private final AppApi mAppApi;

    public AppServiceInt() {
        mAppApi = RetrofitHelper.create(AppApi.class);
    }

    /**
     * Obtains info with respect of anonymous browsing data (visitor cookie)
     */
    protected AppInfo getAppInfo(String userAgent) {
        String visitorCookie = getData().getVisitorCookie();
        // EU: without an accepted-SOCS consent cookie youtube.com expires VISITOR_INFO1_LIVE
        // instead of setting it, so the replayed visitor cookie below never contains one and the
        // anonymous visitor id silently rotates every refresh (killing signed-out watch history
        // and the personalized anonymous Home). SOCS itself is never echoed back, so append it
        // on every request rather than persisting it.
        if (visitorCookie == null || !visitorCookie.contains("SOCS=")) {
            visitorCookie = Helpers.join("; ", visitorCookie, AppConstants.CONSENT_ACCEPTED_COOKIE);
        }
        Call<AppInfo> wrapper = mAppApi.getAppInfo(userAgent, visitorCookie);
        AppInfo result = null;

        Response<AppInfo> response = RetrofitHelper.getResponse(wrapper);

        if (response != null) {
            //String visitorInfoCookie = RetrofitHelper.getCookie(response, AppConstants.VISITOR_INFO_COOKIE);
            //String visitorPrivacyCookie = RetrofitHelper.getCookie(response, AppConstants.VISITOR_PRIVACY_COOKIE);
            //getData().setVisitorCookie(Helpers.join("; ", visitorInfoCookie, visitorPrivacyCookie));
            getData().setVisitorCookie(
                    nextVisitorCookie(getData().getVisitorCookie(), RetrofitHelper.getCookies(response)));
            result = response.body();
        }

        return result;
    }

    /**
     * NEWTUBE(visitor): the cookie jar to keep after an app-info fetch. Replaying the stored
     * VISITOR_INFO1_LIVE is what keeps the anonymous identity across refreshes: youtube.com/tv
     * answers it with the SAME visitor id and re-issues the cookie (checked 2026-09-25: replaying
     * the whole jar, or VISITOR_INFO1_LIVE plus SOCS alone, got the same id back; only visitorData's
     * timestamp and YNID fields changed). The jar used to be REPLACED by whatever Set-Cookie the
     * answer carried, so an answer without that cookie (an error status, a captive portal, a
     * truncated page) silently erased the identity and the next refresh minted a new visitor.
     * <p>
     * Every cookie the answer sends still replaces the stored set, as before - YSC, YNID, rollout
     * and privacy cookies are re-issued by the server and must not be frozen. Only the identity is
     * carried over, and only when the answer did not issue one (absent, or an empty value). A
     * different id issued by the server is the server replacing the identity: accepted.
     */
    static String nextVisitorCookie(String stored, String received) {
        String storedVisitor = findVisitorCookie(stored);
        if (storedVisitor == null || findVisitorCookie(received) != null) {
            return received;
        }

        StringBuilder merged = new StringBuilder();
        if (received != null) {
            for (String pair : received.split(";")) {
                String trimmed = pair.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith(VISITOR_COOKIE_PREFIX)) {
                    merged.append(trimmed).append("; ");
                }
            }
        }

        return merged.append(storedVisitor).toString();
    }

    private static final String VISITOR_COOKIE_PREFIX = AppConstants.VISITOR_INFO_COOKIE + "=";

    /** The {@code VISITOR_INFO1_LIVE=<id>} pair of a cookie string, or null if absent or empty. */
    private static String findVisitorCookie(String cookies) {
        if (cookies == null) {
            return null;
        }

        for (String pair : cookies.split(";")) {
            String trimmed = pair.trim();
            if (trimmed.startsWith(VISITOR_COOKIE_PREFIX) && trimmed.length() > VISITOR_COOKIE_PREFIX.length()) {
                return trimmed;
            }
        }

        return null;
    }

    public PlayerDataExtractor getPlayerDataExtractor(String playerUrl) {
        return new PlayerDataExtractor(playerUrl);
    }

    protected ClientData getClientData(String clientUrl) {
        if (clientUrl == null) {
            return null;
        }

        Call<ClientData> wrapper = mAppApi.getClientData(clientUrl);
        ClientData clientData = RetrofitHelper.get(wrapper);

        // Seems that legacy script encountered.
        if (clientData == null) {
            clientData = RetrofitHelper.get(mAppApi.getClientData(getLegacyClientUrl(clientUrl)));
        }

        return clientData;
    }
    
    private static String getLegacyClientUrl(String clientUrl) {
        if (clientUrl == null) {
            return null;
        }

        return clientUrl
                .replace("/dg=0/", "/exm=base/ed=1/")
                .replace("/m=base", "/m=main");
    }

    public void invalidateVisitorData() {
        getData().setVisitorCookie(null);
    }

    /**
     * Drops the anonymous browsing identity so the next app-info refresh mints a NEW visitorData.
     * Invalidating the cookie alone is not enough: the visitorData itself lives in the cached app
     * info, so the cache has to go too or the same identity is replayed.
     * <p>
     * NEWTUBE(visitor): a full reset of the PERSISTENT identity (Home, search, /next, signed-out
     * history). No production caller since 2026-09-25: bot challenges no longer rotate any
     * identity on the phone (evidence on VideoInfoService.rotateAnonymousIdentity). Before wiring
     * it again: AppServiceIntCached only re-saves the persisted app info when the player extractor
     * is rebuilt, so the identity minted here is not persisted until the next process (which then
     * pays a youtube.com/tv fetch at cold start).
     */
    public void rotateVisitorData() {
        invalidateVisitorData();
        invalidateCache();
    }

    public void invalidateCache() {
        // NOP
    }

    public boolean isPlayerCacheActual() {
        // NOP
        return false;
    }

    // Moved from AppService

    public String getClientId() {
        // TODO: NPE 1.6K!!!
        ClientData clientData = getClientData();
        return clientData != null ? clientData.getClientId() : null;
    }

    /**
     * Constant used in AuthApi
     */
    public String getClientSecret() {
        return getClientData() != null ? getClientData().getClientSecret() : null;
    }

    /**
     * Used with get_video_info, anonymous search and suggestions
     */
    public String getVisitorData() {
        // TODO: NPE 300!!!
        return getAppInfoData() != null ? getAppInfoData().getVisitorData() : null;
    }

    public String getPlayerUrl() {
        // NOTE: NPE 2.5K
        //return getData().getPlayerUrl() != null ? getData().getPlayerUrl() : mCachedAppInfo != null ? mCachedAppInfo.getPlayerUrl() : null;
        return getAppInfoData() != null ? getAppInfoData().getPlayerUrl() : null;
    }

    public String getClientUrl() {
        // NOTE: NPE 143K!!!
        return getAppInfoData() != null ? getAppInfoData().getClientUrl() : null;
    }

    private AppInfo getAppInfoData() {
        return getAppInfo(DefaultHeaders.APP_USER_AGENT);
    }

    private ClientData getClientData() {
        return getClientData(getClientUrl());
    }

    public PlayerDataExtractor getPlayerDataExtractor() {
        return getPlayerDataExtractor(getPlayerUrl());
    }

    public void refreshCacheIfNeeded() {
        getAppInfoData();
        getClientData();
        getPlayerDataExtractor();
    }

    protected MediaServiceData getData() {
        return MediaServiceData.instance();
    }
}
