package com.liskovsoft.youtubeapi.comments

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CommentWriteAnswersTest {
    @Test
    fun readsTheNewComment() {
        val comment = CommentWriteAnswers.createdComment(fixture("create_comment.json"))
        assertEquals("UgwbBtiUuiLoDx-QlId4AaABAg", comment.id)
        assertEquals("@testaccount", comment.authorName)
        assertEquals("Testing, will delete this in a minute", comment.message)
        assertEquals("0 seconds ago", comment.publishedDate)
        assertNotNull(comment.authorPhoto)
        // Its replies page (and so its like button) works like any other comment's.
        assertNotNull(comment.nestedCommentsKey)
        assertNull(comment.replyCount)
        assertFalse(comment.isLiked)
    }

    @Test
    fun readsTheNewReply() {
        val reply = CommentWriteAnswers.createdComment(fixture("create_comment_reply.json"))
        assertEquals("Ugws8WkM4-stbgyJzI54AaABAg.AbOBNn0tNCeAbOBPW6vShG", reply.id)
        assertEquals("Test reply, deleting soon", reply.message)
        assertEquals("@testaccount", reply.authorName)
    }

    @Test
    fun aDeleteThatWentThrough() {
        CommentWriteAnswers.checkDeleted(fixture("delete_comment.json"), "Ugws8WkM4-stbgyJzI54AaABAg.AbOBNn0tNCeAbOBPW6vShG")
    }

    @Test
    fun aDeleteMustSaySoForTheRightComment() {
        assertThrows(IllegalStateException::class.java) {
            CommentWriteAnswers.checkDeleted(fixture("delete_comment.json"), "UgwSomeOtherComment")
        }
        for (answer in listOf("""{"actionResult":{}}""", """{"actionResult":null}""", """{"actions":[]}""",
                """{"actions":[{"removeCommentAction":{"actionResult":{"status":"STATUS_FAILED"}}}]}""")) {
            assertThrows(answer, IllegalStateException::class.java) {
                CommentWriteAnswers.checkDeleted(JsonParser.parseString(answer).asJsonObject, "x")
            }
        }
    }

    @Test
    fun aRefusalCarriesYouTubesReason() {
        val refused = JsonParser.parseString(
            """{"actionResult":{"status":"STATUS_FAILED","feedbackText":{"runs":[{"text":"Comment failed to post."}]}}}""")
            .asJsonObject
        val error = assertThrows(IllegalStateException::class.java) { CommentWriteAnswers.createdComment(refused) }
        assertEquals("ErrorResponse: Comment failed to post.", error.message)
        assertThrows(IllegalStateException::class.java) { CommentWriteAnswers.checkDeleted(refused, "x") }
    }

    @Test
    fun postedButNotDescribedIsNotAFailure() {
        // A retry here would post the comment twice: an empty item instead.
        val done = JsonParser.parseString("""{"actionResult":{"status":"STATUS_SUCCEEDED"}}""").asJsonObject
        assertNull(CommentWriteAnswers.createdComment(done).id)
    }

    @Test
    fun anAnswerWithNeitherResultNorComment() {
        assertThrows(IllegalStateException::class.java) { CommentWriteAnswers.createdComment(JsonObject()) }
        assertThrows(IllegalStateException::class.java) { CommentWriteAnswers.createdComment(null) }
        assertThrows(IllegalStateException::class.java) { CommentWriteAnswers.checkDeleted(null, "x") }
    }

    private fun fixture(name: String): JsonObject =
        javaClass.classLoader!!.getResourceAsStream("comments/$name")!!.reader().use {
            JsonParser.parseReader(it).asJsonObject
        }
}
