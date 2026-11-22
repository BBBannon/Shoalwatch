package app.shoalwatch.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.discover.PersonRef
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.components.Artwork
import app.shoalwatch.ui.components.ArtworkAspect
import app.shoalwatch.ui.components.CardShape
import app.shoalwatch.ui.components.Gutter
import app.shoalwatch.ui.components.titleLines
import app.shoalwatch.ui.present.posterCard
import app.shoalwatch.ui.present.rememberStrings
import androidx.compose.ui.res.stringResource

/**
 * A film, series or collection as a poster with its name and year under it: one tappable element labelled with the whole card.
 * Give it a [width] (a row) or let it fill its grid cell. Not selectable and no menu: the discovery lists only open things.
 */
@Composable
fun ItemTile(entry: ItemWithUserData, session: Session, onOpen: () -> Unit, modifier: Modifier = Modifier, width: Dp? = null) {
    val strings = rememberStrings()
    val card = remember(entry, session, strings) { posterCard(entry, session, strings) }
    Column(
        (if (width != null) modifier.width(width) else modifier.fillMaxWidth()).clip(CardShape)
            .clickable(role = Role.Button, onClick = onOpen).semantics(mergeDescendants = true) { contentDescription = card.label },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Artwork(card.artwork, contentDescription = null, aspect = ArtworkAspect.POSTER, title = card.title, modifier = Modifier.fillMaxWidth())
        Text(card.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = titleLines(), overflow = TextOverflow.Ellipsis)
        card.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** A person in a row of cast and crew: their picture, name and part (the character or the job). Opens their page. */
@Composable
fun PersonCard(person: PersonRef, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val role = person.role?.takeIf { it.isNotBlank() } ?: person.type?.takeIf { it.isNotBlank() }
    val label = if (role == null) person.name else stringResource(R.string.extras_person_cd, person.name, role)
    Column(
        modifier.width(PersonCardWidth).clip(CardShape).heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onOpen).semantics(mergeDescendants = true) { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Artwork(person.artwork, contentDescription = null, aspect = ArtworkAspect.SQUARE, title = person.name, shape = CircleShape, modifier = Modifier.fillMaxWidth())
        Text(person.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = titleLines(), overflow = TextOverflow.Ellipsis)
        role?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = titleLines(1), overflow = TextOverflow.Ellipsis) }
    }
}

internal val PersonCardWidth = 96.dp

/** A person as a row (search results): a small round picture, the name and, under it, the kind of person. */
@Composable
fun PersonRow(person: PersonRef, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val label = person.name
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = Gutter, vertical = 6.dp).semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(person.artwork, contentDescription = null, aspect = ArtworkAspect.SQUARE, title = person.name, shape = CircleShape, modifier = Modifier.width(48.dp))
        Text(person.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = titleLines(2), overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}
