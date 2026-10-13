package com.liskovsoft.youtubeapi.utils

import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper

internal object UtilsService {
    private val mUtilsApi = RetrofitHelper.create(UtilsApi::class.java)

    /**
     * "@name" comes from an @handle link (youtube.com/@MrBeast2) and from the legacy
     * /c/name and /user/name links (there the "@" only marks it as unresolved). Try the handle
     * page first: the bare youtube.com/name a handle used to be sent to is a 404 for most
     * handles, which left those links on an empty channel page. Then the legacy forms.
     */
    @JvmStatic
    fun canonicalChannelId(channelId: String?): String? {
        if (channelId == null || !channelId.startsWith("@")) return channelId

        val name = channelId.substring(1)

        for (path in listOf(channelId, name, "c/$name", "user/$name")) {
            val resolved = try {
                RetrofitHelper.get(mUtilsApi.canonicalChannelId(path))?.channelId
            } catch (e: Exception) {
                null
            }
            if (resolved != null) return resolved
        }

        return null
    }
}
