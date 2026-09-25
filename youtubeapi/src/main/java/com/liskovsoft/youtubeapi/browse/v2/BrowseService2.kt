package com.liskovsoft.youtubeapi.browse.v2

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.youtubeapi.browse.v2.gen.*
import com.liskovsoft.youtubeapi.common.helpers.AppClient
import com.liskovsoft.youtubeapi.common.models.impl.mediagroup.*
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper
import com.liskovsoft.youtubeapi.common.helpers.PostDataHelper
import com.liskovsoft.youtubeapi.common.models.gen.ItemWrapper
import com.liskovsoft.youtubeapi.common.models.gen.getPlaylistId
import com.liskovsoft.youtubeapi.common.models.impl.mediaitem.ShortsMediaItem
import com.liskovsoft.youtubeapi.next.v2.gen.getItems
import com.liskovsoft.youtubeapi.next.v2.gen.getContinuationToken
import com.liskovsoft.youtubeapi.next.v2.gen.getShelves
import com.liskovsoft.youtubeapi.service.YouTubeSignInService
import com.liskovsoft.mediaserviceinterfaces.SignInService
import com.liskovsoft.mediaserviceinterfaces.oauth.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

internal open class BrowseService2 {
    private val mBrowseApi = RetrofitHelper.create(BrowseApi::class.java)

    //fun getHome(): List<MediaGroup?>? {
    //    val home = getBrowseRows(BrowseApiHelper.getHomeQueryWeb(), MediaGroup.TYPE_HOME)
    //    return if (home?.size ?: 0 < 5) listOfNotNull(home, getRecommended()).flatten() else home
    //}

    fun getHome(): Pair<List<MediaGroup?>?, String?>? {
        //val rows = getBrowseRows(BrowseApiHelper.getHomeQueryWeb(), MediaGroup.TYPE_HOME)
        //
        //if (rows?.all { it?.isEmpty == true } != false) // in anonymous mode WEB home page is empty
        //    return getBrowseRowsTV(BrowseApiHelper.getHomeQueryTV(), MediaGroup.TYPE_HOME)
        //
        //return Pair(rows, null)

        // Anonymous TV "default" home personalizes off the persistent visitor id: watch-time
        // pings credit history to it, and once any exists the same home query used signed-in
        // returns real recommendation shelves (verified with raw /browse probes). Requires the
        // visitor id to survive AppInfo refreshes - see the consent cookie in AppServiceInt.
        // A visitor with no history yet gets only a "start searching" nudge (zero shelves), so
        // fall back to the TV topic feeds that serve shelves without any history (same feeds as
        // the Explore sections). FEtrending itself is RETIRED - 400 on every client since
        // YouTube killed the Trending page.
        if (!YouTubeSignInService.instance().isSigned()) {
            // NEWTUBE(home-parallel): the topic feeds used to run one after another behind the probe
            // (5 serial /browse, ~2.75 s before first paint on a fresh install). See AnonymousHome.
            AnonymousHome.watchAccountChanges()

            return AnonymousHome.resolve(
                { getBrowseRowsTV(BrowseApiHelper::getHomeQuery, MediaGroup.TYPE_HOME) },
                listOf(BrowseApiHelper::getMusicQuery, BrowseApiHelper::getGamingQuery,
                    BrowseApiHelper::getNewsQuery, BrowseApiHelper::getSportsQuery).map { query ->
                    { getBrowseRowsTV(query, MediaGroup.TYPE_HOME) }
                }
            )
        }

        return getBrowseRowsTV(BrowseApiHelper::getHomeQuery, MediaGroup.TYPE_HOME)
    }

    fun getTrending(): List<MediaGroup?>? {
        return getBrowseRowsWeb(BrowseApiHelper.getTrendingQuery(AppClient.WEB), MediaGroup.TYPE_TRENDING)
    }

    fun getSports(): Pair<List<MediaGroup?>?, String?>? {
        return getBrowseRowsTV(BrowseApiHelper::getSportsQuery, MediaGroup.TYPE_SPORTS)
    }

    fun getLive(): Pair<List<MediaGroup?>?, String?>? {
        return getBrowseRowsTV(BrowseApiHelper::getLiveQuery, MediaGroup.TYPE_LIVE)
    }

    fun getMyVideos(): MediaGroup? {
        return getBrowseGridTV(BrowseApiHelper::getMyVideosQuery, MediaGroup.TYPE_MY_VIDEOS)
    }

    fun getMovies(): Pair<List<MediaGroup?>?, String?>? {
        return getBrowseRowsTV(BrowseApiHelper::getMoviesQuery, MediaGroup.TYPE_MOVIES)
    }

