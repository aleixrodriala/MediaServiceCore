package com.liskovsoft.googlecommon.common.helpers

import okio.ByteString.Companion.decodeBase64
import java.security.MessageDigest

/**
 * NEWTUBE(visitor): the short NetPath fingerprint of an anonymous visitor IDENTITY.
 *
 * visitorData is a base64 protobuf whose field 1 is the visitor id (the VISITOR_INFO1_LIVE value);
 * its other fields - a mint timestamp and a YNID blob - change on every youtube.com/tv refresh even
 * when the id does not (checked 2026-09-25: two fetches replaying the same cookie returned the same
 * id and two different strings). Hashing the whole string therefore made every 10-hour app-info
 * refresh look like a new visitor in the logs. This hashes the decoded id instead, so `visitor=`
 * changes only when the identity does. The id itself is never logged. A value that does not decode
 * as visitorData falls back to hashing the whole string, as before.
 */
object VisitorFingerprint {
    private val VISITOR_ID = Regex("[A-Za-z0-9_-]{1,64}")

    @JvmStatic
    fun of(visitorData: String?): String {
        if (visitorData.isNullOrEmpty()) {
            return "none"
        }
        return hash(visitorId(visitorData) ?: visitorData)
    }

    /** Field 1 of the visitorData protobuf, or null when [visitorData] is not in that shape. */
    @JvmStatic
    fun visitorId(visitorData: String): String? {
        val bytes = try {
            // Header and ytcfg copies arrive URL-encoded ("...%3D%3D"); the alphabet is URL-safe.
            visitorData.replace("%3D", "=").replace("%3d", "=").decodeBase64()?.toByteArray()
        } catch (_: Exception) {
            null
        } ?: return null

        // Tag 0x0A = field 1, length-delimited; the id is short, so its length is a 1-byte varint.
        if (bytes.size < 3 || bytes[0].toInt() != 0x0A) {
            return null
        }
        val length = bytes[1].toInt() and 0xFF
        if (length == 0 || length >= 0x80 || 2 + length > bytes.size) {
            return null
        }
        val id = String(bytes, 2, length, Charsets.US_ASCII)
        return if (VISITOR_ID.matches(id)) id else null
    }

    private fun hash(value: String): String {
        return try {
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .take(5).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            Integer.toHexString(value.hashCode())
        }
    }
}
