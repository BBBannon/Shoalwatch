package app.shoalwatch.data.playback

import app.shoalwatch.data.playback.PlaybackFakes.ITEM
import app.shoalwatch.data.playback.PlaybackFakes.SOURCE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The version chosen for one play: used if the server returns it, else the first. Pure. */
class PlaybackDecisionVersionTest {
    private val other = "fedcba9876543210fedcba9876543210"
    private val base = fixtureInfo("direct-play.json")
    private val two = base.copy(sources = listOf(base.sources.first(), base.sources.first().copy(id = other, runtimeMs = 5_000_000L)))
    private fun request(source: String?) = PlaybackRequest(ITEM, Quality.MBPS_8, mediaSourceId = source)

    @Test fun theChosenVersionIsUsedWhenPresent() {
        val plan = PlaybackDecision.decide(request(other), two)
        assertEquals(other, plan.mediaSourceId); assertEquals(5_000_000L, plan.runtimeMs)
        assertEquals(listOf("static" to "true", "mediaSourceId" to other), plan.source.query.take(2))
    }

    @Test fun theFirstIsUsedWhenTheChosenIsAbsent() =
        assertEquals(SOURCE, PlaybackDecision.decide(request("00000000000000000000000000000000"), two).mediaSourceId)

    @Test fun theFirstIsUsedWhenNoneIsChosen() = assertEquals(SOURCE, PlaybackDecision.decide(request(null), two).mediaSourceId)

    @Test fun anInvalidChoiceIsIgnored() {
        assertEquals(SOURCE, PlaybackDecision.decide(request("../x"), two).mediaSourceId)
        assertEquals(SOURCE, PlaybackDecision.decide(request("abc"), two).mediaSourceId)
    }

    @Test fun onlyThirtyTwoHexCharactersAreAVersionId() {
        assertTrue(MediaPaths.isSourceId(other) && MediaPaths.isSourceId(other.uppercase()))
        assertFalse(MediaPaths.isSourceId("abc") || MediaPaths.isSourceId(null) || MediaPaths.isSourceId(other + "0") || MediaPaths.isSourceId(other.replaceRange(0, 1, "g")))
    }
}
