package app.shoalwatch

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Review M3 (Milestone 7): exactly one `ProfileLocks` is built, in `LockParts`, because it alone owns the mutex that serialises
 * checks and the wait anchors; a second instance would let two checks at once share a free try.
 */
class LockPartsShapeTest {
    @Test fun theAppBuildsExactlyOneProfileLocks() {
        val construction = Regex("""(?<!class )(?<!")\bProfileLocks\(""") // not the declaration, not its toString text
        val sites = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().withIndex().filter { (_, line) -> construction.containsMatchIn(line) }.map { (i, _) -> "${f.name}:${i + 1}" }
        }.toList()
        assertEquals(1, sites.size)
        assertEquals("LockParts.kt", sites.single().substringBefore(':'))
    }
}
