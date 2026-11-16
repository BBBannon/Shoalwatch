package app.shoalwatch.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.shoalwatch.R

/** The brand mark: three small fish swimming the same way, on deep-sea teal. Decorative. */
@Composable
fun ShoalMark(modifier: Modifier = Modifier, size: Dp = 96.dp) {
    Surface(modifier, shape = RoundedCornerShape(size / 4), color = colorResource(R.color.brand_deep_sea)) {
        Image(painterResource(R.drawable.ic_shoal_foreground), contentDescription = null, modifier = Modifier.size(size))
    }
}

/** A bulleted list row: a dot and wrapping text, as a real row rather than a typed bullet character. */
@Composable
fun BulletRow(text: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 10.dp).size(6.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