    fun getKidsHome(): List<MediaGroup?>? {
        val options = MediaGroupOptions.create(MediaGroup.TYPE_KIDS_HOME)
        val kidsResult = mBrowseApi.getBrowseResultKids(BrowseApiHelper.getKidsHomeQuery())

        return RetrofitHelper.get(kidsResult)?.let {
            val result = mutableListOf<MediaGroup?>()
            it.getRootSection()?.let { result.add(KidsSectionMediaGroup(it, options)) }
            it.getSections()?.forEach {
                if (it?.getItems() == null && it?.getParams() != null) {
                    val kidsResultNested = mBrowseApi.getBrowseResultKids(BrowseApiHelper.getKidsHomeQuery(it.getParams()!!))
                    RetrofitHelper.get(kidsResultNested)?.getRootSection()?.let {
                        result.add(KidsSectionMediaGroup(it, options))
                    }
                }
            }

            result
        }
    }

    open fun getSubscriptions(): MediaGroup? {
        return getSubscriptionsTV()
    }

    private fun getSubscriptionsWeb(): MediaGroup? {
        val browseResult = mBrowseApi.getBrowseResult(BrowseApiHelper.getSubscriptionsQuery(AppClient.WEB))

        return RetrofitHelper.get(browseResult)?.let { BrowseMediaGroup(it, MediaGroupOptions.create(MediaGroup.TYPE_SUBSCRIPTIONS)) }
    }

    private fun getSubscriptionsTV(): MediaGroup? {
        val options = MediaGroupOptions.create(MediaGroup.TYPE_SUBSCRIPTIONS)
        val browseResult = mBrowseApi.getBrowseResultTV(BrowseApiHelper.getSubscriptionsQuery(options.clientTV))

        return RetrofitHelper.get(browseResult)?.let {
            // Prepare to move LIVE items to the top. Multiple results should be combined first.
            val (overrideItems, overrideKey) = continueIfNeededTV(it.getItems(), it.getContinuationToken(), options)

            BrowseMediaGroupTV(it, options, overrideItems = overrideItems, overrideKey = overrideKey)
        }
    }

    open fun getSubscribedChannels(): MediaGroup? {
        return getSubscribedChannelsTV() ?: getSubscribedChannelsWeb()
    }

    private fun getSubscribedChannelsWeb(): MediaGroup? {
        val options = MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS)
        val guideResult = mBrowseApi.getGuideResult(PostDataHelper.createQueryWeb(""))

