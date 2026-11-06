package com.glooboo.umessage.ui.forwarded

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.ForwardStatus
import com.glooboo.umessage.ui.components.InfoChip
import com.glooboo.umessage.ui.components.localizedNumber
import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.icons.Symbols

/** The Forwarded tab's header card: on, paused, no rule on, or stopped for today by the daily limit (bug 9). */
@Composable
internal fun StatusCard(state: ForwardedUiState, onPausedChange: (Boolean) -> Unit, onManageRules: () -> Unit) {
    val now = remember(state.entries) { System.currentTimeMillis() }
    val header = state.header(now)
    val (container, content) = headerColors(header)
    val pauseLabel = stringResource(R.string.nav_fwd_pause_all)
    val switchLabel = stringResource(R.string.w7d_fwd_master_switch)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = container,
        contentColor = content,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (header == ForwardedHeader.ON) Symbols.Forward else Symbols.Warning, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(headerTitle(header, state), style = MaterialTheme.typography.titleMedium)
                    Text(headerHint(header, state, now) ?: pauseLabel, style = MaterialTheme.typography.bodySmall)
                }
                // On means forwarding runs; the stored flag is "paused", so the switch shows its opposite.
                Switch(
                    checked = !state.paused,
                    onCheckedChange = { on -> onPausedChange(!on) },
                    modifier = Modifier.semantics { contentDescription = switchLabel },
                )
            }
            if (header == ForwardedHeader.NO_RULE) {
                TextButton(onClick = onManageRules, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.nav_fwd_manage_rules))
                }
            }
        }
    }
}

@Composable
private fun headerColors(header: ForwardedHeader): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    return when (header) {
        ForwardedHeader.PAUSED -> colors.errorContainer to colors.onErrorContainer
        ForwardedHeader.NO_RULE, ForwardedHeader.LIMIT -> colors.tertiaryContainer to colors.onTertiaryContainer
        ForwardedHeader.ON -> colors.secondaryContainer to colors.onSecondaryContainer
    }
}

@Composable
private fun headerTitle(header: ForwardedHeader, state: ForwardedUiState): String = when (header) {
    ForwardedHeader.PAUSED -> stringResource(R.string.nav_fwd_status_paused)
    ForwardedHeader.NO_RULE -> stringResource(R.string.nav_fwd_status_no_rule)
    ForwardedHeader.LIMIT -> stringResource(R.string.fwdfix_status_limit)
    ForwardedHeader.ON -> pluralStringResource(
        R.plurals.nav_fwd_status_on, state.enabledRules, localizedNumber(state.enabledRules, uiLocale()),
    )
}

/** The second line; null shows the pause switch's label. */
@Composable
private fun headerHint(header: ForwardedHeader, state: ForwardedUiState, now: Long): String? = when (header) {
    ForwardedHeader.PAUSED -> stringResource(R.string.nav_fwd_paused_hint)
    ForwardedHeader.LIMIT -> stringResource(
        R.string.fwdfix_limit_hint,
        localizedNumber(state.sentToday(now), uiLocale()),
        localizedNumber(state.dailyLimit, uiLocale()),
    )
    ForwardedHeader.NO_RULE, ForwardedHeader.ON -> null
}

@Composable
private fun statusColors(status: ForwardStatus): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    return when (status) {
        ForwardStatus.SENT -> colors.secondaryContainer to colors.onSecondaryContainer
        ForwardStatus.PENDING -> colors.surfaceVariant to colors.onSurfaceVariant
        ForwardStatus.FAILED -> colors.errorContainer to colors.onErrorContainer
        ForwardStatus.SKIPPED -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
}

@Composable
private fun statusText(status: ForwardStatus): String = stringResource(
    when (status) {
        ForwardStatus.SENT -> R.string.nav_fwd_status_sent
        ForwardStatus.PENDING -> R.string.nav_fwd_status_pending
        ForwardStatus.FAILED -> R.string.nav_fwd_status_failed
        ForwardStatus.SKIPPED -> R.string.fwdfix_status_skipped
    },
)

/** A forward's status: sent, pending, failed, or skipped by a limit. */
@Composable
internal fun StatusChip(status: ForwardStatus) {
    val (container, content) = statusColors(status)
    InfoChip(
        text = statusText(status),
        container = container,
        content = content,
        icon = when (status) {
            ForwardStatus.SENT -> Symbols.Check
            ForwardStatus.PENDING -> Symbols.Schedule
            ForwardStatus.FAILED -> Symbols.Error
            ForwardStatus.SKIPPED -> Symbols.Block
        },
    )
}
