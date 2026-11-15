package app.shoalwatch.data.downloads

import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.testId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** File names only from validated ids and fixed words, always inside a root, and deletion never follows a link. */
class DownloadPathsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fake by lazy { FakeVolumes(tmp.root).card("ABCD-1234", tmp.newFolder("card")) }
    private val paths by lazy { DownloadPaths(fake.volumes) }
    private val server = testId("server")
    private val item = testId("item")
    private val key get() = DownloadKey(server, item)
    private val root get() = fake.volumes.root(StorageKind.INTERNAL, null)!!
    private val folder get() = paths.itemFolder(key, StorageKind.INTERNAL, null)!!.also { it.mkdirs() }

    private fun refusal(block: () -> Any?): String? = try { block(); null } catch (e: IllegalArgumentException) { e.message }

    /** Every way an id must not be accepted. */
    private val badIds = listOf(
        "..", "../" + item.drop(3), item.dropLast(1) + "/", item.dropLast(1) + "\\", "%2e%2e" + item.drop(6), item.dropLast(1) + "\u0000",
        item.uppercase(), item.dropLast(1), item.substring(0, 8) + "-" + item.substring(8, 12) + "-" + item.substring(12, 16) + "-" + item.substring(16, 20) + "-" + item.substring(20),
        "", " $item",
    )

    @Test fun theLayoutIsExactlyTheFixedWordsAndIds() {
        val f = folder
        assertEquals(File(File(root, server), item).canonicalPath, f.canonicalPath)
        assertEquals("media.mkv", paths.mediaFile(f, "mkv").name)
        assertEquals("media.mp4.part", paths.partFile(f, "mp4").name)
        assertEquals(File(f, "hls").canonicalPath, paths.hlsFolder(f).canonicalPath)
        val hls = paths.hlsFolder(f).also { it.mkdirs() }
        assertEquals(listOf("seg-00000.ts", "seg-19999.ts"), listOf(paths.segmentFile(hls, 0).name, paths.segmentFile(hls, 19_999).name))
        assertEquals("local.m3u8", paths.localPlaylist(hls).name)
        assertEquals(listOf("sub-0.srt", "sub-10000.ass"), listOf(paths.subtitleFile(f, 0, "srt").name, paths.subtitleFile(f, 10_000, "ass").name))
        val art = paths.artworkFile(server, item, ArtworkSlot.BACKDROP, "Ab12")
        assertEquals(File(File(File(root, server), "art"), "$item-BACKDROP-Ab12.img").canonicalPath, art.canonicalPath)
        assertEquals(File(File(fake.volumes.root(StorageKind.REMOVABLE, "ABCD-1234"), server), item).canonicalPath,
            paths.itemFolder(key, StorageKind.REMOVABLE, "ABCD-1234")!!.canonicalPath)
        assertEquals(2, paths.serverFolders(server).size)
    }

    @Test fun everyBuilderRefusesABadIdWithItsFixedMessage() {
        for (bad in badIds) {
            val shown = bad.replace("\u0000", "\\0")
            assertEquals(shown, "Not a normalised id.", refusal { paths.itemFolder(DownloadKey(bad, item), StorageKind.INTERNAL, null) })
            assertEquals(shown, "Not a normalised id.", refusal { paths.itemFolder(DownloadKey(server, bad), StorageKind.INTERNAL, null) })
            assertEquals(shown, "Not a normalised id.", refusal { paths.artworkFile(bad, item, ArtworkSlot.PRIMARY, "tag") })
            assertEquals(shown, "Not a normalised id.", refusal { paths.artworkFile(server, bad, ArtworkSlot.PRIMARY, "tag") })
            assertEquals(shown, "Not a normalised id.", refusal { paths.serverFolders(bad) })
        }
    }

    @Test fun aRefusalNeverEchoesTheInput() {
        val message = refusal { paths.itemFolder(DownloadKey(server, "secret-title-$item"), StorageKind.INTERNAL, null) }!!
        assertFalse(message.contains("secret"))
    }

    @Test fun tagsIndicesAndExtensionsAreChecked() {
        val f = folder
        val hls = paths.hlsFolder(f).also { it.mkdirs() }
        for (tag in listOf("a-b", "a.b", "", "x".repeat(65), "../x", "a/b", "a\u0000")) {
            assertEquals(tag, "Not a valid image tag.", refusal { paths.artworkFile(server, item, ArtworkSlot.THUMB, tag) })
        }
        assertEquals("Stream index out of range.", refusal { paths.subtitleFile(f, -1, "srt") })
        assertEquals("Stream index out of range.", refusal { paths.subtitleFile(f, 10_001, "srt") })
        assertEquals("Segment index out of range.", refusal { paths.segmentFile(hls, -1) })
        assertEquals("Segment index out of range.", refusal { paths.segmentFile(hls, 20_000) })
        for (ext in listOf("exe", "MP4", "mp4/../x", "", "m3u8", "part")) {
            assertEquals(ext, "Not an allowed extension.", refusal { paths.mediaFile(f, ext) })
            assertEquals(ext, "Not an allowed extension.", refusal { paths.partFile(f, ext) })
        }
        for (format in listOf("txt", "SRT", "srt/..", "")) assertEquals(format, "Not an allowed subtitle format.", refusal { paths.subtitleFile(f, 2, format) })
    }

    @Test fun aFolderBuilderRefusesAFolderThatIsNotADownloadsFolder() {
        val outside = tmp.newFolder("elsewhere")
        assertEquals("Path is outside the downloads folder.", refusal { paths.mediaFile(outside, "mp4") })
        assertEquals("Not a download folder.", refusal { paths.mediaFile(File(root, server).also { it.mkdirs() }, "mp4") })
        assertEquals("Not a download folder.", refusal { paths.segmentFile(folder, 0) })
        assertEquals("Not a download folder.", refusal { paths.hlsFolder(File(folder, "hls")) })
    }

    @Test fun anUnmountedCardHasNoFolder() {
        fake.unmount("ABCD-1234")
        assertNull(paths.itemFolder(key, StorageKind.REMOVABLE, "ABCD-1234"))
        assertNull(paths.itemFolder(key, StorageKind.REMOVABLE, "OTHER"))
    }

    @Test fun deleteInsideRemovesAFolderAndRefusesEverythingOutsideTheRoots() {
        val f = folder
        paths.mediaFile(f, "mp4").writeText("x")
        val outside = tmp.newFile("precious.txt").also { it.writeText("keep") }
        assertFalse(paths.deleteInside(outside))
        assertFalse(paths.deleteInside(tmp.root))
        assertFalse("never a root itself", paths.deleteInside(root))
        assertFalse(paths.deleteInside(File(root, "../../precious.txt")))
        assertTrue(outside.exists() && root.exists())
        assertTrue(paths.deleteInside(f))
        assertFalse(f.exists())
    }

    @Test fun aLinkPlantedInAnItemFolderIsRemovedAsALinkAndItsTargetSurvives() {
        val f = folder
        val target = tmp.newFolder("outside").also { File(it, "keep.txt").writeText("keep") }
        assumeTrue("this machine cannot make a link", link(File(f, "evil"), target))
        assertTrue(paths.deleteInside(f))
        assertFalse(f.exists())
        assertTrue("the link's target is untouched", File(target, "keep.txt").exists())
    }

    @Test fun aBuilderRefusesAPathThatALinkWouldTakeOutsideTheRoot() {
        val target = tmp.newFolder("away")
        val serverFolder = File(root, server)
        assumeTrue("this machine cannot make a link", link(serverFolder, target))
        assertEquals("Path is outside the downloads folder.", refusal { paths.itemFolder(key, StorageKind.INTERNAL, null) })
    }

    companion object {
        /** A symbolic link, or on Windows without the privilege for one, a directory junction (it behaves the same for these tests). */
        fun link(at: File, target: File): Boolean = try {
            Files.createSymbolicLink(at.toPath(), target.toPath())
            true
        } catch (e: Exception) {
            System.getProperty("os.name").startsWith("Windows") && target.isDirectory &&
                ProcessBuilder("cmd", "/c", "mklink", "/J", at.absolutePath, target.absolutePath).redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() == 0 }
        }
    }
}
