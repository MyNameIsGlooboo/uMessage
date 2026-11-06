package com.glooboo.umessage.ui.spam

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.icons.Symbols

/** "3 restored by you": a chip under the Blocked line that opens the restored list (BR-10). */
@Composable
fun BlockedRestoredLink(count: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onOpen,
        label = {
            Text(stringResource(R.string.blocked_stats_restored, count), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingIcon = {
            Icon(Symbols.ChevronRight, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
        },
        colors = AssistChipDefaults.assistChipColors(
            labelColor = MaterialTheme.colorScheme.primary,
            trailingIconContentColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = modifier.padding(horizontal = 16.dp),
    )
}
