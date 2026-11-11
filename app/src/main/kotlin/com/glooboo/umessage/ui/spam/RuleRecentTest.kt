package com.glooboo.umessage.ui.spam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.icons.Symbols

/**
 * "Test on recent messages" (spam-21): how many of the newest inbox texts and Blocked messages the rule being edited
 * would match, with a few sender examples. Shown for every rule type once the value is valid; runs only when tapped.
 * Nothing is stored or logged.
 */
@Composable
fun RuleRecentTest(valid: Boolean, preview: RulePreviewState, onRun: () -> Unit, modifier: Modifier = Modifier) {
    if (!valid) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onRun, enabled = preview != RulePreviewState.Loading) {
            Icon(Symbols.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.rule_preview_button), modifier = Modifier.padding(start = 8.dp))
        }
        val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        when (preview) {
            RulePreviewState.Idle -> Unit
            RulePreviewState.Loading -> Row(live, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.rule_preview_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            RulePreviewState.Failed -> Text(
                stringResource(R.string.rule_preview_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = live,
            )
            is RulePreviewState.Done -> Column(live, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (preview.result.isEmpty) {
                    PreviewLine(stringResource(R.string.rule_preview_nothing))
                } else {
                    PreviewPart(R.string.rule_preview_inbox, R.string.rule_preview_inbox_empty, preview.result.inbox)
                    PreviewPart(
                        R.string.rule_preview_blocked, R.string.rule_preview_blocked_empty, preview.result.blocked
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewPart(summary: Int, empty: Int, part: DryRunPart) {
    if (part.scanned == 0) {
        PreviewLine(stringResource(empty))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PreviewLine(stringResource(summary, part.matched, part.scanned))
        if (part.senders.isNotEmpty()) {
            val names = part.senders.map { isolateNumbers(displayNumber(it)) }.joinToString(", ")
            PreviewLine(stringResource(R.string.rule_preview_examples, names), small = true)
        }
    }
}

@Composable
private fun PreviewLine(text: String, small: Boolean = false) {
    Text(
        text,
        style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
