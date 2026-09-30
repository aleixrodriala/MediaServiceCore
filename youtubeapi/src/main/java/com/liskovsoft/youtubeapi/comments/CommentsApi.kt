package com.liskovsoft.youtubeapi.comments

import com.google.gson.JsonObject
import com.liskovsoft.youtubeapi.comments.gen.CommentsResult
import com.liskovsoft.googlecommon.common.converters.gson.WithGson
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

@WithGson
internal interface CommentsApi {
    @Headers("Content-Type: application/json")
    @POST("https://www.youtube.com/youtubei/v1/next")
    fun getComments(@Body commentsQuery: String): Call<CommentsResult?>

    @Headers("Content-Type: application/json")
    @POST("https://www.youtube.com/youtubei/v1/comment/perform_comment_action")
    fun commentAction(@Body actionQuery: String): Call<Void?>

    // NEWTUBE(write-comments): the answers are read loosely (CommentWriteAnswers): YouTube nests the
    // new comment differently in each, and only its commentRenderer and actionResult matter.

    @Headers("Content-Type: application/json")
    @POST("https://www.youtube.com/youtubei/v1/comment/create_comment")
    fun createComment(@Body createQuery: String): Call<JsonObject?>

    @Headers("Content-Type: application/json")
    @POST("https://www.youtube.com/youtubei/v1/comment/create_comment_reply")
    fun createCommentReply(@Body replyQuery: String): Call<JsonObject?>

    @Headers("Content-Type: application/json")
    @POST("https://www.youtube.com/youtubei/v1/comment/perform_comment_action")
    fun commentActionWithAnswer(@Body actionQuery: String): Call<JsonObject?>
}