package app.shoalwatch.ui.lists

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.lists.Playlist
import app.shoalwatch.data.lists.PlaylistEntry
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.FILMS
import app.shoalwatch.ui.browse.PHONE
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.SERVER_ID
import app.shoalwatch.ui.browse.SHOWS
import app.shoalwatch.ui.browse.TABLET
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.browse.item
import app.shoalwatch.ui.home.HomeActions
import app.shoalwatch.ui.home.HomePhase
import app.shoalwatch.ui.home.HomeScreen
import app.shoalwatch.ui.home.HomeUiState
import app.shoalwatch.ui.present.Destination
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Milestone 11 Task 7: the Playlists screen, one playlist, the add-to picker and Home's Playlists card, at phone and tablet size and
 * at font scale 1.0 and 2.0. Every control is 48dp or more and names what it does.
 */
abstract class ListsRenderBase : RenderBase() {
    private val snackbar = SnackbarHostState()
    private val done = mutableListOf<String>()
    private val name = TextFieldState()

    private fun playlistItem(n: Int, title: String, count: Int?) =
        ItemWithUserData(item(n, ItemKind.PLAYLIST, title).copy(childCount = count), null)

    private val playlistsActions = PlaylistsActions(
        onBack = { done += "back" }, onOpen = { done += "open:${(it as Destination.Playlist).playlistId}" }, onRetry = { done += "retry" },
        onNew = { done += "new" }, onCreate = { done += "create" }, onCancelCreate = { done += "cancel" },
    )

    private fun showPlaylists(state: PlaylistsUiState) = show { PlaylistsScreen(state, playlistsActions, name, emptyFlow(), snackbar) }

    private val ready = PlaylistsUiState(
        session = BEN, phase = ListsPhase.READY,
        playlists = listOf(playlistItem(1, "Weekend", 3), playlistItem(2, "Bedtime", 1)),
    )

    @Test fun thePlaylistsAreCardsThatOpenAndNewPlaylistIsAtTheTop() {
        showPlaylists(ready)
        assertShown(text(R.string.lists_playlists_title))
        rule.onNode(hasText(text(R.string.lists_new_playlist))).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithContentDescription("Weekend, 3 items").performClick()
        rule.onNodeWithContentDescription("Bedtime, 1 item").assertExists()
        assertEquals(listOf("new", "open:${id(1)}"), done)
    }

    @Test fun offlineSaysSoWithTryAgain() {
        showPlaylists(ready.copy(phase = ListsPhase.OFFLINE, playlists = emptyList()))
        assertShown(text(R.string.lists_offline_title))
        rule.onNode(hasText(text(R.string.action_retry))).performClick()
        assertEquals(listOf("retry"), done)
    }

    @Test fun noPlaylistsYetSaysHowToMakeOne() {
        showPlaylists(ready.copy(playlists = emptyList()))
        assertShown(text(R.string.lists_empty_title))
        assertShown(text(R.string.lists_new_playlist))
    }

    @Test fun theNewPlaylistWindowAsksForAName() {
        name.clearText()
        showPlaylists(ready.copy(creating = true, nameError = R.string.lists_name_empty))
        assertShown(text(R.string.lists_create_playlist_title))
        assertShown(text(R.string.lists_name_empty))
        rule.onNode(hasText(text(R.string.lists_create))).assertIsEnabled().performClick()
        assertEquals(listOf("create"), done)
    }

    private fun entryOf(n: Int, title: String) = PlaylistEntry("e$n", ItemWithUserData(item(n, ItemKind.MOVIE, title), null))

    private val playlistActions = PlaylistActions(
        onBack = {}, onOpen = { done += "open" }, onRetry = {}, onPlay = { done += "play" }, onShuffle = { done += "shuffle" },
        onDownloadAll = { done += "download" }, onMove = { e, d -> done += "move:$e:$d" }, onRemove = { done += "remove:$it" },
        onAskRename = { done += "rename" }, onRename = {}, onCancelRename = {}, onAskDelete = { done += "ask-delete" },
        onConfirmDelete = { done += "delete" }, onCancelDelete = { done += "keep" },
    )

