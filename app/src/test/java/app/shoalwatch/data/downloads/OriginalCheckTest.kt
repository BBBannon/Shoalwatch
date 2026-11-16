package app.shoalwatch.data.downloads

import app.shoalwatch.core.AppError
import app.shoalwatch.data.api.JsonBodies
import app.shoalwatch.data.playback.api.PlaybackInfo
import app.shoalwatch.data.playback.api.PlaybackInfoResponseDto
import app.shoalwatch.data.playback.api.toPlaybackInfo
import app.shoalwatch.data.playback.fixtureInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Can the original play here as it is? Milestone 3's PlaybackInfo fixtures through the real mappers. */
class OriginalCheckTest {
    private fun raw(name: String) = javaClass.getResource("/jellyfin/playback/$name")!!.readText()

    private fun infoOf(json: String): PlaybackInfo = JsonBodies.JSON.decodeFromString(PlaybackInfoResponseDto.serializer(), json).toPlaybackInfo()

    /**
     * Tasks 5-8 review I-4: `/Items/{id}/Download` sends the item's own file, whose source id is the item's id; Jellyfin may list
     * a wider alternate version first. That source is the one judged, sized and named; the first is only a fallback.
     */
    @Test fun theSourceWhoseIdIsTheItemsIsChosenOverAWiderOneListedFirst() {
        val one = raw("direct-play.json")
        val info = infoOf(twoSources(one))
        val check = OriginalChecks.of(info, ITEM) as OriginalCheck.Playable
        assertEquals(ITEM, check.sourceId)
        assertEquals(42L, check.sizeBytes)
        assertEquals("mp4", check.container)
        assertEquals("with no source of the item's id, the first", OTHER, (OriginalChecks.of(info, "99999999999999999999999999999999") as OriginalCheck.Playable).sourceId)
        val upper = ITEM.uppercase()
        assertEquals("compared as normalised ids", upper, (OriginalChecks.of(infoOf(twoSources(one, upper)), ITEM) as OriginalCheck.Playable).sourceId)
    }

    /** The fixture's one source (as the wider alternate, [OTHER]) followed by the item's own (id [own], an mp4 of 42 bytes). */
    private fun twoSources(one: String, own: String = ITEM): String {
        val start = one.indexOf('[', one.indexOf("\"MediaSources\"")) + 1
        var depth = 0
        var end = start
        while (true) { val c = one[end]; if (c == '{') depth++; if (c == '}') { depth--; if (depth == 0) break }; end++ }
        val first = one.substring(start, end + 1)
        val fixtureId = "\"Id\": \"0123456789abcdef0123456789abcdef\""
        check(fixtureId in first && "\"Container\": \"mkv\"" in first && "\"Size\": 1234567890" in first) { "the fixture changed" }
        val alternate = first.replace(fixtureId, "\"Id\": \"$OTHER\"")
        val itemOwn = first.replace(fixtureId, "\"Id\": \"$own\"")
            .replace("\"Size\": 1234567890", "\"Size\": 42").replace("\"Container\": \"mkv\"", "\"Container\": \"mov,mp4,m4a\"")
        return one.substring(0, start) + alternate + "," + itemOwn + one.substring(end + 1)
    }

    @Test fun aDirectPlayMkvIsPlayableWithItsTracksSizeAndTag() {
        val info = fixtureInfo("direct-play.json")
        val check = OriginalChecks.of(info, ITEM) as OriginalCheck.Playable
        val source = info.sources.first()
        assertEquals(source.id, check.sourceId)
        assertEquals("mkv", check.container)
        assertEquals(1_234_567_890L, check.sizeBytes)
        assertEquals(source.eTag, check.eTag)
        assertEquals(source.audio, check.audio)
        assertEquals(source.subtitles, check.subtitles)
        assertEquals(source.defaultAudioIndex, check.defaultAudio)
        assertTrue(check.audio.isNotEmpty())
    }

    @Test fun supportsDirectPlayFalseIsNotPlayableHere() {
        assertEquals(OriginalCheck.NotPlayableHere, OriginalChecks.of(fixtureInfo("direct-stream.json"), ITEM))
        assertEquals(OriginalCheck.NotPlayableHere, OriginalChecks.of(fixtureInfo("transcode.json"), ITEM))
    }

    @Test fun aContainerOutsideTheSavedSetIsNotPlayableHere() {
        val avi = raw("direct-play.json").replace("\"Container\": \"mkv\"", "\"Container\": \"avi\"")
        assertEquals(OriginalCheck.NotPlayableHere, OriginalChecks.of(infoOf(avi), ITEM))
    }

    @Test fun jellyfinsMp4ContainerListSavesAsMp4() {
        val mp4 = raw("direct-play.json").replace("\"Container\": \"mkv\"", "\"Container\": \"mov,mp4,m4a,3gp,3g2,mj2\"")
        assertEquals("mp4", (OriginalChecks.of(infoOf(mp4), ITEM) as OriginalCheck.Playable).container)
    }

    @Test fun noSourceIsNotPlayableHere() {
        assertEquals(OriginalCheck.NotPlayableHere, OriginalChecks.of(infoOf("""{"MediaSources":[],"PlaySessionId":"abc"}"""), ITEM))
    }

    @Test fun aRefusalIsThrownAsItsAppError() {
        try {
            OriginalChecks.of(fixtureInfo("refused.json"), ITEM)
            fail("expected a refusal")
        } catch (e: AppError) {
            assertEquals(AppError.Kind.PLAYBACK_NOT_ALLOWED, e.kind)
        }
    }

    @Test fun aSizeThatIsNotAWholeNumberInRangeIsUnknownAndTheSourceSurvives() {
        for (bad in listOf("-1", "1.5", "\"12\"", "274877906945")) {
            val json = raw("direct-play.json").replace("\"Size\": 1234567890", "\"Size\": $bad")
            assertEquals(bad, null, (OriginalChecks.of(infoOf(json), ITEM) as OriginalCheck.Playable).sizeBytes)
        }
    }

    private companion object {
        const val ITEM = "0123456789abcdef0123456789abcdef"
        const val OTHER = "fedcba9876543210fedcba9876543210"
    }
}
