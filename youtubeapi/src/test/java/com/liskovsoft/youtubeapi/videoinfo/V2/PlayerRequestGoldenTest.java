package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper;
import com.liskovsoft.youtubeapi.app.AppService;
import com.liskovsoft.youtubeapi.app.potokennp2.PoTokenProviderImpl;
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenResult;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.common.helpers.DebugRequestOverrides;
import com.liskovsoft.youtubeapi.innertube.ytcfg.YtCfgService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import okhttp3.Request;
import okio.Buffer;
import retrofit2.Call;

/**
 * NEWTUBE(source-catalog): the exact /player request every AppClient sends today - method, URL,
 * Retrofit-level headers, the auth flag signed out and signed in, and the body bytes - pinned in
 * golden files, so the source-catalog refactor (netbench DESIGN phase 3) can prove it changes
 * nothing on the wire.
 *
 * <p>The real request path runs: VideoInfoService.getVideoInfo(client, ...) ->
 * VideoInfoApiHelper (PO-token selection, visitor choice, embed identity) -> QueryBuilder ->
 * Retrofit. Only the leaves are replaced, never the decisions: AppService's session values, the
 * BotGuard mint (PoTokenProviderImpl), the embed page (a cached identity) and the HTTP execution
 * (RetrofitHelper.get records the request and answers nothing). No network.
 *
 * <p>The OkHttp interceptors (Accept-Encoding, prettyPrint, Authorization) run after this point;
 * they do not depend on the client. After an intended request change, regenerate with
 * {@code NEWTUBE_GOLDEN_UPDATE=1} and review the diff of src/test/resources/golden/player-request.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class, shadows = {
        PlayerRequestGoldenTest.ShadowAppService.class,
        PlayerRequestGoldenTest.ShadowPoTokenProvider.class,
        PlayerRequestGoldenTest.ShadowRetrofitHelper.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlayerRequestGoldenTest {
    private static final File GOLDEN_DIR = new File("src/test/resources/golden/player-request");
    private static final String VIDEO_ID = "golden-vid1";

    private Locale mLocale;
    private TimeZone mTimeZone;
    private VideoInfoService mService;

    @Before
    public void setUp() {
        mLocale = Locale.getDefault();
        mTimeZone = TimeZone.getDefault();
        Locale.setDefault(Locale.US);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        ShadowAppService.service = ReflectionHelpers.callConstructor(AppService.class);
        ShadowRetrofitHelper.recorded.clear();
        DebugRequestOverrides.setSupportXhr(null);
        ReflectionHelpers.setStaticField(YtCfgService.class, "cachedEmbedIdentity",
                new YtCfgService.EmbedIdentity("golden-host-flags", "golden-embed-visitor",
                        System.currentTimeMillis()));
        mService = ReflectionHelpers.callConstructor(VideoInfoService.class);
    }

    @After
    public void tearDown() {
        Locale.setDefault(mLocale);
        TimeZone.setDefault(mTimeZone);
        ReflectionHelpers.setStaticField(YtCfgService.class, "cachedEmbedIdentity", null);
    }

    @Test
    public void everyClientSendsTheRequestItSentBefore() throws IOException {
        boolean update = "1".equals(System.getenv("NEWTUBE_GOLDEN_UPDATE"));
        List<String> mismatches = new ArrayList<>();
        int compared = 0;
        for (AppClient client : AppClient.values()) {
            if (client == AppClient.INITIAL) {
                continue; // a watch-page scrape, not a /player request
            }
            String actual = describe(client);
            File golden = new File(GOLDEN_DIR, client.name() + ".txt");
            if (update) {
                GOLDEN_DIR.mkdirs();
                Files.write(golden.toPath(), actual.getBytes(StandardCharsets.UTF_8));
                continue;
            }
            assertTrue("no golden for " + client + " - run with NEWTUBE_GOLDEN_UPDATE=1",
                    golden.isFile());
            String expected = new String(Files.readAllBytes(golden.toPath()), StandardCharsets.UTF_8);
            if (!expected.equals(actual)) {
                mismatches.add(client + ":\n--- golden\n" + expected + "\n+++ now\n" + actual);
            }
            compared++;
        }
        assertTrue(String.join("\n\n", mismatches), mismatches.isEmpty());
        if (!update) {
            assertEquals(AppClient.values().length - 1, compared);
        }
    }

    /** One client's request, signed out and signed in, as a stable text block. */
    private String describe(AppClient client) throws IOException {
        Captured signedOut = capture(client, false);
        Captured signedIn = capture(client, true);
        assertEquals(client + ": signing in changes only the auth flag", signedOut.request, signedIn.request);
        return signedOut.request + "\nauth signed-out=" + signedOut.auth + " signed-in=" + signedIn.auth + "\n";
    }

    private Captured capture(AppClient client, boolean signedIn) throws IOException {
        ReflectionHelpers.setField(mService, "mAuthBlock", signedIn);
        ShadowRetrofitHelper.recorded.clear();
        ReflectionHelpers.callInstanceMethod(mService, "getVideoInfo",
                ClassParameter.from(AppClient.class, client),
                ClassParameter.from(String.class, VIDEO_ID),
                ClassParameter.from(String.class, null));
        assertEquals(client + ": one request", 1, ShadowRetrofitHelper.recorded.size());
        Captured captured = ShadowRetrofitHelper.recorded.get(0);
        assertNotNull(captured.request);
        return captured;
    }

    static final class Captured {
        final String request;
        final boolean auth;

        Captured(String request, boolean auth) {
            this.request = request;
            this.auth = auth;
        }
    }

    private static String render(Request request) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append(request.method()).append(' ').append(request.url()).append('\n');
        for (int i = 0; i < request.headers().size(); i++) {
            out.append(request.headers().name(i)).append(": ").append(request.headers().value(i)).append('\n');
        }
        if (request.body() != null) {
            out.append("Content-Type(body): ").append(request.body().contentType()).append('\n');
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            out.append('\n').append(buffer.readUtf8()).append('\n');
        }
        return out.toString();
    }

    @Implements(RetrofitHelper.class)
    public static class ShadowRetrofitHelper {
        static final List<Captured> recorded = new ArrayList<>();

        @Implementation
        protected static <T> T get(Call<T> wrapper, boolean auth) {
            try {
                recorded.add(new Captured(render(wrapper.request()), auth));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            return null;
        }
    }

    @Implements(AppService.class)
    public static class ShadowAppService {
        static AppService service;

        @Implementation
        protected void __constructor__() {
            // No Retrofit, preferences or player-JS extraction.
        }

        @Implementation
        protected static AppService instance() {
            return service;
        }

        @Implementation
        protected String getSignatureTimestamp() {
            return "20697";
        }

        @Implementation
        protected String getVisitorData() {
            return "golden-app-visitor";
        }

        @Implementation
        protected String getClientPlaybackNonce() {
            return "golden-cpn-0001";
        }
    }

    /** The BotGuard mint: a token bound to the video, minted for the web session's visitor. */
    @Implements(PoTokenProviderImpl.class)
    public static class ShadowPoTokenProvider {
        @Implementation
        protected PoTokenResult getWebClientPoToken(String videoId) {
            return new PoTokenResult(videoId, "golden-web-visitor", "golden-pot-" + videoId,
                    "golden-streaming-pot");
        }

        @Implementation
        protected String peekSessionVisitorData() {
            return "golden-web-visitor";
        }

        @Implementation
        protected boolean isWebPotSupported() {
            return true;
        }

        @Implementation
        protected boolean isWebPotExpired() {
            return false;
        }
    }
}
