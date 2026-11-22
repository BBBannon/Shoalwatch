package app.shoalwatch.ui.subtitles

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.shoalwatch.R
import app.shoalwatch.data.subtitles.SubtitleCandidate
import app.shoalwatch.ui.browse.PHONE
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.TABLET
import app.shoalwatch.ui.player.PlayerActions
import app.shoalwatch.ui.player.PlayerScreen
import app.shoalwatch.ui.player.PlayerSheet
import app.shoalwatch.ui.player.ActionLog
import app.shoalwatch.ui.player.ui
import app.shoalwatch.ui.player.playingEpisode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

abstract class SubtitleSearchRenderBase : RenderBase() {
    private val calls = mutableListOf<String>()
    private val actions = SubtitleSearchActions(
        { calls += "open" }, { calls += "close" }, { calls += "toggle" }, { calls += "language:$it" }, { calls += "retry" }, { calls += "download:${it.id}" },
    )
    private val one = SubtitleCandidate("srt-eng-1", "OpenSubtitles", "The Film (2021)", "srt", 8.5f, 1200, hearingImpaired = true, forced = false)
    private val two = SubtitleCandidate("srt-eng-2", "Podnapisi", "The Film", "ass", null, null, hearingImpaired = false, forced = true)

    private fun results(sending: Boolean = false, message: Int = 0) = SubtitleSearchUi(
        open = true, phase = SubtitleSearchPhase.RESULTS, candidates = listOf(one, two), sending = sending, message = message,
    )

