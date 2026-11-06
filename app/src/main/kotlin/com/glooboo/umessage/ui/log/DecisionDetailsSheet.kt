package com.glooboo.umessage.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.SpamLog
import com.glooboo.umessage.ui.components.localizedNumber
import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.spam.SenderBlocking

/**
 * SE-38 / SE-39 / ID-40: the sheet a log row opens. It explains the decision (with the rule's name, for allow rules
 * too), shows the message while it is still in Blocked with "Restore message", and offers the sender fixes.
 * [text] is the row's already-resolved wording, so the sheet and the row always agree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DecisionDetailsSheet(
    group: LogRowGroup,
    text: RowText,
    blockedMessage: BlockedMessage?,
    onDismiss: () -> Unit,
    actions: DecisionSheetActions,
) {
    val entry = group.newest
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.declog_details_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                senderText(entry.sender),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DecisionFacts(group, text)
            FixState(group.fix)
            if (blockedMessage != null) {
                BlockedMessageCard(onRestore = { actions.onRestore(blockedMessage) }, message = blockedMessage)
            }
            if (canFix(entry)) {
                FixButtons(actions.onFix, entry.sender.trim(), wasAllowed = entry.decision == "ALLOW")
            }
        }
    }
}

@Composable
private fun DecisionFacts(group: LogRowGroup, text: RowText) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text.decision + stringResource(R.string.declog_separator) +
                "${rememberLogDay(logDayOf(group.newest.time))}, ${rememberLogTime(group.newest.time)}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = decisionColor(group.newest.decision),
        )
        text.reason?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        Text(
            listOfNotNull(text.source, text.sim).joinToString(stringResource(R.string.declog_separator)),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        if (group.count > 1) {
            val count = localizedNumber(group.count, uiLocale())
            Text(
                pluralStringResource(R.plurals.declog_repeated, group.count, count),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FixState(fix: String) {
    val res = when (fix) {
        "" -> return
        SpamLog.FIX_ALLOWED -> R.string.declog_fixed_allowed
        SpamLog.FIX_BLOCKED -> R.string.declog_fixed_blocked
        SpamLog.FIX_RESTORED -> R.string.declog_fixed_restored
        else -> R.string.w3b_fix_done
    }
    Text(stringResource(res), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun BlockedMessageCard(onRestore: () -> Unit, message: BlockedMessage) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.declog_blocked_message),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (message.hasText) {
                Text(
                    message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = PREVIEW_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalButton(onClick = onRestore, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.declog_restore))
            }
        }
    }
}

/** The sender fixes. The button that reverses the decision comes first; rules for spoofable names get a warning. */
@Composable
private fun FixButtons(onFix: (allow: Boolean) -> Unit, sender: String, wasAllowed: Boolean) {
    val spoofable = remember(sender) { SenderBlocking.isSpoofable(sender) }
    val allow: @Composable () -> Unit = { Text(stringResource(R.string.w3b_fix_allow)) }
    val block: @Composable () -> Unit = { Text(stringResource(R.string.w3b_fix_block)) }
    val buttonModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.w3b_fix_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (spoofable) {
            Text(
                stringResource(R.string.spam_allow_spoof_warning, sender),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        FilledTonalButton(onClick = { onFix(!wasAllowed) }, modifier = buttonModifier) {
            if (wasAllowed) block() else allow()
        }
        OutlinedButton(onClick = { onFix(wasAllowed) }, modifier = buttonModifier) {
            if (wasAllowed) allow() else block()
        }
    }
}

private const val PREVIEW_LINES = 3