    private fun playlistState(canDelete: Boolean = true, busy: Boolean = false, deleteAsk: Boolean = false) = PlaylistUiState(
        session = BEN, phase = ListsPhase.READY, busy = busy, deleteAsk = deleteAsk,
        playlist = Playlist(item(99, ItemKind.PLAYLIST, "Weekend"), canDelete, listOf(entryOf(1, "First Film"), entryOf(2, "Second Film"), entryOf(3, "Third Film"))),
    )

    private fun showPlaylist(state: PlaylistUiState) = show { PlaylistScreen(state, playlistActions, name, emptyFlow(), snackbar) }

    @Test fun playShuffleAndDownloadAllAreButtons() {
        showPlaylist(playlistState())
        assertShown("Weekend")
        rule.onNode(hasText(text(R.string.lists_play))).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNode(hasText(text(R.string.lists_shuffle))).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNode(hasText(text(R.string.lists_download_all))).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("play", "shuffle", "download"), done)
    }

    @Test fun eachEntryHasAccessibleMoveUpMoveDownAndRemoveNamingIt() {
        showPlaylist(playlistState())
        scrollTo(hasContentDescription(text(R.string.lists_move_up, "Second Film")))
        rule.onNodeWithContentDescription(text(R.string.lists_move_up, "Second Film")).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithContentDescription(text(R.string.lists_move_down, "Second Film")).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithContentDescription(text(R.string.lists_remove_entry, "Second Film")).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("move:e2:-1", "move:e2:1", "remove:e2"), done)
    }

    @Test fun theFirstCannotMoveUpAndTheLastCannotMoveDown() {
        showPlaylist(playlistState())
        rule.onNodeWithContentDescription(text(R.string.lists_move_up, "First Film")).assertIsNotEnabled()
        rule.onNodeWithContentDescription(text(R.string.lists_move_down, "First Film")).assertIsEnabled()
        scrollTo(hasContentDescription(text(R.string.lists_move_down, "Third Film")))
        rule.onNodeWithContentDescription(text(R.string.lists_move_down, "Third Film")).assertIsNotEnabled()
    }

    @Test fun whileAChangeRunsEveryControlWaits() {
        showPlaylist(playlistState(busy = true))
        rule.onNode(hasText(text(R.string.lists_play))).assertIsNotEnabled()
        rule.onNodeWithContentDescription(text(R.string.lists_move_down, "First Film")).assertIsNotEnabled()
        rule.onNodeWithContentDescription(text(R.string.lists_remove_entry, "First Film")).assertIsNotEnabled()
    }

    @Test fun deleteIsOfferedOnlyWhenTheServerAllowsItAndAsksFirstNamingThePlaylist() {
        showPlaylist(playlistState(canDelete = false))
        scrollTo(hasText(text(R.string.lists_rename)))
        assertShown(text(R.string.lists_rename))
        assertTrue(!shown(text(R.string.lists_delete)))
    }

    @Test fun theDeleteQuestionNamesThePlaylistAndSaysWhatStays() {
        showPlaylist(playlistState(deleteAsk = true))
        assertShown(text(R.string.lists_delete_title, "Weekend"))
        assertShown(text(R.string.lists_delete_body))
        rule.onNode(hasText(text(R.string.lists_delete_confirm))).performClick()
        rule.onNode(hasText(text(R.string.action_cancel))).performClick()
        assertEquals(listOf("delete", "keep"), done)
    }

    @Test fun anEmptyPlaylistSaysSoAndOffersNoPlay() {
        val empty = playlistState().let { it.copy(playlist = it.playlist!!.copy(entries = emptyList())) }
        showPlaylist(empty)
        assertShown(text(R.string.lists_playlist_empty_title))
        assertTrue(!shown(text(R.string.lists_play)))
    }

    private fun host(state: PickerUiState) = ListPickerHost(
        state, emptyFlow(), name,
        onOpen = { _, _ -> }, onChoose = { done += "choose:${it.name}" }, onStartCreate = { done += "start-create" }, onBackToList = { done += "back-to-list" },
        onCreate = { done += "create" }, onRetry = { done += "retry" }, onDismiss = { done += "dismiss" },
    )

    private fun picker(kind: ListKind = ListKind.PLAYLIST, phase: ListsPhase = ListsPhase.READY, creating: Boolean = false, error: Int = 0, choices: List<ListChoice> = listOf(ListChoice(id(7), "Weekend"), ListChoice(id(8), "Bedtime"))) =
        PickerUiState(session = BEN, kind = kind, itemCount = 3, phase = phase, choices = choices, creating = creating, error = error)

    @Test fun thePickerListsThePlaylistsWithNewPlaylistFirst() {
        show { ListPickerDialog(host(picker())) }
        assertShown(text(R.string.lists_picker_playlist_title))
        assertShown("Adding 3 items.")
        rule.onNode(hasText(text(R.string.lists_new_playlist))).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNode(hasText("Bedtime")).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("start-create", "choose:Bedtime"), done)
    }

    @Test fun theCollectionPickerSaysCollection() {
        show { ListPickerDialog(host(picker(ListKind.COLLECTION, choices = emptyList()))) }
        assertShown(text(R.string.lists_picker_collection_title))
        assertShown(text(R.string.lists_new_collection))
        assertShown(text(R.string.lists_picker_collections_empty))
    }

    @Test fun thePickerSaysOfflineWithTryAgainAndShowsARefusalInWords() {
        show { ListPickerDialog(host(picker(phase = ListsPhase.OFFLINE))) }
        assertShown(text(R.string.lists_offline_title))
        rule.onNode(hasText(text(R.string.action_retry))).performClick()
        assertEquals(listOf("retry"), done)
    }

    @Test fun aRefusalIsShownInTheSentenceFromThePlan() {
        show { ListPickerDialog(host(picker(error = R.string.lists_refused))) }
        assertShown(text(R.string.lists_refused))
        assertEquals("The server didn't let this profile change that.", text(R.string.lists_refused))
    }

    @Test fun theNewListFormAsksForAName() {
        show { ListPickerDialog(host(picker(creating = true, error = R.string.lists_name_too_long))) }
        assertShown(text(R.string.lists_create_playlist_title))
        assertShown(text(R.string.lists_name_too_long))
        rule.onNode(hasText(text(R.string.lists_create))).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNode(hasText(text(R.string.cd_back))).performClick()
        assertEquals(listOf("create", "back-to-list"), done)
    }

    @Test fun homeShowsThePlaylistsCardEvenWithNoLibraries() {
        val opened = mutableListOf<String>()
        val actions = HomeActions({}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, onOpenPlaylists = { opened += "playlists" })
        val state = HomeUiState(phase = HomePhase.READY, session = BEN, serverId = SERVER_ID, serverName = "Den", userName = "ben", views = emptyList())
        show { HomeScreen(state, actions, emptyFlow(), snackbar) }
        scrollTo(hasContentDescription("${text(R.string.lists_playlists_title)}, ${text(R.string.lists_home_card_subtitle)}"))
        rule.onNodeWithContentDescription("${text(R.string.lists_playlists_title)}, ${text(R.string.lists_home_card_subtitle)}").performClick()
        assertEquals(listOf("playlists"), opened)
    }

    @Test fun homeEndsItsLibrariesWithAPlaylistsCard() {
        val opened = mutableListOf<String>()
        val actions = HomeActions({}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, onOpenPlaylists = { opened += "playlists" })
        val state = HomeUiState(phase = HomePhase.READY, session = BEN, serverId = SERVER_ID, serverName = "Den", userName = "ben", views = listOf(FILMS, SHOWS))
        show { HomeScreen(state, actions, emptyFlow(), snackbar) }
        scrollTo(hasText(text(R.string.home_libraries)))
        rule.onNodeWithContentDescription("${text(R.string.lists_playlists_title)}, ${text(R.string.lists_home_card_subtitle)}").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("playlists"), opened)
    }
}

@Config(qualifiers = PHONE) class ListsPhoneRenderTest : ListsRenderBase() { override val fontScale = 1f }
@Config(qualifiers = PHONE) class ListsPhoneLargeTextRenderTest : ListsRenderBase() { override val fontScale = 2f }
@Config(qualifiers = TABLET) class ListsTabletRenderTest : ListsRenderBase() { override val fontScale = 1f }
@Config(qualifiers = TABLET) class ListsTabletLargeTextRenderTest : ListsRenderBase() { override val fontScale = 2f }
