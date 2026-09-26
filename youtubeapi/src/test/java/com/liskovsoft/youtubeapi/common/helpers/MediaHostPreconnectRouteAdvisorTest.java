package com.liskovsoft.youtubeapi.common.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** NEWTUBE(media-path): the app decides, per host, whether the warm goes through Cronet. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public class MediaHostPreconnectRouteAdvisorTest {
    private static final String URL = "https://rr1---sn-uxax4vopj5xn-cjol.googlevideo.com"
            + "/videoplayback?expire=1&itag=251&sig=secret";

    private final List<String> mAsked = new ArrayList<>();

    @After
    public void tearDown() {
        MediaHostPreconnect.setRouteAdvisor(null);
        MediaHostPreconnect.setEnabled(false);
    }

    @Test
    public void theAdvisorSeesOnlyTheHostAndCanTakeTheWarmOver() {
        MediaHostPreconnect.setEnabled(true);
        MediaHostPreconnect.setRouteAdvisor(host -> {
            mAsked.add(host);
            return false; // the app warms its own transport: no Cronet request
        });

        MediaHostPreconnect.warmUrl(URL);
        MediaHostPreconnect.warmUrlEarly(URL);

        // The signed query never leaves this class.
        assertEquals(Collections.nCopies(2, "rr1---sn-uxax4vopj5xn-cjol.googlevideo.com"), mAsked);
    }

    @Test
    public void aCronetAnswerFromTheAdvisorKeepsThePlainWarmSafe() {
        MediaHostPreconnect.setEnabled(true);
        MediaHostPreconnect.setRouteAdvisor(host -> {
            mAsked.add(host);
            return true;
        });

        // No app context here: the Cronet warm then simply does nothing, and nothing throws.
        MediaHostPreconnect.warmUrl(URL);

        assertEquals(1, mAsked.size());
    }

    @Test
    public void aDisabledPreconnectOrAnUnparsableUrlNeverAsks() {
        MediaHostPreconnect.setRouteAdvisor(host -> {
            mAsked.add(host);
            return false;
        });
        MediaHostPreconnect.warmUrl(URL); // not enabled
        MediaHostPreconnect.setEnabled(true);
        MediaHostPreconnect.warmUrl("not a url");
        MediaHostPreconnect.warmUrl(null);

        assertTrue(mAsked.isEmpty());
    }

    @Test
    public void aThrowingAdvisorIsSwallowedLikeEveryOtherWarmFailure() {
        MediaHostPreconnect.setEnabled(true);
        MediaHostPreconnect.setRouteAdvisor(host -> {
            throw new IllegalStateException("advisor bug");
        });

        MediaHostPreconnect.warmUrl(URL); // best effort: must not reach the /player caller
    }
}
