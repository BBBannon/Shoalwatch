package app.shoalwatch.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.shoalwatch.data.images.primaryArtwork
import app.shoalwatch.data.images.thumbArtwork
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.components.Artwork
import app.shoalwatch.ui.components.ArtworkAspect
import app.shoalwatch.ui.components.BackBar
import app.shoalwatch.ui.components.FreshnessBanner
import app.shoalwatch.ui.components.Gutter
import app.shoalwatch.ui.components.ReadableWidth
import app.shoalwatch.ui.downloads.DetailDownloadButton
import app.shoalwatch.ui.downloads.DownloadControls
import app.shoalwatch.ui.present.detailFacts
import app.shoalwatch.ui.present.displayName
import app.shoalwatch.ui.present.plainOverview
import app.shoalwatch.ui.present.rememberStrings
import app.shoalwatch.ui.present.resumeLabel

/** The left pane's width on a landscape tablet. */
private val PicturePaneWidth = 360.dp

/** The picture in the left pane, for tests. */
internal const val DETAIL_PICTURE_TAG = "detail-picture"

/**
 * A film or an episode on a landscape tablet ([app.shoalwatch.ui.components.isTwoPane]). Left, 360dp: Back, the poster (an
 * episode's own still), then Play or Resume (and Play from start) and Download. Right, the rest of the width (at most
 * [ReadableWidth], centred), scrolling: the banner, the episode's series line, the title (the page's heading) and facts, the
 * watched state with its bar, Mark watched and favourite, Go to series and the overview. Each pane scrolls on its own.
 */
@Composable
internal fun DetailTwoPane(state: DetailUiState, entry: ItemWithUserData, session: Session, actions: DetailActions, download: DownloadControls?) {
    val strings = rememberStrings()
    val item = entry.item
    val data = entry.userData
    val resume = resumeLabel(strings, data?.positionMs ?: 0)
    val overview = remember(item.overview) { plainOverview(item.overview) }
    val title = item.displayName(strings)
    val episode = item.kind == ItemKind.EPISODE

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(PicturePaneWidth).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackBar(actions.onBack)
            val picture = if (episode) item.thumbArtwork(session) ?: item.primaryArtwork(session) else item.primaryArtwork(session)
            Artwork(
                picture, null, aspect = if (episode) ArtworkAspect.WIDE else ArtworkAspect.POSTER, title = title,
                modifier = Modifier.padding(horizontal = Gutter).fillMaxWidth().testTag(DETAIL_PICTURE_TAG),
            )
            Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PlayButtons(resume, actions)
                if (download != null && (item.kind == ItemKind.MOVIE || episode)) DetailDownloadButton(item.id, download)
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = ReadableWidth).fillMaxWidth().verticalScroll(rememberScrollState()).statusBarsPadding()
                    .padding(top = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.banner?.let { FreshnessBanner(it, bannerActions(state, actions)) }
                Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (episode) EpisodeLine(item)
                    TitleAndFacts(title, detailFacts(strings, item), actions.onRefresh, MaterialTheme.typography.headlineMedium)
                    WatchStateFor(entry)
                    MarkAndFavourite(state, entry, actions)
                    GoToSeries(item, actions)
                    Overview(overview)
                }
            }
        }
    }
}