        return RetrofitHelper.get(guideResult)?.let { GuideMediaGroup(it, options) }
    }

    private fun getSubscribedChannelsTV(sortByName: Boolean = false): MediaGroup? {
        val options = MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS)
        val browseResult = mBrowseApi.getBrowseResultTV(BrowseApiHelper.getSubscriptionsQuery(options.clientTV))

        return RetrofitHelper.get(browseResult)?.let { it.getTabs()?.let { ChannelListMediaGroup(it, options, if (sortByName) SORT_BY_NAME else SORT_DEFAULT) } }
    }

    open fun getSubscribedChannelsByName(): MediaGroup? {
        return getSubscribedChannelsTV(sortByName = true) ?: getSubscribedChannelsByNameWeb()
    }

    private fun getSubscribedChannelsByNameWeb(): MediaGroup? {
        val options = MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS)
        val guideResult = mBrowseApi.getGuideResult(PostDataHelper.createQueryWeb(""))

        return RetrofitHelper.get(guideResult)?.let { GuideMediaGroup(it, options, SORT_BY_NAME) }
    }

    open fun getSubscribedChannelsByNewContent(): MediaGroup? {
        return getSubscribedChannelsByNewContentTV()
    }

    private fun getSubscribedChannelsByNewContentTV(): MediaGroup? {
        val options = MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS)
        val browseResult = mBrowseApi.getBrowseResultTV(BrowseApiHelper.getSubscriptionsQuery(options.clientTV))

        return RetrofitHelper.get(browseResult)?.let { it.getTabs()?.let { ChannelListMediaGroup(it, options, SORT_BY_NEW_CONTENT) } }
    }

    fun getShorts(): MediaGroup? {
        return getShortsTV() ?: getShortsWeb()
    }

    fun getShorts2(): MediaGroup? {
        return getShortsWeb()
    }

    private fun getShortsWeb(auth: Boolean = false): MediaGroup? {
        val firstResult = mBrowseApi.getReelResult(BrowseApiHelper.getReelQuery())

        return RetrofitHelper.get(firstResult, auth) ?.let { firstItem ->
            val result = continueShortsWeb(firstItem.getContinuationToken(), auth)
            result?.mediaItems?.add(0, ShortsMediaItem(null, firstItem))

            if (auth)
                getSubscribedShortsWeb()?.let { result?.mediaItems?.addAll(0, it) }

            return result
        }
    }

    private fun getShortsTV(): MediaGroup? {
        val options = MediaGroupOptions.create(MediaGroup.TYPE_SHORTS)
        val browseResult = mBrowseApi.getBrowseResultTV(BrowseApiHelper.getSubscriptionsQuery(options.clientTV))

        return RetrofitHelper.get(browseResult)?.let {
            it.getShortItems()?.let { SubscribedShortsMediaGroup(it) }
        }
    }

    fun getMusic(): Pair<List<MediaGroup?>?, String?>? {
        return getBrowseRowsTV(BrowseApiHelper::getMusicQuery, MediaGroup.TYPE_MUSIC)
    }

    fun getLikedMusic(): MediaGroup? {
        return getLikedMusicTV() ?: getLikedMusicWeb()
    }

    fun getNews(): Pair<List<MediaGroup?>?, String?>? {
        return getBrowseRowsTV(BrowseApiHelper::getNewsQuery, MediaGroup.TYPE_NEWS)
    }

    fun getGaming(): Pair<List<MediaGroup?>?, String?>? {
        return getBrowseRowsTV(BrowseApiHelper::getGamingQuery, MediaGroup.TYPE_GAMING)
    }

    fun getHistory(): MediaGroup? {
        return getBrowseGridTV(BrowseApiHelper::getMyHistoryQuery, MediaGroup.TYPE_HISTORY)
    }

    private fun getLikedMusicWeb(): MediaGroup? {
        val options = MediaGroupOptions.create(MediaGroup.TYPE_MUSIC)
        val result = mBrowseApi.getBrowseResult(BrowseApiHelper.getLikedMusicQuery(AppClient.WEB))

        return RetrofitHelper.get(result)?.let { BrowseMediaGroup(it, options) }
    }

    private fun getLikedMusicTV(): MediaGroup? {
        val options = MediaGroupOptions.create(MediaGroup.TYPE_MUSIC)
        val result = mBrowseApi.getContinuationResultTV(BrowseApiHelper.getLikedMusicContinuation(options.clientTV))

        return RetrofitHelper.get(result)?.let { WatchNexContinuationMediaGroup(it, options) }
    }

    fun getNewMusicAlbums(): MediaGroup? {
        val result = mBrowseApi.getBrowseResult(BrowseApiHelper.getNewMusicAlbumsQuery())

        return RetrofitHelper.get(result, false)?.let { BrowseMediaGroup(it, MediaGroupOptions.create(MediaGroup.TYPE_MUSIC)) }
    }

    fun getNewMusicVideos(): MediaGroup? {
        val result = mBrowseApi.getBrowseResult(BrowseApiHelper.getNewMusicVideosQuery())

        return RetrofitHelper.get(result, false)?.let { BrowseMediaGroup(it, MediaGroupOptions.create(MediaGroup.TYPE_MUSIC)) }
    }

    open fun getMyPlaylists(): MediaGroup? {
        val options = MediaGroupOptions.create(MediaGroup.TYPE_USER_PLAYLISTS)
        val result = mBrowseApi.getBrowseResultTV(BrowseApiHelper.getMyPlaylistQuery(options.clientTV))

        return RetrofitHelper.get(result)?.let {
            BrowseMediaGroupTV(it, options)
        }

        //return RetrofitHelper.get(result)?.let {
        //    if (it.getItems()?.firstOrNull { it?.getPlaylistId().equals(BrowseApiHelper.WATCH_LATER_PLAYLIST) } != null) {
        //        BrowseMediaGroupTV(it, options)
        //    } else { // No Watch Later (moved to the dedicated subsection)
        //        val library = mBrowseApi.getBrowseResultTV(BrowseApiHelper.getMyLibraryQuery(options.clientTV))
        //
        //        val outer = it
        //
        //        RetrofitHelper.get(library)?.let {
        //            val watchLater = it.getItems()?.getOrNull(1) // Watch Later subsection
        //            BrowseMediaGroupTV(outer, options, watchLater?.let { outer.getItems()?.toMutableList()?.apply { add(0, it) } })
        //        }
        //    }
        //}
    }

    private fun continueShortsWeb(continuationKey: String?, auth: Boolean = false): MediaGroup? {
        if (continuationKey == null) {
            return null
        }

        val continuation = mBrowseApi?.getReelContinuationResult(BrowseApiHelper.getReelContinuationQuery(AppClient.WEB, continuationKey))

        return RetrofitHelper.get(continuation, auth)?.let {
            val result = mutableListOf<MediaItem?>()

            it.getItems()?.forEach {
                if (it?.videoId != null && it.params != null) {
                    val details = mBrowseApi?.getReelResult(BrowseApiHelper.getReelDetailsQuery(AppClient.WEB, it.videoId, it.params))

                    RetrofitHelper.get(details, auth)?.let {
                            info -> result.add(ShortsMediaItem(it, info))
                    }
                }
            }

            ShortsMediaGroup(result, it.getContinuationToken(), MediaGroupOptions.create(MediaGroup.TYPE_SHORTS))
        }
    }

    open fun getChannelAsGrid(channelId: String?): MediaGroup? {
        return getChannelVideosTV(channelId) ?: getChannelVideosWeb(channelId)
    }

    /**
     * Load a playlist URL that contains only a list id (no selected video). Watch-next metadata
     * cannot serve this shape because it requires a video id, so browse the playlist's VL page
     * directly instead. Prefer the TV playlist renderer because it preserves each video's
     * playlist navigation data and exposes the continuation token needed by the shared
     * channel-uploads screen.
     */
    fun getPlaylist(playlistId: String?, playlistTitle: String?): MediaGroup? {
        if (playlistId == null) {
            return null
        }

        val browseId = playlistId.takeIf { it.startsWith("VL") } ?: "VL$playlistId"
        val options = MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS)

        val tvResult = mBrowseApi.getBrowseResultTV(BrowseApiHelper.getChannelQuery(options.clientTV, browseId))
        RetrofitHelper.get(tvResult)?.let {
            BrowseMediaGroupTV(it, options).apply { title = it.getTitle() ?: playlistTitle }
        }?.takeIf { !it.isEmpty }?.let { return it }

        val webResult = mBrowseApi.getBrowseResult(BrowseApiHelper.getChannelQuery(AppClient.WEB, browseId))
        return RetrofitHelper.get(webResult)?.let {
            BrowseMediaGroup(it, options).apply { if (title.isNullOrEmpty()) title = playlistTitle }
        }?.takeIf { !it.isEmpty }
    }

    private fun getChannelVideosTV(channelId: String?): MediaGroup? {
        if (channelId == null) {
            return null
        }

        val rows = getBrowseRowsTV({ BrowseApiHelper.getChannelVideosQuery(it, channelId) }, MediaGroup.TYPE_CHANNEL_UPLOADS)?.first
        val firstRow = rows?.firstOrNull()
        return firstRow?.let { if (it.nextPageKey != null) it else
            MergedMediaGroup(MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS), rows.getOrNull(1), it)
        }
    }

    private fun getChannelVideosWeb(channelId: String?, auth: Boolean = false): MediaGroup? {
        if (channelId == null) {
            return null
        }

        val options = MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS)
        val videos = mBrowseApi.getBrowseResult(BrowseApiHelper.getChannelVideosQuery(AppClient.WEB, channelId))
        val live = mBrowseApi.getBrowseResult(BrowseApiHelper.getChannelLiveQuery(AppClient.WEB, channelId))

        RetrofitHelper.get(videos, auth)?.let { return BrowseMediaGroup(it, options, RetrofitHelper.get(live)) }

        RetrofitHelper.get(live, auth)?.let { return LiveMediaGroup(it, options) }

        return null
    }

    fun getChannelAsGridOld(channelId: String?): MediaGroup? {
        if (channelId == null) {
            return null
        }

        val videos = mBrowseApi.getBrowseResult(BrowseApiHelper.getChannelVideosQuery(AppClient.WEB, channelId))

        return RetrofitHelper.get(videos)?.let { BrowseMediaGroup(it, MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS)) }
    }

    fun getChannelLive(channelId: String?): MediaGroup? {
        if (channelId == null) {
            return null
        }

        val live = mBrowseApi.getBrowseResult(BrowseApiHelper.getChannelLiveQuery(AppClient.WEB, channelId))

        return RetrofitHelper.get(live)?.let { LiveMediaGroup(it, MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS)) }
    }

    fun getChannelSearch(channelId: String?, query: String?): MediaGroup? {
        return getChannelSearchWeb(channelId, query)
    }

    private fun getChannelSearchWeb(channelId: String?, query: String?, auth: Boolean = false): MediaGroup? {
        if (channelId == null || query == null) {
            return null
        }

        val options = MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS)
        val search = mBrowseApi.getBrowseResult(BrowseApiHelper.getChannelSearchQuery(AppClient.WEB, channelId, query))

        return RetrofitHelper.get(search, auth)?.let { BrowseMediaGroup(it, options) }
    }

    /**
     * Latest, Popular, Oldest
     */
    fun getChannelSortingOptions(channelId: String?): List<MediaGroup?>? {
        return getChannelSortingOptionsWeb(channelId)
    }

    /**
     * Latest, Popular, Oldest
     */
    private fun getChannelSortingOptionsWeb(channelId: String?, auth: Boolean = false): List<MediaGroup?>? {
        if (channelId == null) {
            return null
        }

        val options = MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS)
        val videos = mBrowseApi.getBrowseResult(BrowseApiHelper.getChannelVideosQuery(AppClient.WEB, channelId))

        return RetrofitHelper.get(videos, auth)?.let {
            it.getChips()?.mapNotNull { if (it != null) ChipMediaGroup(it, options) else null }
        }
    }

    open fun getChannel(channelId: String?, params: String?): Pair<List<MediaGroup?>?, String?>? {
        return getChannelTV(channelId, params) ?: getChannelWeb(channelId)?.let { Pair(it, null) }
    }

    private fun getChannelWeb(channelId: String?, auth: Boolean = false): List<MediaGroup?>? {
        if (channelId == null) {
            return null
        }

        val channelOptions = MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL, channelId)
        val uploadOptions = MediaGroupOptions.create(MediaGroup.TYPE_CHANNEL_UPLOADS, channelId)
        val result = mutableListOf<MediaGroup>()

        val homeResult = getBrowseRedirect(channelId) {
            val home = mBrowseApi.getBrowseResult(BrowseApiHelper.getChannelHomeQuery(AppClient.WEB, it))
            RetrofitHelper.get(home, auth)
        }

        var shortTab: MediaGroup? = null

        homeResult?.let { it.getTabs()?.drop(1)?.forEach { // skip first tab - Home (repeats Videos)
            if (it?.title?.contains("Shorts") == true) { // move Shorts tab lower
                shortTab = TabMediaGroup(it, channelOptions)
                return@forEach
            }
            val title = it?.getTitle()
            if (title != null && result.firstOrNull { it.title == title } == null) // only unique rows
                result.add(TabMediaGroup(it, channelOptions)) } }

        shortTab?.let { result.add(it) } // move Shorts tab lower

        homeResult?.let { it.getNestedShelves()?.forEach {
            val title = it?.getTitle()
            if (it != null && result.firstOrNull { it.title == title } == null) // only unique rows
                result.add(ItemSectionMediaGroup(it, if (title == null) uploadOptions else channelOptions)) } } // playlists don't have a title

        if (result.isEmpty()) {
            val playlist = mBrowseApi.getBrowseResult(BrowseApiHelper.getChannelQuery(AppClient.WEB, channelId))
            RetrofitHelper.get(playlist, auth)?.let {
                if (it.getTitle() != null) result.add(BrowseMediaGroup(it, uploadOptions))
            }
        }

        //if (result.isEmpty()) {
        //    getChannelResult(AppClient.WEB_REMIX, channelId)?.let {
        //        if (it.getTitle() != null) result.add(BrowseMediaGroup(it, MediaGroupOptions.createOptions(MediaGroup.TYPE_CHANNEL_UPLOADS)))
        //    }
        //}

        return result.ifEmpty { null }
    }

    private fun getChannelTV(channelId: String?, params: String?): Pair<List<MediaGroup?>?, String?>? {
        if (channelId == null) {
            return null
        }

        return getBrowseRowsTV({ BrowseApiHelper.getChannelQuery(it, channelId, params) }, MediaGroup.TYPE_CHANNEL, MediaGroup.TYPE_CHANNEL_UPLOADS)
    }

    /**
     * A special type of a channel that could be found inside Music section (see Liked row More button)
     */
    fun getGridChannel(channelId: String, params: String? = null): MediaGroup? {
        return getBrowseGridTV({ BrowseApiHelper.getChannelQuery(it, channelId, params) }, MediaGroup.TYPE_CHANNEL_UPLOADS)
    }

    open fun getGroup(reloadPageKey: String, type: Int, title: String?): MediaGroup? {
        return continueGroupTV(EmptyMediaGroup(reloadPageKey, type, title), true)
    }

    fun continueGroup(group: MediaGroup?): MediaGroup? {
        return when (group) {
            is ShortsMediaGroup -> continueShortsWeb(group.nextPageKey)
            is ShelfSectionMediaGroup -> continueGroupTV(group)
            is BrowseMediaGroupTV, is MergedMediaGroup -> continueGroupTV(group)
            is WatchNexContinuationMediaGroup -> continueGroupTV(group)
            else -> continueGroupWeb(group)?.firstOrNull()
        }
    }

    fun continueEmptyGroup(group: MediaGroup?): List<MediaGroup?>? {
        if (group?.nextPageKey != null) {
            return continueGroupTV(group)?.let { listOf(it) } ?: continueGroupWeb(group)
        } else if (group?.channelId != null) {
            return continueTabWeb(group)?.let { listOf(it) }
        }

        return null
    }

    fun continueSectionList(nextPageKey: String?, groupType: Int): Pair<List<MediaGroup?>?, String?>? {
        return continueSectionListTV(nextPageKey, groupType)
    }

    private fun continueSectionListTV(nextPageKey: String?, groupType: Int): Pair<List<MediaGroup?>?, String?>? {
        if (nextPageKey == null) {
            return null
        }

        val options = MediaGroupOptions.create(groupType)
        val continuationResult =
            mBrowseApi.getContinuationResultTV(BrowseApiHelper.getContinuationQuery(options.clientTV, nextPageKey))

        return RetrofitHelper.get(continuationResult)?.let {
            val result = mutableListOf<MediaGroup?>()
            it.getShelves()?.forEach { if (it != null) addOrMerge(result, ShelfSectionMediaGroup(it, options)) }
            Pair(result, it.getContinuationToken())
        }
    }

    private fun continueTabWeb(group: MediaGroup?, auth: Boolean = false): MediaGroup? {
        if (group?.channelId == null) {
            return null
        }

        val options = MediaGroupOptions.create(group.type)
        val browseResult =
            mBrowseApi.getBrowseResult(BrowseApiHelper.getChannelQuery(AppClient.WEB, group.channelId, group.params))

        return RetrofitHelper.get(browseResult, auth)?.let { BrowseMediaGroup(it, options).apply { title = group.title } }
    }

    /**
     * NOTE: Can continue Chip or Group
     */
    private fun continueGroupWeb(group: MediaGroup?, auth: Boolean = false): List<MediaGroup?>? {
        if (group?.nextPageKey == null) {
            return null
        }

        val options = MediaGroupOptions.create(group.type)
        val continuationResult =
            mBrowseApi.getContinuationResult(BrowseApiHelper.getContinuationQuery(AppClient.WEB, group.nextPageKey))

        return RetrofitHelper.get(continuationResult, auth)?.let {
            val result = mutableListOf<MediaGroup?>()

            result.add(ContinuationMediaGroup(it, options).apply { title = group.title })
            it.getSections()?.forEach { if (it != null) result.add(RichSectionMediaGroup(it, options)) }

            result
        }
    }

    private fun continueGroupTV(group: MediaGroup?, continueIfNeeded: Boolean = false): MediaGroup? {
        if (group?.nextPageKey == null) {
            return null
        }

        val options = MediaGroupOptions.create(group.type)
        val continuationResult =
            mBrowseApi.getContinuationResultTV(BrowseApiHelper.getContinuationQuery(options.clientTV, group.nextPageKey))

        return RetrofitHelper.get(continuationResult)?.let {
            // Prepare to move LIVE items to the top. Multiple results should be combined first.
            val (overrideItems, overrideKey) = if (continueIfNeeded) continueIfNeededTV(it.getItems(), it.getContinuationToken(), options) else Pair(null, null)

            WatchNexContinuationMediaGroup(it, options, overrideItems = overrideItems, overrideKey = overrideKey).apply { title = group.title }
        }
    }

    private fun getBrowseRowsWeb(query: String, sectionType: Int, auth: Boolean = false): List<MediaGroup?>? {
        val options = MediaGroupOptions.create(sectionType)
        val browseResult = mBrowseApi.getBrowseResult(query)

        return RetrofitHelper.get(browseResult, auth)?.let {
            val result = mutableListOf<MediaGroup?>()

            // First chip is always empty and corresponds to current result.
            // Also title used as id in continuation. No good.
            // NOTE: First tab on home page has no title.
            result.add(BrowseMediaGroup(it, MediaGroupOptions.create(sectionType))) // always renders first tab
            it.getTabs()?.drop(1)?.forEach { if (it?.getTitle() != null) result.add(TabMediaGroup(it, options)) }
            it.getSections()?.forEach { if (it?.getTitle() != null) addOrMerge(result, RichSectionMediaGroup(it, options)) }
            it.getChips()?.forEach { if (it?.getTitle() != null) result.add(ChipMediaGroup(it, options)) }

            result
        }
    }

    private fun getBrowseRowsTV(query: (AppClient) -> String, sectionType: Int, gridType: Int = MediaGroup.TYPE_UNDEFINED): Pair<List<MediaGroup?>?, String?>? {
        val rowsOptions = MediaGroupOptions.create(sectionType)
        val gridOptions = MediaGroupOptions.create(gridType)
        val browseResult = mBrowseApi.getBrowseResultTV(query(rowsOptions.clientTV))

        return RetrofitHelper.get(browseResult)?.let {
            val result = mutableListOf<MediaGroup?>()
            it.getShelves()?.forEach { if (it != null) addOrMerge(result, ShelfSectionMediaGroup(it, rowsOptions)) }

            if (result.isEmpty()) // playlist
                addOrMerge(result, BrowseMediaGroupTV(it, gridOptions))

            Pair(result, it.getContinuationToken())
        }
    }

    private fun getBrowseGridTV(query: (AppClient) -> String, sectionType: Int, shouldContinue: Boolean = false): MediaGroup? {
        val options = MediaGroupOptions.create(sectionType)
        val browseResult = mBrowseApi.getBrowseResultTV(query(options.clientTV))

        return RetrofitHelper.get(browseResult)?.let {
            // Prepare to move LIVE items to the top. Multiple results should be combined first.
            var continuation: Pair<List<ItemWrapper?>?, String?>? = null
            if (shouldContinue) {
                continuation = continueIfNeededTV(it.getItems(), it.getContinuationToken(), options)
            }

            BrowseMediaGroupTV(it, options, overrideItems = continuation?.first, overrideKey = continuation?.second)
        }
    }

    private fun addOrMerge(result: MutableList<MediaGroup?>, group: MediaGroup) {
        // Always add, merging will be done later
        result.add(group)
    }

    private fun getRecommendedWeb(): List<MediaGroup?>? {
        val options = MediaGroupOptions.create(MediaGroup.TYPE_HOME)
        val guideResult = mBrowseApi.getGuideResult(PostDataHelper.createQueryWeb(""))

        return RetrofitHelper.get(guideResult)?.let {
            val result = mutableListOf<MediaGroup?>()

            it.getRecommended()?.forEach { if (it != null) result.add(RecommendedMediaGroup(it, options)) }

            result
        }
    }

    private fun getSubscribedShortsWeb(): List<MediaItem?>? {
        val browseResult = mBrowseApi.getBrowseResult(BrowseApiHelper.getSubscriptionsQuery(AppClient.WEB))

        return RetrofitHelper.get(browseResult)?.let { it.getShortItems()?.let { SubscribedShortsMediaGroup(it) } }?.mediaItems
    }

    private fun getBrowseRedirect(browseId: String, browseExpression: (String) -> BrowseResult?): BrowseResult? {
        val result = browseExpression(browseId)
        return if (result?.getRedirectBrowseId() != null) browseExpression(result.getRedirectBrowseId()!!) else result
    }

    private fun continueIfNeededTV(items: List<ItemWrapper?>?, continuationKey: String?, options: MediaGroupOptions): Pair<List<ItemWrapper?>?, String?> {
        var combinedItems: List<ItemWrapper?>? = items
        var combinedKey: String? = continuationKey

        // Phone gate: the serial pre-combine below runs BEFORE the group is emitted, i.e. the
        // user stares at an empty grid for every round trip it makes. Returning the first page
        // as overrideItems keeps the live-first sort semantics (applied per emitted window).
        if (BrowseServiceGates.skipContinuationPreCombine) {
            return Pair(combinedItems, combinedKey)
        }

        for (i in 0 until 10) {
            // NOTE: bigger max value help moving live videos to the top (e.g. sorting)
            if (combinedKey == null || (combinedItems?.size ?: 0) > 60)
                break

            val result =
                mBrowseApi.getContinuationResultTV(BrowseApiHelper.getContinuationQuery(options.clientTV, combinedKey))

            combinedKey = null

            RetrofitHelper.get(result)?.let {
                combinedItems = (combinedItems ?: emptyList()) + (it.getItems() ?: emptyList())
                combinedKey = it.getContinuationToken()
            }
        }

        return Pair(combinedItems, combinedKey)
    }
}

