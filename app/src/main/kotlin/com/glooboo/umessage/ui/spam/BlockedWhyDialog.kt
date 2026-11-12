package com.glooboo.umessage.ui.spam

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.CodeMasker
import com.glooboo.umessage.helpers.w3cPrefs
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.ui.components.InfoDialog
import com.glooboo.umessage.ui.components.hideCodesSetting
import com.glooboo.umessage.ui.components.uiLocale
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Why blocked" for one message: the reason (a rule's chip names the rule and opens the rules, SU-22), the
 * held-because sentence and the code seen in it (ID-16), when it arrived and when it is auto-deleted (SU-23), and
 * what is kept of a picture message (SU-17).
 */
@Composable
internal fun WhyBlockedDialog(
    message: BlockedMessage,
    state: BlockedUiState,
    onOpenRules: () -> Unit,
    onDismiss: () -> Unit,
    /** spamui-21: allows the sender for good; the button shows only while [AlwaysAllowPolicy] offers it. */
    onAlwaysAllow: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = uiLocale()
    val colors = MaterialTheme.colorScheme
    val explanation = remember(message.id, message.body, message.reason) { BlockedExplain.of(message) }
    val received = remember(message.receivedAt, locale) {
        blockedReceivedTime(
            message.receivedAt,
            ZoneId.systemDefault(),
            DateFormat.is24HourFormat(context),
            locale,
            context.w3cPrefs.dateStyle,
        )
    }
    val purge = remember(message.receivedAt, state.purgeAfterDays, resources) {
        purgeLeftText(resources, message.receivedAt, System.currentTimeMillis(), state.purgeAfterDays)
    }
    // Phone-number matching: off the main thread. Until it answers the button is not shown.
    val canAllow by produceState(false, message.address, state.rules, onAlwaysAllow) {
        value = onAlwaysAllow != null &&
            withContext(Dispatchers.Default) { AlwaysAllowPolicy.canOffer(state.rules.values, message.address) }
    }
    val allowButton: (@Composable () -> Unit)? = if (canAllow) {
        {
            TextButton(onClick = {
                onAlwaysAllow?.invoke(message.address)
                onDismiss()
            }) { Text(stringResource(R.string.decm_always_allow)) }
        }
    } else {
        null
    }
    InfoDialog(onDismiss = onDismiss, title = stringResource(R.string.r3b_why_blocked), dismissButton = allowButton) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val openRules: (() -> Unit)? = if (message.reason == DecisionReason.BLOCK_RULE) {
                fun() {
                    onDismiss()
                    onOpenRules()
                }
            } else {
                null
            }
            ReasonBadge(message, state.rules, onOpenRules = openRules)
            explanation?.let { Text(stringResource(heldWhyText(it.why)), style = MaterialTheme.typography.bodyMedium) }
            explanation?.code?.let {
                // The code is hidden here as well when "Hide codes on the lock screen" is on.
                val shownCode = if (hideCodesSetting()) CodeMasker.BULLETS else it
                Text(stringResource(R.string.w3b_code_seen, shownCode), style = MaterialTheme.typography.labelLarge)
            }
            if (message.reason == DecisionReason.UNKNOWN_SENDER && BlockedHints.looksImportant(message.body)) {
                Text(
                    stringResource(R.string.blocked_may_be_important),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.secondary,
                )
            }
            mmsMediaLabel(message)?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            val quiet = MaterialTheme.typography.bodySmall
            Text(stringResource(R.string.r3b_received, received), style = quiet, color = colors.onSurfaceVariant)
            simLabel(state.sims, message.subscriptionId)?.let {
                Text(it, style = quiet, color = colors.onSurfaceVariant)
            }
            purge?.let { Text(it, style = quiet, color = colors.onSurfaceVariant) }
        }
    }
}
