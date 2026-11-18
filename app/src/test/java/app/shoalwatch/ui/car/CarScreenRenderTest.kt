package app.shoalwatch.ui.car

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.core.AppError
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.playback.PlaybackState
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.browse.item
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

/** The car screen (Milestone 9) at typical car display sizes: the messages, the lists and the player, with big touch targets. */
abstract class CarScreenRenderBase : RenderBase() {
    override val fontScale = 1f
    private val calls = mutableListOf<String>()
    private val actions = CarActions(
        onPlay = { calls += "play:$it" }, onPlayPause = { calls += "playPause" }, onBackTen = { calls += "back10" },
        onForwardThirty = { calls += "forward30" }, onTapPicture = { calls += "tap" }, onBack = { calls += "back" },
    )
    private val episode = ItemWithUserData(item(101, ItemKind.EPISODE, "The Turn", seriesName = "Slow Horses", index = 2, parentIndex = 1), null)
    private val film = ItemWithUserData(item(70, ItemKind.MOVIE, "A Long Film"), null)
    private val open = CarAccess.Open(BEN)
    private val lists = CarLists(resume = listOf(episode), nextUp = emptyList(), downloads = listOf(film))
    private fun playing(phase: Phase = Phase.PLAYING, controls: Boolean = true, error: AppError.Kind? = null) = CarPlayer(
        PlaybackState.EMPTY.copy(session = BEN, itemId = id(101), title = "The Turn", phase = phase, positionMs = 65_000, durationMs = 3_000_000, error = error),
        controlsVisible = controls,
    )

    private fun showCar(access: CarAccess, lists: CarLists? = this.lists, player: CarPlayer? = null) =
        show { CarScreen(access, lists, player, actions) }

    @Test fun lockedOnThePhoneTheCarSaysUnlockThereAndShowsNothingOfTheProfile() {
        showCar(CarAccess.Locked)
        assertShown(text(R.string.car_unlock))
        assertNotShown("The Turn")
        assertNotShown(text(R.string.home_continue))
    }

    @Test fun signedOutTheCarSaysSignInOnThePhone() {
        showCar(CarAccess.SignIn, lists = null)
        assertShown(text(R.string.car_sign_in))
    }

    @Test fun theListsShowTheirHeadingsAndBigRowsThatPlay() {
        showCar(open)
        assertShown(text(R.string.home_continue))
        assertShown(text(R.string.downloads_title))
        assertNotShown(text(R.string.home_next_up)) // an empty section is left out
        assertShown("Slow Horses · S1 E2")
        rule.onNodeWithText("The Turn").assertIsDisplayed()
        rule.onNodeWithText("A Long Film").performClick()
        assertEquals(listOf("play:${id(70)}"), calls)
        assertEquals("Continue Watching and Downloads", 2, headings())
    }

    @Test fun offlineTheListSaysDownloadsStillPlay() {
        showCar(open, lists.copy(resume = emptyList(), offline = true))
        assertShown(text(R.string.car_offline))
        assertShown("A Long Film")
    }

    @Test fun withNothingToPlayItSaysSo() {
        showCar(open, CarLists())
        assertShown(text(R.string.car_nothing))
    }

    @Test fun thePlayersControlsAreLargeAndEachDoesItsJob() {
        showCar(open, player = playing())
        assertShown("The Turn")
        assertShown("1:05 / 50:00")
        for (label in listOf(R.string.car_list_spoken, R.string.car_back_ten_spoken, R.string.player_pause, R.string.car_forward_thirty_spoken)) {
            rule.onNode(hasContentDescription(text(label))).assertIsDisplayed().assertHeightIsAtLeast(64.dp)
        }
        rule.onNode(hasContentDescription(text(R.string.car_back_ten_spoken))).performClick()
        rule.onNode(hasContentDescription(text(R.string.car_forward_thirty_spoken))).performClick()
        rule.onNode(hasContentDescription(text(R.string.player_pause))).performClick()
        rule.onNode(hasContentDescription(text(R.string.car_list_spoken))).performClick()
        assertEquals(listOf("back10", "forward30", "playPause", "back"), calls)
    }

    @Test fun pausedItOffersPlay() {
        showCar(open, player = playing(Phase.PAUSED))
        rule.onNode(hasContentDescription(text(R.string.player_play))).assertIsDisplayed()
    }

    @Test fun withTheControlsHiddenOnlyThePictureShowsAndATapAsksForThem() {
        showCar(open, player = playing(controls = false))
        assertNotShown(text(R.string.car_list))
        rule.onRoot().performClick()
        assertEquals(listOf("tap"), calls)
    }

    @Test fun aFailureSaysWhatHappenedAndOffersTheList() {
        showCar(open, player = playing(Phase.FAILED, controls = false, error = AppError.Kind.OFFLINE))
        assertShown(AppError.of(AppError.Kind.OFFLINE).userMessage)
        rule.onNode(hasContentDescription(text(R.string.car_list_spoken))).assertIsDisplayed()
        assertNotShown(text(R.string.car_back_ten))
    }
}

@Config(qualifiers = "w1280dp-h720dp-land") class CarScreen1280RenderTest : CarScreenRenderBase()
@Config(qualifiers = "w800dp-h480dp-land") class CarScreen800RenderTest : CarScreenRenderBase()
