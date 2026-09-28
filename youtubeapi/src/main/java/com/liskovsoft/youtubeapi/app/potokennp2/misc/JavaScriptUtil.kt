package com.liskovsoft.youtubeapi.app.potokennp2.misc

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import com.liskovsoft.sharedutils.okhttp.OkHttpManager
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenException
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.util.regex.Pattern

/**
 * Parses the raw challenge data obtained from the Create endpoint and returns an object that can be
 * embedded in a JavaScript snippet.
 */
internal fun parseChallengeData(rawChallengeData: String): String {
    val scrambled = JsonParser.array().from(rawChallengeData)

    val challengeData = if (scrambled.size > 1 && scrambled.isString(1)) {
        val descrambled = descramble(scrambled.getString(1))
        JsonParser.array().from(descrambled)
    } else {
        // Fixes a regression, where if the challenge data array size was one, the second element
        // would be accessed, leading to a crash.
        // This was introduced when porting the challenge parsing from JS to
        // Kotlin.
        //scrambled.getArray(1)
        scrambled.getArray(0)
    }

    val messageId = challengeData.getString(0)
    val interpreterHash = challengeData.getString(3)
    val program = challengeData.getString(4)
    val globalName = challengeData.getString(5)
    val clientExperimentsStateBlob = challengeData.getString(7)

    val privateDoNotAccessOrElseSafeScriptWrappedValue = challengeData.getArray(1, null)?.find { it is String }
    val privateDoNotAccessOrElseTrustedResourceUrlWrappedValue = challengeData.getArray(2, null)?.find { it is String }

    return JsonWriter.string(
        JsonObject.builder()
            .value("messageId", messageId)
            .`object`("interpreterJavascript")
            .value("privateDoNotAccessOrElseSafeScriptWrappedValue", privateDoNotAccessOrElseSafeScriptWrappedValue)
            .value("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", privateDoNotAccessOrElseTrustedResourceUrlWrappedValue)
            .end()
            .value("interpreterHash", interpreterHash)
            .value("program", program)
            .value("globalName", globalName)
            .value("clientExperimentsStateBlob", clientExperimentsStateBlob)
            .done()
    )
}

/**
 * Parses the raw challenge data obtained from the Create endpoint and returns an object that can be
 * embedded in a JavaScript snippet.
 */
internal fun parseDescrambledChallengeData(rawChallengeData: String): String {
    val root = JsonParser.`object`().from(rawChallengeData)
    val bgChallenge = root.getObject("bgChallenge")

    val interpreterHash = bgChallenge.getString("interpreterHash")
    val program = bgChallenge.getString("program")
    val globalName = bgChallenge.getString("globalName")
    val clientExperimentsStateBlob = bgChallenge.getString("clientExperimentsStateBlob")

    val privateDoNotAccessOrElseTrustedResourceUrlWrappedValue = bgChallenge
        .getObject("interpreterUrl")
        .getString("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue")
    val privateDoNotAccessOrElseSafeScriptWrappedValue =
        OkHttpManager.instance().doGetRequest("https:$privateDoNotAccessOrElseTrustedResourceUrlWrappedValue").body?.string()
            ?: throw PoTokenException("Empty response body")

    return JsonWriter.string(
        JsonObject.builder()
            .`object`("interpreterJavascript")
            .value("privateDoNotAccessOrElseSafeScriptWrappedValue", privateDoNotAccessOrElseSafeScriptWrappedValue)
            .value("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", privateDoNotAccessOrElseTrustedResourceUrlWrappedValue)
            .end()
            .value("interpreterHash", interpreterHash)
            .value("program", program)
            .value("globalName", globalName)
            .value("clientExperimentsStateBlob", clientExperimentsStateBlob)
            .done()
    )
}

private val HEX_ESCAPE_PATTERN = Pattern.compile("""\\x([0-9A-Fa-f]{2})""")
private val TRAILING_COMMA_PATTERN = Pattern.compile(""",\s*([\]}])""")
private val UNQUOTED_KEY_PATTERN = Pattern.compile("""([{,]\s*)([a-zA-Z0-9_$]+)\s*:""")

