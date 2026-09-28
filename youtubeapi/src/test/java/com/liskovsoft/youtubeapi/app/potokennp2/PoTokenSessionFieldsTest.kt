package com.liskovsoft.youtubeapi.app.potokennp2

import com.liskovsoft.youtubeapi.app.potokennp2.generators.PoTokenWebView
import com.liskovsoft.youtubeapi.app.potokennp2.generators.PoTokenWebView4
import com.liskovsoft.youtubeapi.app.potokennp2.misc.PoTokenChallengeInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * NEWTUBE(pot-wv4): the fields appended to the `web-pot-session` NetPath line. netbench and the
 * HANDOFF greps read that line, so the order and spelling are fixed here. The formatter only takes
 * kinds, flags, a host, durations and a TTL: there is no parameter a token or a visitor could reach.
 */
class PoTokenSessionFieldsTest {
    @Test
    fun homepageChallenge() {
        assertEquals(
            " challenge=homepage ytcfg=y eventId=y contentFlag=y pageHost=www.youtube.com pageMs=412 ttl=43200",
            PoTokenChallengeInfo("homepage", ytcfg = true, eventId = true, contentFlag = "y",
                pageHost = "www.youtube.com", pageMs = 412).withTtl(43200).toLogFields()
        )
    }

    @Test
    fun attGetAfterAConsentPage() {
        assertEquals(
            " challenge=att-get ytcfg=n eventId=n contentFlag=? pageHost=consent.youtube.com pageMs=230 homepageFail=no-atn ttl=43200",
            PoTokenChallengeInfo("att-get", pageHost = "consent.youtube.com", pageMs = 230,
                homepageFailure = "no-atn", ttlSecs = 43200).toLogFields()
        )
    }

    @Test
    fun legacyCreateChallenge() {
        assertEquals(" challenge=legacy ytcfg=n eventId=n contentFlag=? ttl=43200",
            PoTokenChallengeInfo(PoTokenChallengeInfo.LEGACY, ttlSecs = 43200).toLogFields())
        // before GenerateIT answered
        assertEquals(" challenge=legacy ytcfg=n eventId=n contentFlag=?",
            PoTokenChallengeInfo(PoTokenChallengeInfo.LEGACY).toLogFields())
    }

    @Test
    fun fallbackNamesTheGeneratorNotItsFactory() {
        // fallbackFrom= is built from the factory, which is the generator's companion object.
        assertEquals("PoTokenWebView4", generatorName(PoTokenWebView4))
        assertEquals("PoTokenWebView", generatorName(PoTokenWebView))
    }
}
