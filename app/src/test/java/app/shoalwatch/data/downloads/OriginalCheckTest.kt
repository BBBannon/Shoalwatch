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

    @Test fun aDirectPlayMkvIsPlayableWithItsTracksSizeAndTag() {
        val info = fixtureInfo("direct-play.json")
        val check = OriginalChecks.of(info) as OriginalCheck.Playable
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
        assertEquals(OriginalCheck.NotPlayableHere, OriginalChecks.of(fixtureInfo("direct-stream.json")))
        assertEquals(OriginalCheck.NotPlayableHere, OriginalChecks.of(fixtureInfo("transcode.json")))
    }

    @Test fun aContainerOutsideTheSavedSetIsNotPlayableHere() {
        val avi = raw("direct-play.json").replace("\"Container\": \"mkv\"", "\"Container\": \"avi\"")
        assertEquals(OriginalCheck.NotPlayableHere, OriginalChecks.of(infoOf(avi)))
    }

    @Test fun jellyfinsMp4ContainerListSavesAsMp4() {
        val mp4 = raw("direct-play.json").replace("\"Container\": \"mkv\"", "\"Container\": \"mov,mp4,m4a,3gp,3g2,mj2\"")
        assertEquals("mp4", (OriginalChecks.of(infoOf(mp4)) as OriginalCheck.Playable).container)
    }

    @Test fun noSourceIsNotPlayableHere() {
        assertEquals(OriginalCheck.NotPlayableHere, OriginalChecks.of(infoOf("""{"MediaSources":[],"PlaySessionId":"abc"}""")))
    }

    @Test fun aRefusalIsThrownAsItsAppError() {
        try {
            OriginalChecks.of(fixtureInfo("refused.json"))
            fail("expected a refusal")
        } catch (e: AppError) {
            assertEquals(AppError.Kind.PLAYBACK_NOT_ALLOWED, e.kind)
        }
    }

    @Test fun aSizeThatIsNotAWholeNumberInRangeIsUnknownAndTheSourceSurvives() {
        for (bad in listOf("-1", "1.5", "\"12\"", "274877906945")) {
            val json = raw("direct-play.json").replace("\"Size\": 1234567890", "\"Size\": $bad")
            assertEquals(bad, null, (OriginalChecks.of(infoOf(json)) as OriginalCheck.Playable).sizeBytes)
        }
    }
}