internal typealias HomeRows = Pair<List<MediaGroup?>?, String?>

/**
 * NEWTUBE(home-parallel): signed-out Home. The personalized home query ("probe") is empty for a
 * visitor with no watch history, and the fallback is four TV topic feeds. Two changes, both keeping
 * the old result: feed order and merge are unchanged, and the first feed failure (in feed order)
 * still propagates, exactly as the serial loop did.
 *  - The topic feeds run in parallel (one round trip instead of four).
 *  - An empty probe is remembered for the process (30 min, cleared by any account change or by a
 *    personalized answer). While it holds, a refresh fires the probe TOGETHER with the topic feeds
 *    instead of in front of them, so the moment watch history personalizes the visitor's home the
 *    next refresh still shows it - at the cost of one extra request, never an extra round trip.
 * Signed-in and non-empty homes take the same single request as before.
 */
internal object AnonymousHome : SignInService.OnAccountChange {
    const val VERDICT_TTL_MS = 30 * 60 * 1_000L
    private const val NO_VERDICT = -1L

    @Volatile
    private var emptySinceMs = NO_VERDICT
    @Volatile
    private var watchingAccounts = false
    /** Test seam. */
    @Volatile
    internal var clock: () -> Long = { System.currentTimeMillis() }

    fun resolve(probe: () -> HomeRows?, topicFeeds: List<() -> HomeRows?>): HomeRows? {
        val startMs = clock()
        val verdictSinceMs = emptySinceMs
        val verdictAgeMs = if (verdictSinceMs == NO_VERDICT) -1L else clock() - verdictSinceMs

        if (verdictAgeMs in 0 until VERDICT_TTL_MS) {
            val results = fetchAll(listOf(probe) + topicFeeds)
            val home = results[0].getOrNull()

            if (isPersonalized(home)) {
                clearVerdict()
                logNetPath("home-anon probe=personalized verdict=cleared parallel=${results.size} ms=${clock() - startMs}")
                return home
            }

            if (home != null) {
                markEmpty() // re-confirmed
            }

            val merged = mergeTopicFeeds(results.drop(1))
            logNetPath("home-anon verdict=empty age=${verdictAgeMs / 1_000}s probe+topics parallel=${results.size} " +
                    "probe=${describe(results[0])} rows=${merged.first?.size ?: 0} ms=${clock() - startMs}")
            return merged
        }

        val home = probe()

        if (isPersonalized(home)) {
            if (verdictSinceMs != NO_VERDICT) {
                clearVerdict() // expired verdict, and the visitor has history now
            }
            return home
        }

        if (home != null) {
            markEmpty()
        }

        val merged = mergeTopicFeeds(fetchAll(topicFeeds))
        logNetPath("home-anon probe=${if (home != null) "empty" else "null"} topics parallel=${topicFeeds.size} " +
                "rows=${merged.first?.size ?: 0} ms=${clock() - startMs}")
        return merged
    }

