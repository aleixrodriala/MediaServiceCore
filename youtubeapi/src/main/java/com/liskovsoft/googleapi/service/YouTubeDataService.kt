package com.liskovsoft.googleapi.service

import com.liskovsoft.googleapi.youtubedata3.YouTubeDataServiceInt
import com.liskovsoft.googleapi.youtubedata3.impl.ItemMetadata
import com.liskovsoft.sharedutils.rx.RxHelper
import io.reactivex.rxjava3.core.Observable

object YouTubeDataService {
    @JvmStatic
    fun getVideoMetadata(vararg videoIds: String): Observable<List<ItemMetadata>> {
        return RxHelper.fromCallable { requireNotNull(YouTubeDataServiceInt.getVideoMetadata(*videoIds)) }
    }

    @JvmStatic
    fun getChannelMetadata(vararg channelIds: String): Observable<List<ItemMetadata>> {
        return RxHelper.fromCallable { requireNotNull(YouTubeDataServiceInt.getChannelMetadata(*channelIds)) }
    }

    @JvmStatic
    fun getPlaylistMetadata(vararg playlistIds: String): Observable<List<ItemMetadata>> {
        return RxHelper.fromCallable { requireNotNull(YouTubeDataServiceInt.getPlaylistMetadata(*playlistIds)) }
    }
}
