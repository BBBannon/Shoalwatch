package app.shoalwatch.ui.car

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shoalwatch.R
import app.shoalwatch.core.AppError
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.ui.present.PlayerText
import app.shoalwatch.ui.present.episodeCode
import app.shoalwatch.ui.present.rememberStrings

/** What the car screen's buttons and rows do (the activity hands the [CarViewModel]'s own functions). */
class CarActions(
    val onPlay: (String) -> Unit = {},
    val onPlayPause: () -> Unit = {},
    val onBackTen: () -> Unit = {},
    val onForwardThirty: () -> Unit = {},
    val onTapPicture: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/**
 * The car screen (Milestone 9): a plain message while the phone is locked or signed out (nothing of any profile), else the lists or
 * the full-screen player. Large text and touch targets for a car display; the phone's own theme. [picture] is the video itself.
 */
@Composable
fun CarScreen(access: CarAccess, lists: CarLists?, player: CarPlayer?, actions: CarActions, picture: @Composable () -> Unit = {}) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            access == CarAccess.SignIn -> Message(stringResource(R.string.car_sign_in))
            access == CarAccess.Locked -> Message(stringResource(R.string.car_unlock))
            access == CarAccess.PhoneDisplay -> Message(stringResource(R.string.car_open_on_car))
            access !is CarAccess.Open || lists == null || lists.session != access.session -> Waiting() // never another profile's (review I2)
            player != null -> CarPlayerView(player, actions, picture)
            else -> ListsView(lists, actions)
        }
    }
}

@Composable
private fun Message(text: String) = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Text(text, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
}

@Composable
private fun Waiting() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    val loading = stringResource(R.string.loading)
    CircularProgressIndicator(Modifier.semantics { contentDescription = loading })
}

@Composable
private fun ListsView(lists: CarLists, actions: CarActions) {
    if (lists.resume.isEmpty() && lists.nextUp.isEmpty() && lists.downloads.isEmpty()) {
        Message(stringResource(if (lists.offline) R.string.car_offline else R.string.car_nothing))
        return
    }
    val resume = stringResource(R.string.home_continue)
    val nextUp = stringResource(R.string.home_next_up)
    val saved = stringResource(R.string.downloads_title)
    val offline = stringResource(R.string.car_offline)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 960.dp).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (lists.offline) item { Text(offline, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.tertiary) }
            section(resume, lists.resume, actions)
            section(nextUp, lists.nextUp, actions)
            section(saved, lists.downloads, actions)
        }
    }
}

private fun LazyListScope.section(title: String, items: List<ItemWithUserData>, actions: CarActions) {
    if (items.isEmpty()) return
    item(key = "h:$title") {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
    }
    items(items, key = { "$title:${it.item.id}" }) { CarRow(it, actions) }
}

/** One big row: the title, and for an episode its series and code. The whole row plays it. */
@Composable
private fun CarRow(entry: ItemWithUserData, actions: CarActions) {
    val strings = rememberStrings()
    val item = entry.item
    val line = listOfNotNull(item.seriesName, episodeCode(strings, item)).joinToString(" · ").ifEmpty { null }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).clickable { actions.onPlay(item.id) },
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 14.dp), verticalArrangement = Arrangement.Center) {
            Text(item.name, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            line?.let { Text(it, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** The picture full screen; a tap shows the controls along the top (a car may have no Back key of its own). Back stops. */
@Composable
private fun CarPlayerView(player: CarPlayer, actions: CarActions, picture: @Composable () -> Unit) {
    BackHandler(onBack = actions.onBack)
    val p = player.playback
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = actions.onTapPicture)) {
        picture()
        val error = if (p.phase == Phase.FAILED) p.error ?: AppError.Kind.PLAYBACK_FAILED else null
        when {
            error != null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(AppError.of(error).userMessage, color = Color.White, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            }
            p.phase == Phase.IDLE || p.phase == Phase.RESOLVING || p.phase == Phase.BUFFERING -> {
                val loading = stringResource(R.string.player_loading)
                CircularProgressIndicator(Modifier.align(Alignment.Center).semantics { contentDescription = loading })
            }
        }
        if (player.controlsVisible || error != null) Controls(player, actions, Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun Controls(player: CarPlayer, actions: CarActions, modifier: Modifier) {
    val p = player.playback
    Column(modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CarButton(stringResource(R.string.car_list), stringResource(R.string.car_list_spoken), actions.onBack)
            if (p.phase != Phase.FAILED) {
                CarButton(stringResource(R.string.car_back_ten), stringResource(R.string.car_back_ten_spoken), actions.onBackTen)
                val playing = p.phase == Phase.PLAYING || p.phase == Phase.BUFFERING
                val label = stringResource(if (playing) R.string.player_pause else R.string.player_play)
                CarButton(label, label, actions.onPlayPause)
                CarButton(stringResource(R.string.car_forward_thirty), stringResource(R.string.car_forward_thirty_spoken), actions.onForwardThirty)
            }
        }
        p.title?.let { Text(it, color = Color.White, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp)) }
        p.durationMs?.takeIf { it > 0 }?.let { d ->
            Text(stringResource(R.string.car_time, PlayerText.clock(p.positionMs), PlayerText.clock(d)), color = Color.White, fontSize = 18.sp)
        }
    }
}

@Composable
private fun CarButton(text: String, spoken: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.heightIn(min = 64.dp).widthIn(min = 96.dp).semantics { contentDescription = spoken }) {
        Text(text, fontSize = 22.sp)
    }
}
