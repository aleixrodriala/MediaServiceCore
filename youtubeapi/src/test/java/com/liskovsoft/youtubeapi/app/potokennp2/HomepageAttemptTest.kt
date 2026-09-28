package com.liskovsoft.youtubeapi.app.potokennp2

import com.grack.nanojson.JsonParser
import com.liskovsoft.youtubeapi.app.potokennp2.misc.HttpCodeException
import com.liskovsoft.youtubeapi.app.potokennp2.misc.PageAnswer
import com.liskovsoft.youtubeapi.app.potokennp2.misc.attemptHomepageChallenge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NEWTUBE(pot-wv4): fix (c) of potoken-port.md. PoTokenWebView4's homepage step must never throw:
 * a network error, a non-200, a consent page or a failed interpreter download returns no challenge
 * so the generator moves on to /att/get (and says why on the NetPath line). Upstream let every one
 * of these escape a @JavascriptInterface method, which skipped /att/get. The page GET and the
 * interpreter download are fakes here: no network.
 */
class HomepageAttemptTest {
    private val homepage = readFixture("homepage-synthetic.html")
    private val consent = readFixture("consent-synthetic.html")

    @Test
    fun theHomepageChallengeIsUsedWithItsYtcfg() {
        var descrambled: String? = null

        val attempt = attemptHomepageChallenge(
            fetchPage = { PageAnswer(homepage, "www.youtube.com") },
            descramble = { descrambled = it; "PARSED" }
        )

        assertEquals("PARSED", attempt.challengeData)
        assertEquals("SyntheticEventId0", JsonParser.`object`().from(attempt.ytcfg).getString("EVENT_ID"))
        assertTrue(descrambled!!.contains("bgChallenge"))
        assertTrue(attempt.info.toLogFields(), attempt.info.toLogFields().startsWith(
            " challenge=homepage ytcfg=y eventId=y contentFlag=y pageHost=www.youtube.com pageMs="))
        assertNull(attempt.info.homepageFailure)
    }

    @Test
    fun aConsentPageFallsBackAndNamesItsHost() {
        var descrambleCalled = false

        val attempt = attemptHomepageChallenge(
            fetchPage = { PageAnswer(consent, "consent.youtube.com") },
            descramble = { descrambleCalled = true; "PARSED" }
        )

        assertNull(attempt.challengeData)
        assertNull(attempt.ytcfg)
        assertFalse(descrambleCalled)
        val fields = attempt.info.toLogFields()
        assertTrue(fields, fields.startsWith(" challenge=att-get ytcfg=n eventId=n contentFlag=? pageHost=consent.youtube.com pageMs="))
        assertTrue(fields, fields.endsWith(" homepageFail=no-atn"))
    }

    @Test
    fun aNetworkErrorFallsBack() {
        // OkHttpManager rethrows every IOException as IllegalStateException.
        val attempt = attemptHomepageChallenge(
            fetchPage = { throw IllegalStateException("Interrupted OkHttp request to https://www.youtube.com/") },
            descramble = { "PARSED" }
        )

        assertNull(attempt.challengeData)
        assertEquals(" challenge=att-get ytcfg=n eventId=n contentFlag=? homepageFail=page-IllegalStateException",
            attempt.info.toLogFields())
    }

    @Test
    fun aNon200FallsBack() {
        val attempt = attemptHomepageChallenge(
            fetchPage = { throw HttpCodeException(429) },
            descramble = { "PARSED" }
        )

        assertNull(attempt.challengeData)
        assertEquals("http-429", attempt.info.homepageFailure)
    }

    @Test
    fun aFailedInterpreterDownloadFallsBack() {
        val attempt = attemptHomepageChallenge(
            fetchPage = { PageAnswer(homepage, "www.youtube.com") },
            descramble = { throw IllegalStateException("Interrupted OkHttp request to https://www.google.com/js/th/x.js") }
        )

        assertNull(attempt.challengeData)
        assertNull(attempt.ytcfg)
        assertEquals("interpreter-IllegalStateException", attempt.info.homepageFailure)
        assertEquals("att-get", attempt.info.challenge)
        // what the page said is still reported
        assertEquals("www.youtube.com", attempt.info.pageHost)
        assertEquals("y", attempt.info.contentFlag)
    }

    @Test
    fun aChallengeThatOnlyNamesTheKeysFailsAtTheInterpreterStep() {
        // extractHomepageChallenge checks for the three words, as upstream does; the real
        // parseDescrambledChallengeData then fails to parse it (before any download).
        val page = """<script>window.ytAtN({'R': 'bgChallenge program interpreterUrl'})</script>"""

        val attempt = attemptHomepageChallenge(
            fetchPage = { PageAnswer(page, "www.youtube.com") },
            descramble = { JsonParser.`object`().from(it); "PARSED" }
        )

        assertNull(attempt.challengeData)
        assertEquals("interpreter-JsonParserException", attempt.info.homepageFailure)
    }

    private fun readFixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("potoken/$name")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
}
