package com.glooboo.umessage.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.spam.FirstRun
import com.glooboo.umessage.spam.FirstRunState
import com.glooboo.umessage.ui.components.readableOn
import com.glooboo.umessage.ui.icons.Symbols

/** U13: the chats the last first sort moved to Spam while its card was not tapped yet; 0 shows no card. */
internal fun spamMovedForCard(state: FirstRunState): Int =
    if (state.cardPending) state.movedToSpam.coerceAtLeast(0) else 0

/** [spamMovedForCard] of the first-run runner's state; always 0 while ProviderTruthFlag is off. */
@Composable
internal fun rememberSpamMoved(): Int {
    if (!ProviderTruthFlag.enabled) return 0
    val context = LocalContext.current
    val state by remember(context) { FirstRun.state(context) }.collectAsState()
    return spamMovedForCard(state)
}

/**
 * U13 (answer 15): "N chats are now in Spam. Review." after every first sort (fresh install, update, contacts granted,
 * switch on). It counts every moved chat, also those of senders the user blocked, and stays until tapped; the tap
 * marks it seen for this pass (AD-5: [FirstRun.acknowledge] with card) and opens Spam.
 */
@Composable
internal fun SpamMovedCard(moved: Int, onReview: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val review = {
        FirstRun.acknowledge(context, card = true)
        onReview()
    }
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    val content = readableOn(container, MaterialTheme.colorScheme.onSurface)
    // C-18: one click target for the one action, the Review button.
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    Symbols.Shield,
                    contentDescription = null,
                    tint = readableOn(container, MaterialTheme.colorScheme.primary),
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    pluralStringResource(R.plurals.pt_card_moved, moved, moved),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                stringResource(R.string.pt_card_moved_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = review, colors = ButtonDefaults.textButtonColors(contentColor = content)) {
                    Text(stringResource(R.string.w3c_receipt_review), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
