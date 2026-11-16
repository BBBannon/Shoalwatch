package app.shoalwatch.data.downloads

import android.content.Context
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.StorageVolumeBuilder
import java.io.File
import java.io.IOException

/** Which storage is offered, through Robolectric's StorageManager and Environment shadows. */
@RunWith(RobolectricTestRunner::class)
class AndroidVolumesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val storage = context.getSystemService(StorageManager::class.java)
    private val primary by lazy { tmp.newFolder("emulated", "Android", "data", "app") }
    private val cards = mutableListOf<File>()

    @After fun reset() { shadowOf(storage).resetStorageVolumeList() }

    /** An app-specific folder on a card: its volume is registered with [removable] and [uuid], and its state set to [state]. */
    private fun card(name: String, uuid: String?, removable: Boolean = true, state: String = Environment.MEDIA_MOUNTED): File {
        val volumeRoot = tmp.newFolder(name)
        val appDir = File(volumeRoot, "Android/data/app.shoalwatch/files").also { it.mkdirs() }
        val builder = StorageVolumeBuilder(name, volumeRoot, name, Process.myUserHandle(), state).setIsRemovable(removable)
        builder.setFsUuid(uuid) // null: a volume with no uuid (the builder would otherwise make one up)
        shadowOf(storage).addStorageVolume(builder.build())
        ShadowEnvironment.setExternalStorageState(appDir, state)
        cards += appDir
        return appDir
    }

    private val allocations = mutableListOf<Pair<File, Long>>()

    private fun volumes(allocatable: (StorageManager, File) -> Long = { _, dir -> dir.usableSpace }) =
        AndroidVolumes(context, { listOf(primary) + cards }, { Environment.getExternalStorageState(it) }, allocatable) { _, dir, bytes ->
            allocations += dir to bytes
        }

    @Test fun reserveAsksForSpaceOnTheVolumeAndLeavesTheFilesLengthAlone() {
        val root = volumes().root(StorageKind.INTERNAL, null)!!
        val part = File(root, "media.mp4.part").apply { writeBytes(ByteArray(5)) }
        volumes().reserve(part, 1_000_000)
        assertEquals("the part file is appended to, so its length must not change", 5L, part.length())
        assertEquals(listOf(part.parentFile!!.canonicalFile to 1_000_000L), allocations.map { it.first.canonicalFile to it.second })
    }

    @Test fun internalIsAlwaysOfferedFirst() {
        val choices = volumes().choices()
        assertEquals(StorageChoice(StorageKind.INTERNAL, null, choices.first().freeBytes, true), choices.first())
        assertEquals(1, choices.size)
        assertEquals(File(context.filesDir, "downloads").canonicalPath, volumes().root(StorageKind.INTERNAL, null)!!.canonicalPath)
    }

    @Test fun aMountedRemovableVolumeWithAUuidIsOffered() {
        val dir = card("sd", "ABCD-1234")
        val choices = volumes().choices()
        assertEquals(listOf(StorageKind.INTERNAL, StorageKind.REMOVABLE), choices.map { it.kind })
        assertEquals("ABCD-1234", choices[1].volumeId)
        assertEquals(File(dir, "downloads").canonicalPath, volumes().root(StorageKind.REMOVABLE, "ABCD-1234")!!.canonicalPath)
        assertEquals(2, volumes().allRoots().size)
    }

    @Test fun anUnmountedOrUuidlessOrNonRemovableVolumeIsNotOffered() {
        card("out", "OUT-1", state = Environment.MEDIA_UNMOUNTED)
        card("nouuid", null)
        card("fixed", "FIXED-1", removable = false)
        card("odd", "bad/uuid")
        assertEquals(listOf<String?>(null), volumes().choices().map { it.volumeId })
        assertNull(volumes().root(StorageKind.REMOVABLE, "OUT-1"))
        assertNull(volumes().root(StorageKind.REMOVABLE, "FIXED-1"))
        assertEquals(0L, volumes().freeBytes(StorageKind.REMOVABLE, "OUT-1"))
    }

    @Test fun freeBytesFallsBackToUsableSpaceWhenAllocatableBytesThrows() {
        assertEquals(123L, volumes { _, _ -> 123L }.freeBytes(StorageKind.INTERNAL, null))
        // usableSpace moves a little between two reads on a busy disk, so compare within 256 MiB
        for (failure in listOf(IOException("no"), IllegalStateException("no"))) {
            val free = volumes { _, _ -> throw failure }.freeBytes(StorageKind.INTERNAL, null)
            assertTrue("$free", kotlin.math.abs(free - context.filesDir.usableSpace) < 256L shl 20)
        }
    }

    @Test fun eachRootGetsANomediaFile() {
        card("sd", "ABCD-1234")
        val roots = volumes().allRoots()
        assertEquals(2, roots.size)
        for (root in roots) assertTrue(File(root, ".nomedia").isFile)
    }

    @Test fun reserveIsANoOpOutsideInternalStorage() {
        val dir = card("sd", "ABCD-1234")
        val onCard = File(volumes().root(StorageKind.REMOVABLE, "ABCD-1234"), "x.part")
        volumes().reserve(onCard, 10)
        assertTrue("nothing was created on the card", !onCard.exists() && dir.exists())
    }
}
