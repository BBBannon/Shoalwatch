package app.shoalwatch.ui.present

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.ui.browse.item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ItemTextTest {
    private val strings = Strings(ApplicationProvider.getApplicationContext<Context>().resources)

    private fun season(number: Int?, name: String = "") = item(1, ItemKind.SEASON, name).copy(indexNumber = number)

    @Test fun seasonsAreNumberedSpecialsOrNamed() {
        assertEquals("Season 2", seasonLabel(strings, season(2, "Whatever the server says")))
        assertEquals("Specials", seasonLabel(strings, season(0, "Season 0")))
        assertEquals("Extras", seasonLabel(strings, season(null, "Extras")))
        assertEquals("Season", seasonLabel(strings, season(null, "")))
    }

    @Test fun seriesFactsJoinWhatIsKnown() {
        val series = item(1, ItemKind.SERIES, "Tidal", year = 2019).copy(officialRating = "TV-14")
        assertEquals("2019 · TV-14 · 3 seasons", seriesFacts(strings, series, 3))
        assertEquals("2019 · TV-14 · 1 season", seriesFacts(strings, series, 1))
        assertEquals("2019 · TV-14", seriesFacts(strings, series, 0))
        assertNull(seriesFacts(strings, series.copy(year = null, officialRating = null), 0))
    }

    @Test fun detailFactsHaveYearRuntimeRatingAndTheCommunityScore() {
        val film = item(1, year = 2019, runtimeMs = 102 * 60_000L).copy(officialRating = "PG", communityRating = 7.84f)
        val facts = detailFacts(strings, film)
        assertEquals("2019 · 1 hr 42 min · PG · 7.8", facts.text)
        assertEquals("2019 · 1 hr 42 min · PG · Community rating 7.8 out of 10", facts.spoken)
    }

    @Test fun detailFactsLeaveOutWhatIsMissing() {
        val bare = item(1, year = null, runtimeMs = null)
        assertNull(detailFacts(strings, bare).text)
        assertEquals("2019", detailFacts(strings, item(1, year = 2019, runtimeMs = null)).text)
        assertEquals("2019", detailFacts(strings, item(1, year = 2019, runtimeMs = null).copy(communityRating = Float.NaN)).text)
    }

    @Test fun ratingsUseAPointWhateverTheLanguage() {
        assertEquals("7.8", ratingText(7.8f))
        assertEquals("10.0", ratingText(10f))
    }
}
