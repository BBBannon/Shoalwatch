package app.shoalwatch

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two mechanical rules for every Kotlin file under `src/main`: at most 400 lines, and at most 45% blank lines. The second
 * catches the way an edit tool once turned a file into one blank line per code line (doubled line endings) without a diff
 * anyone would read.
 */
class SourceShapeTest {
    private fun sources() = listOf("src/main/java", "src/main/kotlin").map(::File).filter { it.isDirectory }
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    @Test fun noMainFileIsTooLongOrMostlyBlank() {
        val offenders = sources().mapNotNull { f ->
            val lines = f.readLines()
            val blank = lines.count { it.isBlank() }
            when {
                lines.size > 400 -> "${f.path}: ${lines.size} lines (limit 400)"
                lines.isNotEmpty() && blank * 100 > lines.size * 45 -> "${f.path}: $blank of ${lines.size} lines are blank (limit 45%)"
                else -> null
            }
        }
        assertTrue("Files out of shape:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test fun theScannerFindsTheSources() = assertTrue(sources().size > 50)
}
