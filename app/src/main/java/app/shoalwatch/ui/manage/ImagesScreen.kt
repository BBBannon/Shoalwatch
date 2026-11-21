package app.shoalwatch.ui.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.images.ArtworkRef
import app.shoalwatch.data.images.backdropArtwork
import app.shoalwatch.data.images.primaryArtwork
import app.shoalwatch.data.images.thumbArtwork
import app.shoalwatch.data.manage.ImageKind
import app.shoalwatch.data.manage.ItemImage
import app.shoalwatch.data.manage.RemoteImage
import app.shoalwatch.ui.admin.AdminAction
import app.shoalwatch.ui.admin.AdminCard
import app.shoalwatch.ui.admin.AdminConfirmDialog
import app.shoalwatch.ui.admin.AdminGate
import app.shoalwatch.ui.admin.AdminScaffold
import app.shoalwatch.ui.components.Artwork
import app.shoalwatch.ui.components.ArtworkAspect
import app.shoalwatch.ui.components.ErrorMessage

class ImagesActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onKind: (ImageKind) -> Unit,
    val onUpload: () -> Unit,
    val onFind: () -> Unit,
    val onAllLanguages: (Boolean) -> Unit,
    val onUse: (RemoteImage) -> Unit,
    val onRemove: (ItemImage) -> Unit,
    val onConfirmRemove: () -> Unit,
    val onDismissRemove: () -> Unit,
    val onDismissProblem: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

/**
 * Pictures: a chip per kind (Poster, Backdrop, Logo, Thumbnail, Banner); what the server has now for that kind (the item's own
 * picture beside the first one, through the normal artwork path), with Remove; Upload from this phone; Find online, whose choices
 * are words only (plan decision 7).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImagesScreen(state: ImagesUiState, actions: ImagesActions) {
    val kindName = stringResource(state.kind.label())
    AdminScaffold(stringResource(R.string.images_title), state.frame, actions.onBack, actions.onRefresh, actions.onDismissProblem, actions.onDismissNotice) {
        item(key = "kinds") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in ImageKind.entries) FilterChip(selected = state.kind == k, onClick = { actions.onKind(k) }, label = { Text(stringResource(k.label())) })
            }
        }
        item(key = "current") {
            AdminCard {
                Text(stringResource(R.string.images_current), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                val here = state.images.filter { it.kind == state.kind }
                if (here.isEmpty()) Text(stringResource(R.string.images_none), style = MaterialTheme.typography.bodyMedium)
                for (image in here) CurrentImage(image, kindName, ownPicture(state, image), state.frame.canAct) { actions.onRemove(image) }
            }
        }
        item(key = "upload") {
            AdminCard {
                Text(stringResource(R.string.images_upload_help), style = MaterialTheme.typography.bodyMedium)
                AdminAction(stringResource(R.string.images_upload), stringResource(R.string.images_upload), state.frame.canAct && !state.preparing, onClick = actions.onUpload)
                if (state.preparing) Text(stringResource(R.string.images_uploading), style = MaterialTheme.typography.bodySmall)
                state.pickProblem?.let { ErrorMessage(stringResource(it)) }
            }
        }
        item(key = "find") {
            AdminCard {
                Text(stringResource(R.string.images_online_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.images_all_languages), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = state.allLanguages, onCheckedChange = actions.onAllLanguages)
                }
                AdminAction(stringResource(R.string.images_find), stringResource(R.string.images_find), state.frame.canAct, onClick = actions.onFind)
            }
        }
        val remote = state.remote ?: return@AdminScaffold
        item(key = "online") { Text(stringResource(R.string.images_online), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
        if (remote.images.isEmpty()) item(key = "online-none") { Text(stringResource(R.string.images_online_none), style = MaterialTheme.typography.bodyMedium) }
        itemsIndexed(remote.images, key = { i, _ -> "remote-$i" }) { i, image -> RemoteChoice(image, i + 1, state.frame.canAct) { actions.onUse(image) } }
    }
    if (state.frame.gate !is AdminGate.Open) return
    state.removeAsk?.let { image ->
        AdminConfirmDialog(
            stringResource(R.string.images_remove_title, stringResource(image.kind.label()).lowercase()),
            listOf(stringResource(R.string.images_remove_body)), stringResource(R.string.images_remove), state.frame.canAct,
            actions.onConfirmRemove, actions.onDismissRemove,
        )
    }
}

/** The item's own picture for the first image of the kinds the app shows (from the server, through the artwork checks). */
private fun ownPicture(state: ImagesUiState, image: ItemImage): ArtworkRef? {
    val s = state.session ?: return null
    val item = state.item?.item ?: return null
    if (image.index != 0) return null
    return when (image.kind) {
        ImageKind.PRIMARY -> item.primaryArtwork(s)?.takeIf { it.itemId == item.id }
        ImageKind.BACKDROP -> item.backdropArtwork(s)
        ImageKind.THUMB -> item.thumbArtwork(s)
        else -> null
    }
}

@Composable
private fun CurrentImage(image: ItemImage, kindName: String, picture: ArtworkRef?, canAct: Boolean, onRemove: () -> Unit) {
    val name = if (image.index == 0) kindName else stringResource(R.string.images_number, kindName, image.index + 1)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (picture != null) {
            val aspect = if (image.kind == ImageKind.PRIMARY) ArtworkAspect.POSTER else ArtworkAspect.WIDE
            Artwork(picture, null, aspect = aspect, title = kindName, modifier = Modifier.width(if (aspect == ArtworkAspect.POSTER) 64.dp else 112.dp))
        }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if (image.width != null && image.height != null) {
                Text(stringResource(R.string.images_size, image.width, image.height), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AdminAction(stringResource(R.string.images_remove), stringResource(R.string.images_remove_cd, name), canAct, caution = true, onClick = onRemove)
    }
}

@Composable
private fun RemoteChoice(image: RemoteImage, number: Int, canAct: Boolean, onUse: () -> Unit) {
    val parts = listOfNotNull(
        image.providerName.ifEmpty { null },
        if (image.width != null && image.height != null) stringResource(R.string.images_size, image.width, image.height) else null,
        image.language?.let { code -> java.util.Locale.forLanguageTag(code).displayLanguage.ifEmpty { code } },
        image.rating?.let { stringResource(R.string.images_rated, String.format(java.util.Locale.UK, "%.1f", it)) },
        image.votes?.let { pluralStringResource(R.plurals.images_votes, it, it) },
    )
    val line = parts.joinToString(" · ")
    AdminCard {
        Text(line, style = MaterialTheme.typography.bodyMedium)
        AdminAction(stringResource(R.string.identify_use), stringResource(R.string.images_choice_cd, line, number), canAct, onClick = onUse)
    }
}
