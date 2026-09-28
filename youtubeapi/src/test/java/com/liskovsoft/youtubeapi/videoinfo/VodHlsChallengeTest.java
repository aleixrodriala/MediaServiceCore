package com.liskovsoft.youtubeapi.videoinfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** NEWTUBE(hls-vod): the throttling challenge of a VOD HLS manifest URL is a path pair. */
public class VodHlsChallengeTest {
    private static final String MANIFEST = "https://manifest.googlevideo.com/api/manifest/hls_variant"
            + "/expire/1759200000/ei/abc/ip/1.2.3.4/id/760f8f1e2b/source/youtube"
            + "/n/Rz3kQ9_xYw-Ab1/sn/sn-h5q7kne7/playlist_type/DVR/file/index.m3u8";

    @Test
    public void theChallengeIsReadFromThePath() {
        assertEquals("Rz3kQ9_xYw-Ab1", VideoInfoServiceBase.manifestChallenge(MANIFEST));
    }

    @Test
    public void onlyTheChallengeIsReplaced() {
        assertEquals(MANIFEST.replace("/n/Rz3kQ9_xYw-Ab1/", "/n/solved123/"),
                VideoInfoServiceBase.withManifestChallenge(MANIFEST, "solved123"));
    }

    /** "/sn/" (the server name) is not "/n/", and a URL without the pair is left alone. */
    @Test
    public void aManifestWithoutAChallengeIsUntouched() {
        String withoutN = MANIFEST.replace("/n/Rz3kQ9_xYw-Ab1", "");
        assertNull(VideoInfoServiceBase.manifestChallenge(withoutN));
        assertEquals(withoutN, VideoInfoServiceBase.withManifestChallenge(withoutN, "solved123"));
    }
}
