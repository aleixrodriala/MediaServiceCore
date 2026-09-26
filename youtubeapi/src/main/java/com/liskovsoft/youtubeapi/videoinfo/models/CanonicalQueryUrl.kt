package com.liskovsoft.youtubeapi.videoinfo.models

import java.net.URLDecoder

/**
 * NEWTUBE(open-cpu): recognises media URLs whose sharedutils query-string round trip is the
 * identity, so [VideoUrlHolder] can answer a lookup without building that parser.
 *
 * Every format holder used to be parsed on every open just to learn that it carries no `n`
 * challenge (VISIONOS, the phone's usual winner, never does), and again for `xtags`; the parse
 * (`UrlQueryStringFactory` -> java.net.URI, three regex passes, String.format, a URLDecoder call
 * per parameter) and its re-print on every later `getUrl()` (a URLEncoder call per parameter plus
 * String.format) were the largest single CPU cost between the /player response and the player.
 *
 * [isCanonical] only says yes when BOTH hold, by construction:
 *  - `UrlQueryStringFactory.parse(url).toString() == url`, so skipping the parse cannot change
 *    what `getUrl()` returns;
 *  - `parse(url).get(key)` is `URLDecoder.decode(value, "UTF-8")` of the key's only occurrence,
 *    or null when absent - which is what [get] computes.
 *
 * The grammar, and why each rule is needed:
 *  - `http(s)://` + a plain RFC 2396 hostname (lowercase labels of `[a-z0-9-]`, no leading or
 *    trailing `-`, top label starting with a letter; no port, no user info): java.net.URI parses
 *    it as a server authority and `scheme://host+path` is rebuilt exactly.
 *  - a single path segment of `[A-Za-z0-9._-]`: `URI.getPath()` (decoded) equals the raw path,
 *    and PathQueryString's `/k/v/...` pattern cannot match, so it never joins the lookup.
 *  - query `k=v(&k=v)*`, unique non-empty keys of `[A-Za-z0-9._-]`, non-empty values of
 *    `[A-Za-z0-9._*+-]` or `%HH` (uppercase hex, ASCII, a character URLEncoder always escapes):
 *    decode-then-encode reproduces every value byte for byte (`+` -> space -> `+`), the
 *    LinkedHashMap keeps the order, and no `;`, empty or duplicate parameter gets regrouped.
 * Anything else answers false and takes the original parse.
 */
internal object CanonicalQueryUrl {
    fun isCanonical(url: String): Boolean {
        val authorityStart = when {
            url.startsWith("https://") -> 8
            url.startsWith("http://") -> 7
            else -> return false
        }
        val pathStart = url.indexOf('/', authorityStart)
        val queryStart = url.indexOf('?', authorityStart)
        if (pathStart < 0 || queryStart < 0 || queryStart < pathStart + 2) {
            return false
        }
        if (!isHostname(url, authorityStart, pathStart)) {
            return false
        }
        for (i in pathStart + 1 until queryStart) {
            if (!isSegmentChar(url[i])) {
                return false
            }
        }
        return isQuery(url, queryStart + 1)
    }

    /** The decoded value of [key] in a URL [isCanonical] accepted, or null when absent. */
    fun get(url: String, key: String): String? {
        var start = url.indexOf('?') + 1
        val length = url.length
        while (start < length) {
            var end = url.indexOf('&', start)
            if (end < 0) {
                end = length
            }
            val eq = url.indexOf('=', start)
            if (eq - start == key.length && url.regionMatches(start, key, 0, key.length)) {
                return URLDecoder.decode(url.substring(eq + 1, end), "UTF-8")
            }
            start = end + 1
        }
        return null
    }

    private fun isHostname(url: String, start: Int, end: Int): Boolean {
        var labels = 0
        var labelStart = start
        while (labelStart < end) {
            var labelEnd = url.indexOf('.', labelStart)
            if (labelEnd < 0 || labelEnd > end) {
                labelEnd = end
            }
            if (labelEnd == labelStart) {
                return false // empty label or trailing dot
            }
            if (!isLowerAlnum(url[labelStart]) || !isLowerAlnum(url[labelEnd - 1])) {
                return false
            }
            for (i in labelStart + 1 until labelEnd - 1) {
                if (!isLowerAlnum(url[i]) && url[i] != '-') {
                    return false
                }
            }
            labels++
            if (labelEnd == end) {
                // The rightmost label of a qualified hostname must start with a letter.
                return labels >= 2 && url[labelStart] in 'a'..'z'
            }
            labelStart = labelEnd + 1
        }
        return false
    }

    private fun isQuery(url: String, start: Int): Boolean {
        val length = url.length
        if (start >= length) {
            return false
        }
        val keys = HashSet<String>()
        var paramStart = start
        while (true) {
            var paramEnd = url.indexOf('&', paramStart)
            if (paramEnd < 0) {
                paramEnd = length
            }
            var eq = -1
            for (i in paramStart until paramEnd) {
                if (url[i] == '=') {
                    eq = i
                    break
                }
            }
            if (eq <= paramStart || eq >= paramEnd - 1) {
                return false // no '=', empty key or empty value
            }
            for (i in paramStart until eq) {
                if (!isKeyChar(url[i])) {
                    return false
                }
            }
            var i = eq + 1
            while (i < paramEnd) {
                val c = url[i]
                if (c == '%') {
                    if (i + 2 >= paramEnd || !isEscapedAscii(url[i + 1], url[i + 2])) {
                        return false
                    }
                    i += 3
                } else if (isValueChar(c)) {
                    i++
                } else {
                    return false
                }
            }
            if (!keys.add(url.substring(paramStart, eq))) {
                return false // a repeated key would be regrouped
            }
            if (paramEnd == length) {
                return true
            }
            paramStart = paramEnd + 1
            if (paramStart == length) {
                return false // trailing '&'
            }
        }
    }

    private fun isLowerAlnum(c: Char): Boolean = c in 'a'..'z' || c in '0'..'9'

    private fun isSegmentChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_' || c == '-'

    private fun isKeyChar(c: Char): Boolean = isSegmentChar(c)

    /** Left alone by both URLDecoder and URLEncoder ('+' maps to space and back). */
    private fun isValueChar(c: Char): Boolean = isSegmentChar(c) || c == '*' || c == '+'

    /** `%HH` in URLEncoder's own spelling of a character it always escapes. */
    private fun isEscapedAscii(high: Char, low: Char): Boolean {
        val h = upperHex(high)
        val l = upperHex(low)
        if (h < 0 || l < 0) {
            return false
        }
        val c = (h * 16 + l).toChar()
        // URLEncoder leaves [a-zA-Z0-9.*_-] alone and turns a space into '+'; it percent-encodes
        // everything else, in uppercase hex.
        return c.code < 0x80 && c != ' ' && !isSegmentChar(c) && c != '*'
    }

    private fun upperHex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
