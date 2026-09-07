package com.liskovsoft.googlecommon.common.helpers

import android.app.Application
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Real request-body inspector only: no HTTP client, account state, or app initialization. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PlayerBodyTimestampDiagnosticsTest {
    @Test
    fun fiveDigitIntegerReportsFive() {
        assertDigits(5, "20697")
    }

    @Test
    fun eightDigitIntegerReportsEight() {
        assertDigits(8, "20697000")
    }

    @Test
    fun missingTimestampReportsZeroAndAbsent() {
        val info = inspect(request("""{"videoId":"fixture"}"""))
        assertEquals(0, field(info, "signatureTimestampDigits"))
        assertFalse(field(info, "hasSignatureTimestamp") as Boolean)
    }

    @Test
    fun negativeTimestampReportsZeroButStillPresent() {
        assertDigits(0, "-20697")
        assertDigits(0, "-20697000")
    }

    @Test
    fun nonIntegerValuesNeverReportTheirNumericPrefix() {
        for (value in listOf("20697.5", "20697.0", "20697e3", "20697E+3", "\"20697\"", "null", "true")) {
            assertDigits(0, value)
        }
    }

    @Test
    fun zeroAndMalformedNumericValuesReportZero() {
        for (value in listOf("0", "020697", "+20697", "20697oops")) {
            assertDigits(0, value)
        }
    }

    @Test
    fun whitespaceAndCommaDelimiterPreserveExistingDiagnosticsAndRequestBytes() {
        val json = """{
            "videoId":"fixture",
            "contentCheckOk":true,
            "racyCheckOk":true,
            "playbackContext":{"contentPlaybackContext":{
                "signatureTimestamp" : 20697000   , "other":false
            }}
        }"""
        val request = request(json)
        val info = inspect(request)

        assertEquals(8, field(info, "signatureTimestampDigits"))
        assertEquals("fixture", field(info, "videoId"))
        assertTrue(field(info, "hasSignatureTimestamp") as Boolean)
        assertTrue(field(info, "contentCheckOk") as Boolean)
        assertTrue(field(info, "racyCheckOk") as Boolean)
        assertFalse(field(info, "hasPoToken") as Boolean)
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        assertEquals(json, buffer.readUtf8())
    }

    @Test
    fun absentRequestBodyReportsZero() {
        val info = inspect(Request.Builder().url("https://fixture.invalid/player").build())
        assertEquals(0, field(info, "signatureTimestampDigits"))
        assertFalse(field(info, "hasSignatureTimestamp") as Boolean)
    }

    private fun assertDigits(expected: Int, value: String) {
        val info = inspect(request("""{"playbackContext":{"contentPlaybackContext":{"signatureTimestamp":$value}}}"""))
        assertEquals("value=$value", expected, field(info, "signatureTimestampDigits"))
        assertTrue("presence is separate from valid integer format", field(info, "hasSignatureTimestamp") as Boolean)
    }

    private fun request(json: String): Request = Request.Builder()
        .url("https://fixture.invalid/player")
        .post(json.toRequestBody("application/json".toMediaType()))
        .build()

    private fun inspect(request: Request): Any {
        val method = RetrofitOkHttpHelper::class.java.getDeclaredMethod("inspectPlayerBody", Request::class.java)
        method.isAccessible = true
        return method.invoke(RetrofitOkHttpHelper, request)
    }

    private fun field(info: Any, name: String): Any {
        val field = info.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(info)
    }
}