/**
 * ```text
 * --- PATCH(unstem 2026-08): homepage challenge + ytcfg (BgUtils#44) ------
 * parseLooseJSON vendored from LuanRT/BgUtils v4.0.3 (MIT). The ytAtN
 * payload is JS-object-literal-ish, not strict JSON.
 * ```
 *
 * NEWTUBE(pot-wv4): ported from upstream a722df75/6a21ed72 with two changes, both towards BgUtils'
 * own order of operations (src/utils/helpers.ts):
 * - `\xNN` escapes are decoded AFTER parsing, inside string values. Upstream decodes them in the
 *   raw text first, so an escaped quote (`\x27` inside a single-quoted string, `\x22` inside a
 *   double-quoted one) became a live delimiter and broke the parse.
 * - Object and array values come back as JSON text ([JsonWriter]), their nested strings
 *   normalized as BgUtils' normalizeValue does. Upstream returned `value.toString()`, which for
 *   nanojson's JsonObject (a LinkedHashMap) is `{k=v}`, not JSON. The page's `R` is a quoted
 *   string today (issue5/embed.html, 2026-09-28), so this is a guard.
 * A string value that holds hex-escaped JSON (the real `R`) comes back decoded, as JSON text.
 */
internal fun parseLooseJSON(looseJson: String): Map<String, String> {
    var jsonStr = TRAILING_COMMA_PATTERN.matcher(looseJson).replaceAll("$1")

    jsonStr = quoteSingleQuotedStrings(jsonStr)

    // just in case (BgUtils)
    jsonStr = UNQUOTED_KEY_PATTERN.matcher(jsonStr).replaceAll("""$1"$2":""")

    val parsedData = JsonParser.`object`().from(jsonStr)
    val result = LinkedHashMap<String, String>()

    for ((key, value) in parsedData) {
        result[key] = when (value) {
            null -> "null"
            // The real R: returned as the decoded text, which is JSON for the next parser.
            is String -> decodeHexEscapes(value)
            is JsonObject, is JsonArray -> JsonWriter.string(normalizeLooseValue(value))
            else -> value.toString()
        }
    }

    return result
}

/**
 * BgUtils' normalizeValue for nested values: strings are hex-decoded, and a decoded string that
 * holds JSON becomes that JSON (normalized in turn), so an object-literal `R` with escaped strings
 * inside comes out the way BgUtils would see it.
 */
private fun normalizeLooseString(value: String): Any? {
    val decoded = decodeHexEscapes(value)
    val trimmed = decoded.trim()

    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        try {
            return normalizeLooseValue(JsonParser.any().from(decoded))
        } catch (e: Exception) {
            // not JSON after all: keep the text, as BgUtils does
        }
    }

    return decoded
}

private fun normalizeLooseValue(value: Any?): Any? = when (value) {
    is String -> normalizeLooseString(value)
    is JsonArray -> JsonArray(value.map { normalizeLooseValue(it) })
    is JsonObject -> JsonObject().also { normalized ->
        for ((key, nested) in value) {
            normalized[key] = normalizeLooseValue(nested)
        }
    }
    else -> value
}

/**
 * NEWTUBE(pot-wv4): BgUtils' `/'((?:[^'\\]|\\[\s\S])*)'/g` replace (each single-quoted literal
 * becomes a JSON string, `\'` unescaped), as a scanner rather than that regex. OpenJDK's engine
 * recurses once per character through a repeated alternation, and the real `R` literal is ~40 KB
 * (a StackOverflowError in JVM unit tests); Android's ICU engine keeps its backtrack stack on the
 * heap, which is why upstream's regex works on devices. Same matches: a quote with no closing
 * quote is left as it is and the scan goes on from the next character.
 */
private fun quoteSingleQuotedStrings(source: String): String {
    val out = StringBuilder(source.length + 16)
    var i = 0

    while (i < source.length) {
        val char = source[i]

        if (char == '\'') {
            val end = findClosingSingleQuote(source, i + 1)

            if (end >= 0) {
                out.append(quoteJson(source.substring(i + 1, end).replace("""\'""", "'")))
                i = end + 1
                continue
            }
        }

        out.append(char)
        i++
    }

    return out.toString()
}

