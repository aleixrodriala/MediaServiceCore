package com.liskovsoft.youtubeapi.app.potokennp2

import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import com.liskovsoft.youtubeapi.app.potokennp2.misc.parseLooseJSON
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * NEWTUBE(pot-wv4): parseLooseJSON against BgUtils-style vectors (src/utils/helpers.ts, v4.0.3):
 * the `ytAtN` argument is a JS object literal, not JSON. Two cases are where upstream's port
 * differed from BgUtils and this one follows BgUtils: an escaped quote inside a string, and an
 * object-literal value.
 */
class ParseLooseJsonTest {
    @Test
    fun hexEscapesSingleQuotesAndTrailingCommas() {
        val result = parseLooseJSON("""{'R': '\x7b\x22a\x22:\x5b1,2\x5d\x7d', 'T': 'plain',}""")

        assertEquals("""{"a":[1,2]}""", result["R"])
        assertEquals("plain", result["T"])
    }

    @Test
    fun anEscapedQuoteInsideASingleQuotedStringSurvives() {
        // Upstream decoded \x27 into a live ' BEFORE the single-quote pass and broke the literal.
        val result = parseLooseJSON("""{'R': '\x7b\x22q\x22:\x22it\x27s\x22\x7d'}""")

        assertEquals("it's", JsonParser.`object`().from(result["R"]).getString("q"))
        assertEquals("it's", parseLooseJSON("""{'R': 'it\'s'}""")["R"])
    }

    @Test
    fun anObjectLiteralValueComesBackAsJson() {
        // Upstream returned value.toString(): "{bgChallenge={program=p}}", which is not JSON.
        val result = parseLooseJSON("""{R: {bgChallenge: {program: 'p', interpreterUrl: {u: '//x'}}}, A: [1, 'two',]}""")

        val bgChallenge = JsonParser.`object`().from(result["R"]).getObject("bgChallenge")
        assertEquals("p", bgChallenge.getString("program"))
        assertEquals("//x", bgChallenge.getObject("interpreterUrl").getString("u"))
        assertEquals("""[1,"two"]""", result["A"])
    }

    @Test
    fun nestedStringsOfAnObjectLiteralAreNormalizedAsBgUtilsDoes() {
        // BgUtils' normalizeValue walks objects: hex escapes are decoded in nested strings, and a
        // nested string holding JSON becomes that JSON.
        val result = parseLooseJSON(
            """{R: {bgChallenge: {program: 'a\x3db', interpreterUrl: '\x7b\x22u\x22:\x22\/\/x\x22\x7d'}}}""")

        val bgChallenge = JsonParser.`object`().from(result["R"]).getObject("bgChallenge")
        assertEquals("a=b", bgChallenge.getString("program"))
        assertEquals("//x", bgChallenge.getObject("interpreterUrl").getString("u"))
    }

    @Test
    fun unquotedKeysAndScalars() {
        val result = parseLooseJSON("""{R: 'a', count: 3, flag: true, none: null, "quoted": "q"}""")

        assertEquals("a", result["R"])
        assertEquals("3", result["count"])
        assertEquals("true", result["flag"])
        assertEquals("null", result["none"])
        assertEquals("q", result["quoted"])
    }

    @Test
    fun escapedSlashesAreLeftForTheJsonParseOfR() {
        // The page writes URLs as \/\/host\/path inside R; they are JSON escapes once R is decoded.
        val result = parseLooseJSON("""{'R': '\x7b\x22u\x22:\x22\/\/www.google.com\/js\/th\/x.js\x22\x7d'}""")

        assertEquals("//www.google.com/js/th/x.js", JsonParser.`object`().from(result["R"]).getString("u"))
    }

    @Test(expected = JsonParserException::class)
    fun anUnterminatedLiteralIsAParseError() {
        parseLooseJSON("""{'R': 'never closed}""")
    }
}
