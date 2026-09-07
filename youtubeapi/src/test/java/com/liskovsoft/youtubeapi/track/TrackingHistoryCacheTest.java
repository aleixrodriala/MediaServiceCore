package com.liskovsoft.youtubeapi.track;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import android.app.Application;

import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper;
import com.liskovsoft.youtubeapi.feedback.FeedbackApiHelper;
import com.liskovsoft.youtubeapi.feedback.FeedbackService;

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

import java.lang.reflect.Proxy;

import kotlin.Triple;
import retrofit2.Call;

/** Real feedback/cache bookkeeping, with all remote IO replaced by an offline boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class,
        shadows = {TrackingHistoryCacheTest.OfflineRetrofit.class,
                TrackingHistoryCacheTest.OfflineFeedbackQuery.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TrackingHistoryCacheTest {
    private TrackingService tracking;

    @Before
    public void setUp() {
        OfflineRetrofit.fail = false;
        ReflectionHelpers.setStaticField(TrackingService.class, "sInstance", null);
        ReflectionHelpers.setStaticField(FeedbackService.class, "sInstance", null);
        tracking = TrackingService.instance();
        ReflectionHelpers.setField(tracking, "mPosition",
                new Triple<>("offline-video", 30f, System.currentTimeMillis()));
    }

    @Test
    public void cachedPlaybackNormallyReusesItsHistoryRecord() {
        assertFalse(needsRecord());
    }

    @Test
    public void feedbackRemovalAllowsSameVideoToCreateHistoryAgain() {
        FeedbackService.instance().markAsNotInterested("offline-feedback");
        assertTrue(needsRecord());
    }

    @Test
    public void failedFeedbackDoesNotInvalidateSuccessfulHistory() {
        OfflineRetrofit.fail = true;
        assertThrows(IllegalStateException.class,
                () -> FeedbackService.instance().markAsNotInterested("offline-feedback"));
        assertFalse(needsRecord());
    }

    @Test
    public void repeatedClearIsHarmless() {
        tracking.clearCache();
        tracking.clearCache();
        assertTrue(needsRecord());
    }

    private boolean needsRecord() {
        return ReflectionHelpers.callInstanceMethod(tracking, "needNewRecord",
                ClassParameter.from(String.class, "offline-video"));
    }

    @Implements(RetrofitHelper.class)
    public static class OfflineRetrofit {
        static boolean fail;

        @Implementation
        protected static <T> T create(Class<T> type) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                    (proxy, method, args) -> null));
        }

        @Implementation
        protected static <T> T get(Call<T> call) {
            if (fail) throw new IllegalStateException("Synthetic feedback failure");
            return null;
        }
    }

    @Implements(FeedbackApiHelper.class)
    public static class OfflineFeedbackQuery {
        @Implementation
        protected static String getNotInterestedQuery(String token) {
            return "{}";
        }
    }
}