    /** Same merge as the old serial loop: feed order, and the first failure in feed order propagates. */
    internal fun mergeTopicFeeds(results: List<Result<HomeRows?>>): HomeRows {
        val rows = mutableListOf<MediaGroup?>()

        for (result in results) {
            result.getOrThrow()?.first?.let { rows.addAll(it) }
        }

        return Pair(rows.ifEmpty { null }, null)
    }

    /** Runs [tasks] concurrently (blocking calls, IO dispatcher) and returns their outcomes in input order. */
    internal fun <T> fetchAll(tasks: List<() -> T>): List<Result<T>> {
        if (tasks.size <= 1) {
            return tasks.map { runCatching { it() } }
        }

        return runBlocking {
            tasks.map { task -> async(Dispatchers.IO) { runCatching { task() } } }.awaitAll()
        }
    }

    internal fun isPersonalized(home: HomeRows?): Boolean = home?.first?.any { it?.isEmpty == false } == true

    internal fun hasEmptyVerdict(): Boolean = emptySinceMs != NO_VERDICT && clock() - emptySinceMs < VERDICT_TTL_MS

    fun watchAccountChanges() {
        if (!watchingAccounts) {
            watchingAccounts = true
            // The account manager holds listeners weakly; this object is a static singleton.
            YouTubeSignInService.instance().addOnAccountChange(this)
        }
    }

    override fun onAccountChanged(account: Account?) {
        // Sign in/out or switch regenerates the visitor (AppService.invalidateCache): verdict is stale.
        if (emptySinceMs != NO_VERDICT) {
            logNetPath("home-anon verdict=cleared reason=account-change")
        }
        clearVerdict()
    }

    internal fun clearVerdict() {
        emptySinceMs = NO_VERDICT
    }

    private fun markEmpty() {
        emptySinceMs = clock()
    }

    private fun describe(result: Result<HomeRows?>): String {
        return result.exceptionOrNull()?.let { "error:" + it.javaClass.simpleName } ?: if (result.getOrNull() == null) "null" else "empty"
    }

    private fun logNetPath(message: String) {
        android.util.Log.d("NetPath", message)
    }
}
