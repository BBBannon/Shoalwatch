package app.shoalwatch.data.downloads

import app.shoalwatch.S1
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.testId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A released copy's files go only once the player lets go of them, and never outside the downloads root (virtual time). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReleasedFilesTest {
    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val fake by lazy { FakeVolumes(temp.newFolder()) }
    private val paths by lazy { DownloadPaths(fake.volumes) }
    private val playing = LocalPlaying()
    private val files by lazy { ReleasedFiles(paths, playing, scope, dispatcher) }
    private val key = DownloadKey(S1, testId("e1"))

    private fun saved(k: DownloadKey = key): File = paths.itemFolder(k, StorageKind.INTERNAL, null)!!.also { it.mkdirs(); File(it, "media.mkv").writeText("x") }

    @Test fun aKeyThatIsOpenIsDeletedOnlyWhenItCloses() = scope.runTest {
        val folder = saved()
        playing.opened(key)
        files.delete(key, StorageKind.INTERNAL, null)
        advanceUntilIdle()
        assertTrue(folder.exists())
        playing.closed(key)
        advanceUntilIdle()
        assertFalse(folder.exists())
    }

    @Test fun aKeyThatIsNotOpenIsDeletedAtOnce() = scope.runTest {
        val folder = saved()
        files.delete(key, StorageKind.INTERNAL, null)
        advanceUntilIdle()
        assertFalse(folder.exists())
    }

    @Test fun twoHoldersNeedTwoCloses() = scope.runTest {
        val folder = saved()
        playing.opened(key); playing.opened(key)
        files.delete(key, StorageKind.INTERNAL, null)
        playing.closed(key)
        advanceUntilIdle()
        assertTrue(folder.exists())
        playing.closed(key)
        advanceUntilIdle()
        assertFalse(folder.exists())
    }

    @Test fun aFolderOutsideTheRootIsNeverTouched() = scope.runTest {
        val outside = File(fake.internalBase, "keep").also { it.mkdirs(); File(it, "media.mkv").writeText("x") }
        files.delete(DownloadKey(S1, "../../keep"), StorageKind.INTERNAL, null) // DownloadPaths refuses a name that is not an id
        files.delete(DownloadKey("..", "keep"), StorageKind.INTERNAL, null)
        advanceUntilIdle()
        assertTrue(File(outside, "media.mkv").exists())
    }
}
