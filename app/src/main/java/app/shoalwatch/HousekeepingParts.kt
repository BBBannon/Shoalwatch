package app.shoalwatch

import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.db.downloads.ReleaseStore
import app.shoalwatch.data.downloads.AutoDownloadNext
import app.shoalwatch.data.downloads.HousekeepingRepository
import app.shoalwatch.data.downloads.ReleasedFiles
import app.shoalwatch.data.downloads.SharerLocks
import app.shoalwatch.data.playback.QualityDefault
import app.shoalwatch.data.sync.OnlineEvents
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Watch-and-delete's wiring (kept out of [AppGraph], which is near its size limit): see "Watch-and-delete" in docs/ARCHITECTURE.md. */
internal class HousekeepingParts(graph: AppGraph) {
    /** The one release rule, under the library's write lock (it changes pins). */
    val releaseStore = ReleaseStore(graph.database, System::currentTimeMillis, graph.libraryWriteLock)

    /** A released copy's files go once the player lets go of them. */
    val releasedFiles = ReleasedFiles(graph.downloadPaths, graph.localPlaying, graph.appScope)

    /** Auto-download next: run at start, after each watch, and whenever a server with a waiting request comes online. */
    val autoDownloadNext = AutoDownloadNext(
        graph.database.housekeepingDao(), graph.downloads, graph.downloadPrefs, { QualityDefault.activeNetworkMetered(graph.appContext) },
        OnlineEvents(graph.addressSelector, graph.database.housekeepingDao().observeAutoNextServers().map { it.toSet() }, graph.appScope),
        graph.appScope,
    )

    val repository = HousekeepingRepository(
        releaseStore, graph.database.housekeepingDao(), graph.playstate, releasedFiles,
        onWatched = autoDownloadNext::onWatched,
        hasToken = { s -> graph.serverStore.cachedToken(s.serverId, s.userId) != null },
        scope = graph.appScope,
        beforeRelease = autoDownloadNext::remember,
        volumes = graph.downloadVolumes,
        remove = { s, id -> graph.downloads.remove(s, id) },
        inPlayer = { s, id -> graph.localPlaying.watchedWhileOpen(s.userId, DownloadKey(s.serverId, id)) },
        markHere = { s, id ->
            graph.offlinePlays.watched(s, id) // first: a process that dies now still sends watched at the next start
            graph.localPlaystate.markWatchedHere(s, id)
        },
        household = graph.lockParts.householdGuard,
        // "Watched together" marks a protected profile only with its own PIN (security review M8 F12).
        sharerLocks = SharerLocks(
            isProtected = { graph.lockParts.profileLocks.isProtected(it) },
            check = { s, pin -> graph.lockParts.profileLocks.check(s, pin) },
            name = { s -> graph.serverStore.accounts(s.serverId).first().firstOrNull { it.userId == s.userId }?.userName },
        ),
    )
}
