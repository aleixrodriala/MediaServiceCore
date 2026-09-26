package com.liskovsoft.youtubeapi.videoinfo.models

import com.liskovsoft.googlecommon.common.helpers.YouTubeHelper
import com.liskovsoft.sharedutils.querystringparser.UrlQueryString
import com.liskovsoft.sharedutils.querystringparser.UrlQueryStringFactory

private const val PARAM_URL = "url"
private const val PARAM_S = "s"
private const val PARAM_CIPHER = "cipher"
private const val PARAM_TYPE = "type"
private const val PARAM_ITAG = "itag"
private const val PARAM_CPN = "cpn"
private const val PARAM_POT = "pot"
private const val PARAM_CVER = "cver"
private const val PARAM_SIGNATURE = "signature"
private const val PARAM_SIGNATURE_SPECIAL = "sig"
private const val PARAM_SIGNATURE_SPECIAL_MARK = "lsig"
private const val PARAM_EVENT_ID = "ei"
private const val PARAM_N = "n"

internal class VideoUrlHolder(private var url: String? = null,
                              private var cipher: String? = null,
                              private var signatureCipher: String? = null) {
    private var extractedSParam: String? = null
    private var realSignature: String? = null
    private var urlQuery: UrlQueryString? = null
    // NEWTUBE(open-cpu): urlQuery printed back (a URLEncoder call per parameter); valid until the
    // next setParam. getUrl() runs ~3x per format while the format info is built.
    private var urlQueryText: String? = null
    // NEWTUBE(open-cpu): a lookup was answered from the raw url (CanonicalQueryUrl) where the old
    // code built urlQuery. Only setUrl can tell the difference, see there.
    private var queryVirtual = false
    private var canonicalFor: String? = null
    private var canonical = false

    fun getUrl(): String? {
        parseCipher()

        // Bypass query creation if url isn't transformed
        val query = urlQuery ?: return url

        return urlQueryText ?: query.toString().also { urlQueryText = it }
    }

    fun setUrl(url: String?) {
        // A lookup used to build urlQuery from the previous url, and getUrl() then kept printing
        // that query whatever setUrl said. Build it now, so that stays true.
        if (queryVirtual && urlQuery == null) {
            getUrlQuery()
        }

        this.url = url
    }

    /**
     * Contains segments list and durations (required for stream switch!!!)
     */
    fun getOtfInitUrl(): String? {
        return getUrl()?.let { "$it&sq=0" }
    }

    fun getOtfTemplateUrl(): String? {
        return getUrl()?.let { "$it&sq=\$Number$" }
    }

    fun getSParam(): String? {
        parseCipher()
        return extractedSParam
    }

    fun getSignature(): String? {
        return realSignature
    }

    fun setSignature(signature: String?) {
        if (signature != null) {
            setParam(PARAM_SIGNATURE_SPECIAL, signature)

            realSignature = signature
        }
    }

    fun getNParam(): String? {
        return getParam(PARAM_N)
    }

    fun setNParam(throttleCipher: String?) {
        setParam(PARAM_N, throttleCipher)
    }

    fun setClientVersion(clientVersion: String?) {
        setParam(PARAM_CVER, clientVersion)
    }

    fun setCpn(cpn: String?) {
        setParam(PARAM_CPN, cpn)
    }

    fun setPoToken(poToken: String?) {
        setParam(PARAM_POT, poToken);
    }

    fun getLanguage(): String? {
        val xtags = getParam("xtags") ?: return null

        // Example: acont=dubbed:lang=ar
        val xtagsQuery = UrlQueryStringFactory.parse(xtags.replace(":", "&"))
        val lang = xtagsQuery.get("lang")
        val acont = xtagsQuery.get("acont")
        // original, descriptive, dubbed, dubbed-auto, secondary
        return if (lang != null && acont != null) String.format("%s (%s)", YouTubeHelper.exoNameFix(lang), acont) else lang
    }

    fun getParam(paramName: String?): String? {
        // NEWTUBE(open-cpu): nothing modified the url yet and its query round-trips unchanged, so
        // answer from the raw text and leave the (costly) parser unbuilt - see CanonicalQueryUrl.
        if (urlQuery == null && paramName != null) {
            parseCipher()
            val rawUrl = url ?: return null
            if (isCanonical(rawUrl)) {
                queryVirtual = true
                return CanonicalQueryUrl.get(rawUrl, paramName)
            }
        }

        val queryString = getUrlQuery()

        if (queryString != null) {
            return queryString.get(paramName)
        }

        return null
    }

    fun setParam(paramName: String?, paramValue: String?) {
        val queryString = getUrlQuery()

        if (queryString != null && paramName != null && paramValue != null) {
            queryString.set(paramName, paramValue)
            urlQueryText = null
        }
    }

    private fun isCanonical(rawUrl: String): Boolean {
        if (rawUrl !== canonicalFor) {
            canonical = CanonicalQueryUrl.isCanonical(rawUrl)
            canonicalFor = rawUrl
        }

        return canonical
    }

    private fun parseCipher() {
        if (url == null) { // items signatures are ciphered
            val cipherUri: String? = cipher ?: signatureCipher

            if (cipherUri != null) {
                val queryString = UrlQueryStringFactory.parse(cipherUri)
                url = queryString.get(PARAM_URL)
                extractedSParam = queryString.get(PARAM_S)
            }
        }
    }

    private fun getUrlQuery(): UrlQueryString? {
        parseCipher()

        if (url == null) {
            return null
        }

        if (urlQuery == null) {
            urlQuery = UrlQueryStringFactory.parse(url)
        }

        return urlQuery
    }
}