package com.liskovsoft.youtubeapi.comments

import com.google.gson.Gson
import com.liskovsoft.youtubeapi.common.helpers.PostDataHelper
import okio.Buffer
import java.net.URLEncoder

internal object CommentsApiParams {
    private const val COMMENT_ACTION_TEMPLATE: String = "\"actions\":[\"%s\"]"

    fun getCommentsQuery(commentsKey: String): String {
        val chatData = String.format("\"continuation\":\"%s\"", commentsKey)
        return PostDataHelper.createQueryTV(chatData)
    }

    fun getActionQuery(actionKey: String): String {
        return PostDataHelper.createQueryTV(String.format(COMMENT_ACTION_TEMPLATE, actionKey))
    }

    // NEWTUBE(write-comments): writing with the TV sign-in, TVHTML5 context. The TV comments views
    // carry no create/reply/delete params (the TV app only reads), so they are built here, in the
    // layouts YouTube's web client sends (create: upstream MediaServiceCore PR #40; reply: the one
    // YouTube.js v1 used; delete: perform_comment_action type 6). All three posted and deleted a
    // test account's comment and reply on 2026-09-30.

    fun getCreateCommentQuery(videoId: String, commentText: String): String =
        PostDataHelper.createQueryTV(createCommentData(videoId, commentText))

    fun getCreateReplyQuery(videoId: String, parentCommentId: String, commentText: String): String =
        PostDataHelper.createQueryTV(createReplyData(videoId, parentCommentId, commentText))

    fun getDeleteCommentQuery(videoId: String, commentId: String): String =
        getActionQuery(deleteCommentParams(videoId, commentId))

    fun createCommentData(videoId: String, commentText: String): String =
        "\"createCommentParams\":\"${createCommentParams(videoId)}\",\"commentText\":${Gson().toJson(commentText)}"

    fun createReplyData(videoId: String, parentCommentId: String, commentText: String): String =
        "\"createReplyParams\":\"${createReplyParams(videoId, parentCommentId)}\",\"commentText\":${Gson().toJson(commentText)}"

    /** {2: videoId, 5: {}, 10: 7} */
    fun createCommentParams(videoId: String): String =
        encode(Proto().string(2, videoId).message(5, Proto()).varint(10, 7))

    /** {2: videoId, 4: the top-level comment's id, 5: {1: 0}, 10: 7} */
    fun createReplyParams(videoId: String, parentCommentId: String): String =
        encode(Proto().string(2, videoId).string(4, parentCommentId).message(5, Proto().varint(1, 0)).varint(10, 7))

    /** {1: 6 (delete), 2: 2, 3: commentId, 5: videoId}; a reply's id is "parentId.replyId". */
    fun deleteCommentParams(videoId: String, commentId: String): String =
        encode(Proto().varint(1, 6).varint(2, 2).string(3, commentId).string(5, videoId))

    /** Standard base64 with padding, URL-encoded, as YouTube's own clients send these params. */
    private fun encode(proto: Proto): String = URLEncoder.encode(proto.bytes.readByteString().base64(), "UTF-8")

    /** Just enough protobuf: varints, strings and nested messages, in the order written. */
    private class Proto {
        val bytes = Buffer()

        fun varint(field: Int, value: Long): Proto = apply { tag(field, 0); writeVarint(value) }

        fun varint(field: Int, value: Int): Proto = varint(field, value.toLong())

        fun string(field: Int, value: String): Proto = apply {
            val utf8 = value.toByteArray(Charsets.UTF_8)
            tag(field, 2)
            writeVarint(utf8.size.toLong())
            bytes.write(utf8)
        }

        fun message(field: Int, value: Proto): Proto = apply {
            tag(field, 2)
            writeVarint(value.bytes.size)
            bytes.writeAll(value.bytes)
        }

        private fun tag(field: Int, wireType: Int) = writeVarint(((field shl 3) or wireType).toLong())

        private fun writeVarint(value: Long) {
            var v = value
            while (v and 0x7F.inv().toLong() != 0L) {
                bytes.writeByte(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            bytes.writeByte(v.toInt())
        }
    }
}