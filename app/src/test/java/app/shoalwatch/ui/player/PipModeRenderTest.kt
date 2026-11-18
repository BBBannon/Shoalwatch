package app.shoalwatch.ui.player

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.data.playback.SubtitleNotice
import app.shoalwatch.data.playback.UpNextState
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.lock.FakePipHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * In the picture-in-picture window only the picture and its subtitles: no control, no Up next, no watch-and-delete prompt, no
 * snackbar. Full screen, the top bar has the "Picture-in-picture" button; back from the small window the controls show once.
 */
abstract class PipModeRenderBase : RenderBase() {
    private val log = ActionLog(pip = true)
    private val withPip = log.actions
    private val busy = ui(playingEpisode.copy(upNext = UpNextState(id(102), "The Turn", 8, cancelled = false), subtitleNotice = SubtitleNotice.UNREADABLE))
        .copy(watched = WatchedPromptUi(BEN, id(101), film = false, together = 2, always = false), controlsVisible = true)

    @Test fun inTheSmallWindowNothingButThePicture() {
        show { PlayerScreen(busy, withPip, inPip = true) }
        rule.waitForIdle()
        listOf(R.string.cd_back, R.string.player_pause, R.string.player_seek, R.string.player_lock, R.string.player_pip)
            .forEach { assertTrue("control shown: ${text(it)}\n${tree()}", !described(text(it))) }
        listOf(R.string.up_next_play, R.string.watched_keep, R.string.subtitle_unreadable).forEach { assertNotShown(text(it)) }
        assertEquals("no notice is used up while hidden", emptyList<String>(), log.calls.filter { it == "dismissNotice" })
    }

    @Test fun fullScreenTheTopBarHasThePipButtonAt48dp() {
        show { PlayerScreen(ui(), withPip, inPip = false) }
        val node = rule.onNodeWithContentDescription(text(R.string.player_pip)).performScrollTo()
        val size = node.fetchSemanticsNode().size
        val min = with(rule.density) { 48.dp.roundToPx() }
        assertTrue("$size", size.width >= min && size.height >= min)
        node.performClick()
        assertEquals(listOf("pip"), log.calls)
    }

    @Test fun withNoPipActionThereIsNoButton() {
        show { PlayerScreen(ui(), ActionLog().actions) }
        assertTrue(!described(text(R.string.player_pip)))
    }

    /** The wish follows the player (FakePipHost): present while it may enter, withdrawn for a sheet, the screen lock, a failure or the cover, and when it goes. */
    @Test fun theWishIsKeptOnlyWhileThePlayerMayEnter() {
        val host = FakePipHost()
        var state by mutableStateOf(ui())
        var enabled by mutableStateOf(true)
        var shown by mutableStateOf(true)
        var plays = 0
        show {
            CompositionLocalProvider(LocalPipHost provides host) {
                if (shown) PipEffect(state, enabled, onPlayPause = { plays++ }, aspect = 2.0f)
            }
        }
        rule.waitForIdle()
        val first = host.last
        assertTrue("a wish while playing: ${host.wishes}", first != null && first.playing && first.width == 2000 && first.height == 1000)
        host.onPlayPause?.invoke()
        assertEquals(1, plays)
        for ((what, change) in listOf<Pair<String, () -> Unit>>(
            "a sheet" to { state = ui().copy(sheet = PlayerSheet.SPEED) },
            "the screen lock" to { state = ui().copy(locked = true) },
            "a failure" to { state = ui(playingEpisode.copy(phase = Phase.FAILED)) },
            "the lock cover" to { state = ui(); enabled = false },
        )) {
            state = ui(); enabled = true
            rule.waitForIdle()
            assertTrue("back after $what", host.last != null)
            change()
            rule.waitForIdle()
            assertEquals("no wish with $what", null, host.last)
        }
        state = ui(playingEpisode.copy(phase = Phase.PAUSED)); enabled = true
        rule.waitForIdle()
        assertEquals("paused: no auto-enter", false, host.last?.playing)
        shown = false
        rule.waitForIdle()
        assertEquals("withdrawn when the player goes", null, host.last)
    }

    @Test fun backFromTheSmallWindowTheControlsShowOnce() {
        var inPip by mutableStateOf(true)
        show { PlayerScreen(ui().copy(controlsVisible = false), withPip, inPip = inPip) }
        rule.waitForIdle()
        assertEquals(emptyList<String>(), log.calls)
        inPip = false
        rule.waitForIdle()
        assertEquals(listOf("tap"), log.calls)
    }
}

@Config(qualifiers = PLAYER_PHONE)
class PipModeRenderTest : PipModeRenderBase() { override val fontScale = 1.0f }

@Config(qualifiers = PLAYER_PHONE)
class PipModeRenderLargeTextTest : PipModeRenderBase() { override val fontScale = 2.0f }
