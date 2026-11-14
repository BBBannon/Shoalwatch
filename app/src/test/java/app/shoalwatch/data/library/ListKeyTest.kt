package app.shoalwatch.data.library

import app.shoalwatch.testId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** List keys are built in one place, never collide, and always fit the 200-character column limit. */
class ListKeyTest {
    private val a = testId("a")
    private val b = testId("b")

    private fun allKeys(): List<String> {
        val shapes = listOf(
            ListShape.DIRECT, ListShape.forLibrary(LibraryKind.MOVIES), ListShape.forLibrary(LibraryKind.TV), ListShape.forLibrary(LibraryKind.BOX_SETS),
            ListShape(ItemKind.entries.toSet(), true), ListShape(setOf(ItemKind.MOVIE), false), ListShape(setOf(ItemKind.MOVIE, ItemKind.SERIES), true),
        )
        val sorts = SortBy.entries.flatMap { by -> listOf(true, false).map { Sort(by, it) } }
        val filters = listOf(false, true).flatMap { u -> listOf(false, true).map { f -> ItemFilter(u, f) } }
        val children = listOf(a, b).flatMap { p -> shapes.flatMap { sh -> sorts.flatMap { so -> filters.map { fi -> ListKey.children(p, sh, so, fi) } } } }
        val other = listOf(
            ListKey.RESUME, ListKey.NEXT_UP, ListKey.VIEWS, ListKey.latest(a), ListKey.latest(b), ListKey.seasons(a), ListKey.seasons(b),
            ListKey.episodes(a, null), ListKey.episodes(a, a), ListKey.episodes(a, b), ListKey.episodes(b, null), ListKey.item(a), ListKey.item(b),
        )
        return children + other
    }

    @Test fun everyDistinctInputBuildsADistinctKey() {
        val keys = allKeys()
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(2 * 7 * 8 * 4 + 13, keys.size)
    }

    @Test fun everyKeyFitsTheColumnLimit() {
        assertTrue(allKeys().all { it.length in 1..200 })
        val longest = allKeys().maxOf { it.length }
        assertTrue("longest is $longest", longest <= 200)
    }

    @Test fun keysUseOnlySafeCharacters() {
        assertTrue(allKeys().all { key -> key.all { it.isLetterOrDigit() || it == ':' || it == '_' } })
    }

    @Test fun theDocumentedShapes() {
        assertEquals("latest:$a", ListKey.latest(a))
        assertEquals("seasons:$a", ListKey.seasons(a))
        assertEquals("episodes:$a:all", ListKey.episodes(a, null))
        assertEquals("episodes:$a:$b", ListKey.episodes(a, b))
        assertEquals("children:$a:movie_r:name_asc:all", ListKey.children(a, ListShape.forLibrary(LibraryKind.MOVIES), Sort(SortBy.NAME, false), ItemFilter()))
        assertEquals("resume", ListKey.RESUME)
        assertEquals("nextUp", ListKey.NEXT_UP)
    }

    @Test fun anIdIsNormalisedBeforeItGoesIntoAKey() {
        val dashed = "${a.substring(0, 8)}-${a.substring(8, 12)}-${a.substring(12, 16)}-${a.substring(16, 20)}-${a.substring(20)}".uppercase()
        assertEquals(ListKey.latest(a), ListKey.latest(dashed))
    }

    @Test fun anIdThatIsNotAGuidIsRefused() {
        try {
            ListKey.latest("not:an:id")
            fail("expected a refusal")
        } catch (_: IllegalArgumentException) {
            // refused, and nothing was built
        }
    }
}
