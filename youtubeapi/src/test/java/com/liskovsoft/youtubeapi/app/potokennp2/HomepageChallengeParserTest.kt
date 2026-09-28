package com.liskovsoft.youtubeapi.app.potokennp2

import com.grack.nanojson.JsonParser
import com.liskovsoft.youtubeapi.app.potokennp2.misc.extractHomepageChallenge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NEWTUBE(pot-wv4): the HTML half of PoTokenWebView4's homepage challenge, on synthetic pages whose
 * shape copies a real one (issue5/embed.html, 2026-09-28: `window.ytAtN({'R': '\x7b\x22...'})`, an
 * empty `window.ytAtN()` call before it, `\u003d`-escaped experiment flags). Every value is made
 * up. Pure: no WebView, no network.
 */
class HomepageChallengeParserTest {
    private val homepage = readFixture("homepage-synthetic.html")
    private val consent = readFixture("consent-synthetic.html")

    @Test
    fun homepageYieldsTheFirstYtcfgAndTheAtnChallenge() {
        val result = extractHomepageChallenge(homepage)

        assertNull(result.failure)
        assertEquals("y", result.contentFlag)
        assertTrue(result.hasEventId)

        // The FIRST ytcfg.set, as bgutil/upstream take it, not the later one.
        val ytcfg = JsonParser.`object`().from(result.ytcfg)
        assertEquals("SyntheticEventId0", ytcfg.getString("EVENT_ID"))
        assertEquals("WEB", ytcfg.getString("INNERTUBE_CLIENT_NAME"))

        // R comes back as JSON text that parseDescrambledChallengeData can read.
        val bgChallenge = JsonParser.`object`().from(result.rawChallenge).getObject("bgChallenge")
        assertEquals("c3ludGhldGlj+cHJvZ3Jh/bQ==", bgChallenge.getString("program"))
        assertEquals("syntheticglobal", bgChallenge.getString("globalName"))
        assertEquals("synthetic-interpreter-hash", bgChallenge.getString("interpreterHash"))
        assertEquals("[null,null,null,[],[]]", bgChallenge.getString("clientExperimentsStateBlob"))
        assertEquals("//www.google.com/js/th/synthetic-interpreter.js", bgChallenge
            .getObject("interpreterUrl")
            .getString("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue"))
    }

    @Test
    fun consentInterstitialHasNoChallenge() {
        val result = extractHomepageChallenge(consent)

        assertNull(result.rawChallenge)
        assertEquals("no-atn", result.failure)
        assertNull(result.ytcfg)
        assertFalse(result.hasEventId)
        assertEquals("?", result.contentFlag)
    }

    @Test
    fun aPageWithYtcfgButNoAtnKeepsTheConfigFacts() {
        val page = homepage.replace("window.ytAtN({", "window.somethingElse({")
        val result = extractHomepageChallenge(page)

        assertNull(result.rawChallenge)
        assertEquals("no-atn", result.failure)
        assertTrue(result.hasEventId)
        assertEquals("y", result.contentFlag)
    }

    @Test
    fun theEmptyAtnCallBeforeThePayloadIsSkipped() {
        // Real pages call window.ytAtN() with no argument first (on DOMContentLoaded).
        assertTrue(homepage.indexOf("window.ytAtN();") < homepage.indexOf("window.ytAtN({"))
        assertNotNull(extractHomepageChallenge(homepage).rawChallenge)
    }

    @Test
    fun aPayloadWithoutBgChallengeIsNotUsable() {
        val page = """<script>window.ytAtN({'R': '\x7b\x22responseContext\x22:\x7b\x7d\x7d',});</script>"""
        val result = extractHomepageChallenge(page)

        assertNull(result.rawChallenge)
        assertEquals("no-bgchallenge", result.failure)
    }

    @Test
    fun anUnparseableAtnLiteralIsNotUsable() {
        val page = """<script>window.ytAtN({'R': 'unterminated, "x": {})</script>"""
        val result = extractHomepageChallenge(page)

        assertNull(result.rawChallenge)
        assertEquals("atn-parse", result.failure)
    }

    @Test
    fun aYtcfgThatIsNotJsonFailsThePageAsBgutilDoes() {
        val page = homepage.replace("ytcfg.set({\"CLIENT_CANARY_STATE\"", "ytcfg.set({CLIENT_CANARY_STATE")
        val result = extractHomepageChallenge(page)

        assertNull(result.rawChallenge)
        assertEquals("ytcfg-parse", result.failure)
    }

    @Test
    fun noYtcfgStillUsesTheChallengeWithoutEventId() {
        val page = homepage.replace("ytcfg.set(", "ytcfg.put(")
        val result = extractHomepageChallenge(page)

        assertNotNull(result.rawChallenge)
        assertNull(result.failure)
        assertNull(result.ytcfg)
        assertFalse(result.hasEventId)
    }

    @Test
    fun anEmptyEventIdIsReportedAsMissing() {
        val page = homepage.replace("\"EVENT_ID\":\"SyntheticEventId0\"", "\"EVENT_ID\":\"\"")
        val result = extractHomepageChallenge(page)

        assertNotNull(result.ytcfg)
        assertFalse(result.hasEventId)
    }

    @Test
    fun contentFlagReadsBothEscapedAndPlainForms() {
        assertEquals("n", extractHomepageChallenge(
            homepage.replace("html5_generate_content_po_token\\u003dtrue", "html5_generate_content_po_token\\u003dfalse")
        ).contentFlag)
        assertEquals("y", extractHomepageChallenge("html5_generate_content_po_token=true").contentFlag)
        assertEquals("?", extractHomepageChallenge(homepage.replace("html5_generate_content_po_token", "html5_other")).contentFlag)
    }

    @Test
    fun garbageAndTruncatedPagesNeverThrow() {
        val inputs = listOf(
            "",
            "<html>",
            "\u0000\u0001binary\uFFFD",
            "window.ytAtN({'R': '\\x7b",
            "window.ytAtN({",
            "ytcfg.set({\"EVENT_ID\":",
            "ytcfg.set({});window.ytAtN({'R': 42})",
        )

        for (input in inputs) {
            assertNull("input: $input", extractHomepageChallenge(input).rawChallenge)
        }

        // Every prefix of a real-shaped page: a response cut off by the network.
        var cut = 0
        while (cut < homepage.length) {
            val result = extractHomepageChallenge(homepage.substring(0, cut))
            if (result.rawChallenge != null) {
                // only possible once the whole ytAtN(...) call is in
                assertTrue(cut > homepage.indexOf("'T': 'synthetic-eacr-token'"))
            }
            cut += 7
        }
    }

    @Test
    fun aRealSizedPayloadParsesOnTheJvm() {
        // The real R literal is ~40 KB (37,663-char program). A repeated-alternation regex recurses
        // per character on OpenJDK; the scanner in parseLooseJSON must not.
        val program = StringBuilder()
        repeat(4_000) { program.append("AbC\\/dEf\\x3d") }
        val page = homepage.replace("c3ludGhldGlj+cHJvZ3Jh\\/bQ\\x3d\\x3d", program.toString())
        assertTrue(page.length > 50_000)

        val result = extractHomepageChallenge(page)

        assertNull(result.failure)
        val bgChallenge = JsonParser.`object`().from(result.rawChallenge).getObject("bgChallenge")
        assertEquals(4_000 * "AbC/dEf=".length, bgChallenge.getString("program").length)
    }

    private fun readFixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("potoken/$name")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
}