    private fun showBody(ui: SubtitleSearchUi) = show {
        Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) { SubtitleSearchBody(SubtitleSearchControls(ui, actions)) }
    }

    @Test fun resultsAreTextOnlyWithEveryFactAndAPlainDownloadButton() {
        showBody(results())
        assertShown("The Film (2021)")
        assertShown("OpenSubtitles · SRT · ${text(R.string.subsearch_rating, "8.5")} · 1,200 downloads · ${text(R.string.subsearch_hearing_impaired)}")
        assertShown("Podnapisi · ASS · ${text(R.string.subsearch_forced)}")
        assertShown(text(R.string.subsearch_language, "English"))
        assertDescribed(text(R.string.subsearch_download_for, "The Film (2021)"))
    }

    @Test fun downloadingIsSentOnceFromTheNamedButton() {
        showBody(results())
        rule.onNode(hasContentDescription(text(R.string.subsearch_download_for, "The Film (2021)"))).performScrollTo().performClick()
        assertEquals(listOf("download:srt-eng-1"), calls)
    }

    @Test fun whileSendingEveryDownloadButtonWaitsAndSaysSo() {
        showBody(results(sending = true))
        assertShown(text(R.string.subsearch_sending))
        rule.onAllNodes(hasText(text(R.string.subsearch_download)).and(isEnabled())).assertCountEquals(0)
        rule.onAllNodes(hasText(text(R.string.subsearch_download)).and(isNotEnabled())).assertCountEquals(2)
    }

    @Test fun theLanguageButtonOpensAListOfReadableNamesAndPicksOne() {
        showBody(SubtitleSearchUi(open = true, picking = true, phase = SubtitleSearchPhase.RESULTS))
        assertShown(text(R.string.subsearch_language_heading))
        assertShown("French")
        rule.onAllNodes(hasText("French")).onFirst().performScrollTo().performClick()
        assertEquals(listOf("language:fre"), calls)
    }

    @Test fun theDownloadsResultIsSaidInWords() {
        showBody(results(message = R.string.subsearch_later))
        assertShown("Downloaded. It can take a minute to show.")
    }

    @Test fun offlineSaysSoWithTryAgain() {
        showBody(SubtitleSearchUi(open = true, phase = SubtitleSearchPhase.OFFLINE))
        assertShown("You're offline")
        rule.onNode(hasText(text(R.string.action_retry))).performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test fun aRefusalIsTheSentenceFromThePlan() {
        showBody(SubtitleSearchUi(open = true, phase = SubtitleSearchPhase.NOT_ALLOWED))
        assertShown("This profile isn't allowed to fetch subtitles.")
    }

    @Test fun noMatchesAndAFailureAreSaidInWords() {
        showBody(SubtitleSearchUi(open = true, phase = SubtitleSearchPhase.RESULTS))
        assertShown(text(R.string.subsearch_none))
    }

    @Test fun searchingShowsProgressInWords() {
        showBody(SubtitleSearchUi(open = true, phase = SubtitleSearchPhase.SEARCHING))
        assertShown(text(R.string.subsearch_searching))
    }

    @Test fun theDetailPagesWindowHasATitleResultsAndClose() {
        show { SubtitleSearchDialog(SubtitleSearchControls(results(), actions)) }
        assertShown(text(R.string.subsearch_title))
        assertShown("The Film (2021)")
        rule.onNode(hasText(text(R.string.subsearch_close))).performClick()
        assertEquals(listOf("close"), calls)
    }

    @Test fun aClosedSearchShowsNothing() {
        show { SubtitleSearchDialog(SubtitleSearchControls(SubtitleSearchUi(), actions)) }
        assertNotShown(text(R.string.subsearch_title))
    }

    // ---- inside the player's sheets

    private fun player(sheet: PlayerSheet, search: SubtitleSearchUi = results(), method: app.shoalwatch.data.playback.PlayMethod? = null) {
        val base = ActionLog().actions
        val withSearch = PlayerActions(
            base.onBack, base.onTapSurface, base.onPlayPause, base.onSeek, base.onSkipBack, base.onSkipForward, base.onDoubleTap, base.onPrevious, base.onNext,
            base.onSkipSegment, base.onPlayUpNext, base.onCancelUpNext, base.onToggleLock, base.onOpenSheet, base.onCloseSheet, base.onSelectAudio,
            base.onSelectSubtitle, base.onBurnIn, base.onQuality, base.onSpeed, base.onSleep, base.onAppearance, base.onDelay, base.onRetry, base.onSignIn,
            base.onDismissSubtitleNotice, base.onDismissSleepFired, search = actions,
        )
        val playback = playingEpisode.let { if (method != null) it.copy(method = method) else it }
        show { PlayerScreen(ui(playback).copy(sheet = sheet, search = search), withSearch) }
    }

    @Test fun theSubtitlesSheetOffersFindSubtitlesOnline() {
        player(PlayerSheet.TRACKS)
        rule.onNode(hasText(text(R.string.subsearch_find))).performScrollTo().performClick()
        assertEquals(listOf("open"), calls)
    }

    @Test fun aSavedCopyOnThePhoneOffersNoSearch() {
        player(PlayerSheet.TRACKS, method = app.shoalwatch.data.playback.PlayMethod.LOCAL)
        assertNotShown(text(R.string.subsearch_find))
    }

    @Test fun theSearchSheetShowsResultsAndBackReturnsToTheSubtitles() {
        player(PlayerSheet.SUBTITLE_SEARCH)
        assertShown(text(R.string.subsearch_title))
        assertShown("The Film (2021)")
        rule.onNode(hasText(text(R.string.cd_back))).performScrollTo().performClick()
        assertEquals(listOf("close"), calls)
    }
}

@Config(qualifiers = PHONE) class SubtitleSearchPhoneRenderTest : SubtitleSearchRenderBase() { override val fontScale = 1f }
@Config(qualifiers = PHONE) class SubtitleSearchPhoneLargeTextRenderTest : SubtitleSearchRenderBase() { override val fontScale = 2f }
@Config(qualifiers = TABLET) class SubtitleSearchTabletRenderTest : SubtitleSearchRenderBase() { override val fontScale = 1f }
@Config(qualifiers = TABLET) class SubtitleSearchTabletLargeTextRenderTest : SubtitleSearchRenderBase() { override val fontScale = 2f }
