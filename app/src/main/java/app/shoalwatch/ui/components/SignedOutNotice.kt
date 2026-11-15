package app.shoalwatch.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.shoalwatch.R

/** Nobody is signed in: the plain notice that Home has always shown, with a button to Servers and accounts. */
@Composable
fun SignedOutNotice(onOpenServers: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(Gutter), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        NoticeCard(stringResource(R.string.home_signed_out_title), stringResource(R.string.home_signed_out_body), tone = Tone.Info)
        PrimaryButton(stringResource(R.string.home_manage), onOpenServers)
    }
}
