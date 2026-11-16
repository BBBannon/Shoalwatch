package app.shoalwatch

import app.shoalwatch.data.db.downloads.ReleaseStore
import app.shoalwatch.data.downloads.HousekeepingRepository
import app.shoalwatch.data.downloads.ReleasedFiles

/** Watch-and-delete's wiring (kept out of [AppGraph], which is near its size limit): see "Watch-and-delete" in docs/ARCHITECTURE.md. */
internal class HousekeepingParts(graph: AppGraph) {
    /** The one release rule, under the library's write lock (it changes pins). */
    val releaseStore = ReleaseStore(graph.database, System::currentTimeMillis, graph.libraryWriteLock)

    /** A released copy's files go once the player lets go of them. */
    val releasedFiles = ReleasedFiles(graph.downloadPaths, graph.localPlaying, graph.appScope)

    val repository = HousekeepingRepository(
        releaseStore, graph.database.housekeepingDao(), graph.playstate, releasedFiles,
        onWatched = { _, _ -> }, // auto-download next (Task 13)
        hasToken = { s -> graph.serverStore.cachedToken(s.serverId, s.userId) != null },
        scope = graph.appScope,
    )
}
