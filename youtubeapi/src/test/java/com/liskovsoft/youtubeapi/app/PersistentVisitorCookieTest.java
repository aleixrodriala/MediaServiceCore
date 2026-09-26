package com.liskovsoft.youtubeapi.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.youtubeapi.app.models.AppInfo;
import com.liskovsoft.youtubeapi.app.models.cached.AppInfoCached;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Proxy;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Offline: an app-info refresh (youtube.com/tv) must replay the stored visitor cookie, and must not
 * lose the anonymous identity when the answer carries no visitor cookie of its own. The persistent
 * visitor drives Home, search, /next and signed-out history, so a silent re-mint fragments them.
 * No network: the AppApi is a stub that records the Cookie header it was handed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PersistentVisitorCookieTest {
    private static final String STORED = "VISITOR_INFO1_LIVE=keepme; YSC=old";
    private AppServiceInt mService;
    private String mSentCookie;
    private Response<AppInfo> mAnswer;

    @Before
    public void setUp() {
        mService = ReflectionHelpers.callConstructor(AppServiceInt.class);
        ReflectionHelpers.setField(mService, "mAppApi", stubApi());
        MediaServiceData.instance().setVisitorCookie(STORED);
    }

    @After
    public void tearDown() {
        MediaServiceData.instance().setVisitorCookie(null);
    }

    @Test
    public void refreshReplaysTheStoredVisitorCookie() {
        mAnswer = ok("VISITOR_INFO1_LIVE=keepme", "YSC=new");

        mService.getAppInfo("ua");

        assertTrue("the refresh must present the identity it wants to keep: " + mSentCookie,
                mSentCookie.contains("VISITOR_INFO1_LIVE=keepme"));
        assertTrue("EU consent rides along or youtube.com expires the visitor instead",
                mSentCookie.contains("SOCS="));
    }

    @Test
    public void answerConfirmingTheIdentityRefreshesTheJar() {
        mAnswer = ok("VISITOR_INFO1_LIVE=keepme", "YSC=new");

        mService.getAppInfo("ua");

        assertEquals("VISITOR_INFO1_LIVE=keepme; YSC=new",
                MediaServiceData.instance().getVisitorCookie());
    }

    @Test
    public void errorAnswerWithoutCookiesKeepsTheIdentity() {
        mAnswer = Response.error(503, ResponseBody.create("busy", (MediaType) null));

        mService.getAppInfo("ua");

        assertEquals("a cookie-less answer used to wipe the jar, so the next refresh re-minted",
                "VISITOR_INFO1_LIVE=keepme", MediaServiceData.instance().getVisitorCookie());
    }

    @Test
    public void emptyVisitorCookieIsNotAReplacementIdentity() {
        mAnswer = ok("YSC=new", "VISITOR_INFO1_LIVE=");

        mService.getAppInfo("ua");

        assertEquals("the other cookie updates land; the empty visitor does not",
                "YSC=new; VISITOR_INFO1_LIVE=keepme", MediaServiceData.instance().getVisitorCookie());
    }

    @Test
    public void answerWithoutVisitorStillTakesTheServersOtherCookies() {
        mAnswer = ok("YSC=new", "__Secure-YNID=fresh", "VISITOR_PRIVACY_METADATA=v2");

        mService.getAppInfo("ua");

        assertEquals("server updates to non-identity cookies must not be frozen",
                "YSC=new; __Secure-YNID=fresh; VISITOR_PRIVACY_METADATA=v2; VISITOR_INFO1_LIVE=keepme",
                MediaServiceData.instance().getVisitorCookie());
    }

    @Test
    public void serverIssuedIdentityReplacesTheStoredOne() {
        mAnswer = ok("VISITOR_INFO1_LIVE=serverchose", "YSC=new");

        mService.getAppInfo("ua");

        assertEquals("the server replacing the visitor is legitimate - accept it",
                "VISITOR_INFO1_LIVE=serverchose; YSC=new",
                MediaServiceData.instance().getVisitorCookie());
    }

    @Test
    public void mergeRules() {
        // Nothing to protect: the answer's jar is taken as is (the pre-existing behaviour).
        assertEquals("YSC=a", AppServiceInt.nextVisitorCookie(null, "YSC=a"));
        assertNull(AppServiceInt.nextVisitorCookie("YSC=a", null));
        assertEquals("VISITOR_INFO1_LIVE=x", AppServiceInt.nextVisitorCookie("YSC=a", "VISITOR_INFO1_LIVE=x"));
        // The server issued an identity (same or different): its jar wins.
        assertEquals("VISITOR_INFO1_LIVE=y; YSC=b",
                AppServiceInt.nextVisitorCookie("VISITOR_INFO1_LIVE=x; YSC=a", "VISITOR_INFO1_LIVE=y; YSC=b"));
        // No identity in the answer: only the stored identity is carried over.
        assertEquals("VISITOR_INFO1_LIVE=x", AppServiceInt.nextVisitorCookie("VISITOR_INFO1_LIVE=x; YSC=a", null));
        assertEquals("YSC=b; VISITOR_INFO1_LIVE=x",
                AppServiceInt.nextVisitorCookie("YSC=a; VISITOR_INFO1_LIVE=x", "YSC=b"));
        // A cookie merely named like the identity is not it.
        assertEquals("VISITOR_INFO1_LIVE_X=1; VISITOR_INFO1_LIVE=x",
                AppServiceInt.nextVisitorCookie("VISITOR_INFO1_LIVE=x", "VISITOR_INFO1_LIVE_X=1"));
    }

    private static Response<AppInfo> ok(String... setCookies) {
        Headers.Builder headers = new Headers.Builder();
        for (String cookie : setCookies) {
            headers.add("Set-Cookie", cookie + "; Domain=.youtube.com; Path=/; Secure; HttpOnly");
        }
        AppInfo body = AppInfoCached.fromString("https://player.invalid/base.js%aic%"
                + "https://client.invalid/base.js%aic%VISITOR%aic%" + System.currentTimeMillis());
        return Response.success(body, headers.build());
    }

    private AppApi stubApi() {
        return (AppApi) Proxy.newProxyInstance(AppApi.class.getClassLoader(), new Class<?>[]{AppApi.class},
                (proxy, method, args) -> {
                    if ("getAppInfo".equals(method.getName()) && args != null && args.length == 2) {
                        mSentCookie = (String) args[1];
                        return answerCall();
                    }
                    throw new AssertionError("unexpected AppApi call " + method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private Call<AppInfo> answerCall() {
        return (Call<AppInfo>) Proxy.newProxyInstance(Call.class.getClassLoader(), new Class<?>[]{Call.class},
                (proxy, method, args) -> {
                    if ("execute".equals(method.getName())) {
                        return mAnswer;
                    }
                    throw new AssertionError("unexpected Call method " + method.getName());
                });
    }
}
