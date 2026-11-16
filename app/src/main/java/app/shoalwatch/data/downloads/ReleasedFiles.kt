package app.shoalwatch.data.downloads

import app.shoalwatch.core.AppLog
import app.shoalwatch.data.db.downloads.DownloadKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The files of a released download. They go only once the player lets go of them ([LocalPlaying]): "Watched — delete download"
 * is pressed during the credits, while the engine still reads the file, and a seek back must still work.
 */
internal class ReleasedFiles(
    private val paths: DownloadPaths,
    private val playing: LocalPlaying,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Deletes the item's folder now, or once the player lets go of it. A process that dies first leaves it to the next start's orphan sweep. */
    fun delete(key: DownloadKey, storage: StorageKind, volumeId: String?) {
        playing.whenClosed(key) {
            scope.launch(io) {
                try {
                    paths.itemFolder(key, storage, volumeId)?.let(paths::deleteInside) // null: its card is out (swept when it is back)
                } catch (e: Exception) {
                    AppLog.w(TAG, "released files not deleted", e) // a refused path, or the disk: the start-up sweep tries again
                }
            }
        }
    }

    private companion object { const val TAG = "Housekeeping" }
}