/**
 * Index of the `'` closing a literal whose body starts at [from], stepping over `\` + any char;
 * -1 when there is none.
 */
private fun findClosingSingleQuote(source: String, from: Int): Int {
    var j = from

    while (j < source.length) {
        when (source[j]) {
            '\'' -> return j
            '\\' -> j += 2
            else -> j++
        }
    }

    return -1
}

/**
 * `\x41` -> `A`, as BgUtils' decodeHexEscapes.
 */
private fun decodeHexEscapes(value: String): String {
    if (!value.contains("\\x")) {
        return value
    }

    val hexMatcher = HEX_ESCAPE_PATTERN.matcher(value)

    return buildString {
        var lastEnd = 0

        while (hexMatcher.find()) {
            append(value, lastEnd, hexMatcher.start())
            append(hexMatcher.group(1)!!.toInt(16).toChar())
            lastEnd = hexMatcher.end()
        }

        append(value, lastEnd, value.length)
    }
}

private fun quoteJson(value: String): String =
    buildString {
        append('"')
        for (char in value) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        // Securely escape raw control chars to match RFC spec
                        //append("\\u%04x".format(char.code))
                        append("\\u00")
                        // Fast hex conversion for values 0-31 without allocations
                        append("0123456789abcdef"[char.code ushr 4])
                        append("0123456789abcdef"[char.code and 0x0F])
                    } else {
                        append(char)
                    }
                }
            }
        }
        append('"')
    }

/**
 * Parses the raw integrity token data obtained from the GenerateIT endpoint to a JavaScript
 * `Uint8Array` that can be embedded directly in JavaScript code, and an [Int] representing the
 * duration of this token in seconds.
 */
internal fun parseIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val integrityTokenData = JsonParser.array().from(rawIntegrityTokenData)
    return base64ToU8(integrityTokenData.getString(0)) to integrityTokenData.getLong(1)
}

/**
 * Converts a string (usually the identifier used as input to `obtainPoToken`) to a JavaScript
 * `Uint8Array` that can be embedded directly in JavaScript code.
 */
internal fun stringToU8(identifier: String): String {
    return newUint8Array(identifier.toByteArray())
}

/**
 * Takes a poToken encoded as a sequence of bytes represented as integers separated by commas
 * (e.g. "97,98,99" would be "abc"), which is the output of `Uint8Array::toString()` in JavaScript,
 * and converts it to the specific base64 representation for poTokens.
 */
internal fun u8ToBase64(poToken: String): String {
    return poToken.split(",")
        .map { it.toUByte().toByte() }
        .toByteArray()
        .toByteString()
        .base64()
        .replace("+", "-")
        .replace("/", "_")
}

/**
 * Takes the scrambled challenge, decodes it from base64, adds 97 to each byte.
 */
private fun descramble(scrambledChallenge: String): String {
    return base64ToByteString(scrambledChallenge)
        .map { (it + 97).toByte() }
        .toByteArray()
        .decodeToString()
}

/**
 * Decodes a base64 string encoded in the specific base64 representation used by YouTube, and
 * returns a JavaScript `Uint8Array` that can be embedded directly in JavaScript code.
 */
private fun base64ToU8(base64: String): String {
    return newUint8Array(base64ToByteString(base64))
}

private fun newUint8Array(contents: ByteArray): String {
    return "new Uint8Array([" + contents.joinToString(separator = ",") { it.toUByte().toString() } + "])"
}

/**
 * Decodes a base64 string encoded in the specific base64 representation used by YouTube.
 */
private fun base64ToByteString(base64: String): ByteArray {
    val base64Mod = base64
        .replace('-', '+')
        .replace('_', '/')
        .replace('.', '=')

    return (base64Mod.decodeBase64() ?: throw PoTokenException("Cannot base64 decode"))
        .toByteArray()
}
