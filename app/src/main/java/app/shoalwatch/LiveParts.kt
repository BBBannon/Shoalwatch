package app.shoalwatch

import app.shoalwatch.core.SystemMonotonicClock
import app.shoalwatch.data.discover.Discover
import app.shoalwatch.data.discover.api.DiscoverGateway
import app.shoalwatch.data.lists.Lists
import app.shoalwatch.data.lists.api.ListsGateway
import app.shoalwatch.data.playback.PlayQueues
import app.shoalwatch.data.subtitles.RemoteSubtitles
import app.shoalwatch.data.subtitles.api.SubtitlesGateway
import kotlinx.coroutines.launch

/**
 * Milestone 11's wiring (kept out of [AppGraph], which is near its size limit): playlists and collections, discovery, subtitles
 * from the internet and play queues. All live only, on the one authenticated call path; nothing here is saved.
 */
internal class LiveParts(graph: AppGraph) {
    /** Playlists and collections (data/lists): what the playlist and collection screens call. The one cache is the collection index, in memory. */
    val lists: Lists = ListsGateway(graph.serverApi, SystemMonotonicClock)

    /** Discovery (data/discover), reads only: the detail extras, person, filtered-browse, genre, studio and search-extras screens. Nothing is cached. */
    val discover: Discover = DiscoverGateway(graph.serverApi)

    /** Subtitles from the internet (data/subtitles): what the subtitle search sheet calls, always through the user's own server. */
    val remoteSubtitles: RemoteSubtitles = SubtitlesGateway(graph.serverApi)

    /** Live play queues (a playlist played in order or shuffled), by random token; in memory only. */
    val playQueues = PlayQueues().also { q -> graph.appScope.launch { graph.serverCatalog.activeSession().collect(q::keepOnly) } } // one profile playlist never outlives its session
}
