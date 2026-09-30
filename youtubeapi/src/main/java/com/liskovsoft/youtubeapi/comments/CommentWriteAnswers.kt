package com.liskovsoft.youtubeapi.comments

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.liskovsoft.mediaserviceinterfaces.data.CommentItem
import com.liskovsoft.youtubeapi.comments.gen.CommentItemWrapper
import com.liskovsoft.youtubeapi.comments.gen.CommentRenderer
import com.liskovsoft.youtubeapi.comments.impl.CommentItemImpl

/**
 * NEWTUBE(write-comments): what YouTube answered to a new comment, reply or delete.
 *
 * The answers are read loosely on purpose. A new comment comes back inside a list mutation
 * (actions[].listMutationCommand...commentThreadRenderer.comment.commentRenderer) next to
 * placeholder renderers that only carry trackingParams, and a reply's is nested the same way with
 * a different wrapper; so the comment is the first commentRenderer that has a commentId, wherever
 * it is. Success is the actionResult's status: the top-level one for a create, the
 * removeCommentAction's for a delete (which must say so explicitly, for the right comment). A
 * refusal is thrown as "ErrorResponse: <YouTube's words>", the form RetrofitHelper gives an HTTP
 * error's message, so a caller finds the reason one way.
 */
internal object CommentWriteAnswers {
    private const val SUCCEEDED = "STATUS_SUCCEEDED"
    private val gson = Gson()

    /**
     * The comment YouTube created; throws with YouTube's reason when it refused. A post YouTube
     * reports done but does not describe comes back as an empty item (no id), never as a failure:
     * the person would be offered a retry and post it twice.
     */
    fun createdComment(answer: JsonObject?): CommentItem {
        answer ?: throw IllegalStateException("No answer")
        val result = findFirst(answer, "actionResult") as? JsonObject
        val status = statusOf(result)
        if (status != null && status != SUCCEEDED) {
            throw refusal(result, status)
        }
        val renderer = findCommentRenderer(answer)
        if (renderer == null) {
            if (status == SUCCEEDED) {
                return CommentItemImpl(CommentItemWrapper(null, null))
            }
            throw IllegalStateException("No comment in the answer")
        }
        val comment = gson.fromJson(renderer, CommentRenderer::class.java)
        return CommentItemImpl(CommentItemWrapper(
            CommentItemWrapper.CommentThreadRenderer(CommentItemWrapper.CommentThreadRenderer.Comment(comment)),
            null))
    }

    /** YouTube removed [commentId]: its removeCommentAction says so, explicitly. */
    fun checkDeleted(answer: JsonObject?, commentId: String) {
        answer ?: throw IllegalStateException("No answer")
        val remove = findFirst(answer, "removeCommentAction") as? JsonObject
        val result = (remove?.get("actionResult") ?: findFirst(answer, "actionResult")) as? JsonObject
        val status = statusOf(result) ?: throw IllegalStateException("No result in the answer")
        if (status != SUCCEEDED) {
            throw refusal(result, status)
        }
        val removed = remove?.get("commentId")?.takeIf { it.isJsonPrimitive }?.asString
        if (removed != null && removed != commentId) {
            throw IllegalStateException("The answer names another comment")
        }
    }

    private fun statusOf(result: JsonObject?): String? =
        result?.get("status")?.takeIf { it.isJsonPrimitive }?.asString

    private fun refusal(result: JsonObject?, status: String) =
        IllegalStateException("ErrorResponse: " + ((result?.let { feedback(it) }) ?: status))

    /** "Comment added", "Comment deleted", or YouTube's reason for refusing. */
    private fun feedback(result: JsonObject): String? {
        val runs = (result.get("feedbackText") as? JsonObject)?.get("runs") as? JsonArray ?: return null
        val text = runs.mapNotNull { (it as? JsonObject)?.get("text")?.takeIf { t -> t.isJsonPrimitive }?.asString }
            .joinToString("")
        return text.ifEmpty { null }
    }

    private fun findCommentRenderer(node: JsonElement?): JsonObject? {
        when (node) {
            is JsonObject -> {
                val renderer = node.get("commentRenderer") as? JsonObject
                if (renderer != null && renderer.has("commentId")) {
                    return renderer
                }
                for ((_, value) in node.entrySet()) {
                    findCommentRenderer(value)?.let { return it }
                }
            }
            is JsonArray -> for (value in node) {
                findCommentRenderer(value)?.let { return it }
            }
            else -> {}
        }
        return null
    }

    private fun findFirst(node: JsonElement?, key: String): JsonElement? {
        when (node) {
            is JsonObject -> {
                node.get(key)?.let { return it }
                for ((_, value) in node.entrySet()) {
                    findFirst(value, key)?.let { return it }
                }
            }
            is JsonArray -> for (value in node) {
                findFirst(value, key)?.let { return it }
            }
            else -> {}
        }
        return null
    }
}
