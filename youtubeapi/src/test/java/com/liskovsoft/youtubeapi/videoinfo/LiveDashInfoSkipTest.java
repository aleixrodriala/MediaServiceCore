package com.liskovsoft.youtubeapi.videoinfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Offline: when a live open may skip the blocking googlevideo dash-info probe. Only the phone gate
 * plus a manifest url the loader will actually open (VideoLoaderController's live branch) may skip
 * it; a manifest-less live stream still needs the probe for the generated MPD.
 */
public class LiveDashInfoSkipTest {
    private static final String DASH = "https://manifest.googlevideo.com/api/manifest/dash/x";
    private static final String HLS = "https://manifest.googlevideo.com/api/manifest/hls_variant/x";

    @Test
    public void tvBehaviourIsUnchangedWhateverTheManifests() {
        assertNull(VideoInfoServiceBase.liveDashInfoSkipReason(false, DASH, HLS));
        assertNull(VideoInfoServiceBase.liveDashInfoSkipReason(false, DASH, null));
        assertNull(VideoInfoServiceBase.liveDashInfoSkipReason(false, null, HLS));
        assertNull(VideoInfoServiceBase.liveDashInfoSkipReason(false, null, null));
    }

    @Test
    public void aManifestUrlMakesTheProbeDeadWeight() {
        assertEquals("dash-manifest", VideoInfoServiceBase.liveDashInfoSkipReason(true, DASH, HLS));
        assertEquals("dash-manifest", VideoInfoServiceBase.liveDashInfoSkipReason(true, DASH, null));
        assertEquals("hls-manifest", VideoInfoServiceBase.liveDashInfoSkipReason(true, null, HLS));
    }

    /** The generated-MPD last resort reads the probe's segment numbers, so it must still run. */
    @Test
    public void manifestLessLiveStillProbes() {
        assertNull(VideoInfoServiceBase.liveDashInfoSkipReason(true, null, null));
    }
}
