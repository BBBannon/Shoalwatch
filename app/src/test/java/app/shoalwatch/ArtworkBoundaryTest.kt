package app.shoalwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Nothing outside the artwork code may reach for an image loader or a Coil type: a screen that built its own loader, request or
 * `AsyncImage` would bypass the guarded loader (which refuses every model but an `ArtworkRef`). Allowed: `data/images/`,
 * `ui/components/Artwork*.kt`, and the wiring in `ShoalwatchApp.kt` and `AppGraph.kt`. Failures name the file and line.
 */
class ArtworkBoundaryTest {
    private val banned = listOf("ImageLoader.Builder", "ImageLoader(", "AsyncImage", "SubcomposeAsyncImage", "rememberAsyncImagePainter", "ImageRequest", "import coil3.")

    private fun sources() = listOf("src/main/java", "src/main/kotlin").map(::File).filter { it.isDirectory }
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private fun allowed(path: String): Boolean {
        val p = path.replace('\\', '/')
        return "/data/images/" in p || ("/ui/components/" in p && p.substringAfterLast('/').startsWith("Artwork")) ||
            p.endsWith("/ShoalwatchApp.kt") || p.endsWith("/AppGraph.kt")
    }

    /** Every `path:line: text` where a banned word appears in a file that may not use it. */
    private fun violations(files: List<Pair<String, List<String>>>): List<String> = files.filterNot { allowed(it.first) }.flatMap { (path, lines) ->
        lines.withIndex().filter { (_, line) -> banned.any { it in line } }.map { (i, line) -> "$path:${i + 1}: ${line.trim()}" }
    }

    @Test fun noScreenOrDataCodeOutsideTheArtworkFilesTouchesCoil() {
        val files = sources()
        assertTrue("scanned only ${files.size} files", files.size > 20)
        val found = violations(files.map { it.path to it.readLines() })
        assertTrue("Coil or an image loader is used outside the artwork code:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test fun theScannerCatchesEachBannedWordAndNamesTheLine() {
        for (word in banned) {
            val found = violations(listOf("src/main/java/app/shoalwatch/ui/home/Screen.kt" to listOf("package x", "val a = $word", "val b = 1")))
            assertEquals(word, listOf("src/main/java/app/shoalwatch/ui/home/Screen.kt:2: val a = $word"), found)
        }
        assertTrue(violations(listOf("src/main/java/app/shoalwatch/data/images/ArtworkLoader.kt" to listOf("import coil3.ImageLoader"))).isEmpty())
        assertTrue(violations(listOf("src/main/java/app/shoalwatch/ui/components/Artwork.kt" to listOf("AsyncImage("))).isEmpty())
        assertTrue(violations(listOf("src/main/java/app/shoalwatch/ui/components/Buttons.kt" to listOf("AsyncImage("))).isNotEmpty())
    }
}
