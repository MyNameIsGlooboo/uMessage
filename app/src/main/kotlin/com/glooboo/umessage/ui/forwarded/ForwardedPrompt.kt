package com.glooboo.umessage.ui.forwarded

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R

/**
 * The one-time question after the first forward rule is saved: "Show a Forwarded tab?". Shown as a persistent tonal
 * card (a snackbar is too easy to miss). The caller stores the answer in `NavPrefs.showForwardedTab` (true for
 * [onShow], false for [onNotNow]); it is asked when [ForwardedTabPrompt.shouldOffer] says so.
 */
@Composable
fun ForwardedTabPromptCard(onShow: () -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.nav_prompt_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.nav_prompt_body), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShow, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.nav_prompt_show))
                }
                TextButton(onClick = onNotNow, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.nav_prompt_not_now))
                }
            }
        }
    }
}
