package com.liskovsoft.youtubeapi.comments

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URLDecoder
import java.util.Base64

class CreateCommentParamsTest {
    @Test
    fun createParamsMatchACapturedWorkingRequest() {
        // From a create_comment request that posted (upstream MediaServiceCore PR #40).
        assertEquals("EgtHcDNCUmJOU0xFQSoAUAc%3D", CommentsApiParams.createCommentParams("Gp3BRbNSLEA"))
    }

    @Test
    fun replyParamsMatchTheOnesThatPosted() {
        // The reply that posted from the test account on 2026-09-30.
        assertEquals("EgtqTlFYQUM5SVZSdyIaVWd3czhXa000LXN0Ymd5SnpJNTRBYUFCQWcqAggAUAc%3D",
            CommentsApiParams.createReplyParams("jNQXAC9IVRw", "Ugws8WkM4-stbgyJzI54AaABAg"))
    }

    @Test
    fun deleteParamsMatchTheOnesThatDeleted() {
        assertEquals("CAYQAhoaVWd3YkJ0aVV1aUxvRHgtUWxJZDRBYUFCQWcqC2pOUVhBQzlJVlJ3",
            CommentsApiParams.deleteCommentParams("jNQXAC9IVRw", "UgwbBtiUuiLoDx-QlId4AaABAg"))
    }

    @Test
    fun aReplyIdIsWrittenWhole() {
        val replyId = "Ugws8WkM4-stbgyJzI54AaABAg.AbOBNn0tNCeAbOBPW6vShG"
        val bytes = decode(CommentsApiParams.deleteCommentParams("jNQXAC9IVRw", replyId))
        // {1: 6, 2: 2, 3: <49 bytes>, 5: <11 bytes>}
        assertEquals(listOf(0x08, 6, 0x10, 2, 0x1A, replyId.length), bytes.take(6).map { it.toInt() and 0xFF })
        assertEquals(replyId, String(bytes, 6, replyId.length))
        assertEquals(0x2A, bytes[6 + replyId.length].toInt() and 0xFF)
    }

    @Test
    fun longTextsGetMultiByteLengths() {
        // A string over 127 bytes needs a two-byte varint length.
        val bytes = decode(CommentsApiParams.createReplyParams("jNQXAC9IVRw", "x".repeat(200)))
        assertEquals(listOf(0x22, 0xC8, 0x01), bytes.drop(13).take(3).map { it.toInt() and 0xFF })
    }

    @Test
    fun escapesTheText() {
        assertEquals("\"createCommentParams\":\"EgtqTlFYQUM5SVZSdyoAUAc%3D\",\"commentText\":\"a \\\"quote\\\"\\nline\"",
            CommentsApiParams.createCommentData("jNQXAC9IVRw", "a \"quote\"\nline"))
    }

    private fun decode(params: String): ByteArray = Base64.getDecoder().decode(URLDecoder.decode(params, "UTF-8"))
}
