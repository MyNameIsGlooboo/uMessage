package com.glooboo.umessage.ui.spam

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.SpamStats
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.ui.components.localizedNumber
import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.icons.Symbols

/** Short name of an unknown-sender mode (the label of its segment). */
@StringRes
internal fun blockedModeLabel(mode: UnknownSenderMode): Int = when (mode) {
    UnknownSenderMode.STRICT -> R.string.spam_mode_strict
    UnknownSenderMode.SMART -> R.string.spam_mode_smart
    UnknownSenderMode.OFF -> R.string.spam_mode_off
}

@StringRes
private fun levelText(mode: UnknownSenderMode): Int = when (mode) {
    UnknownSenderMode.STRICT -> R.string.blocked_line_strict
    UnknownSenderMode.SMART -> R.string.blocked_line_smart
    UnknownSenderMode.OFF -> R.string.blocked_line_off
}

/** "12 stopped this week", or "Nothing stopped this week". */
private fun stoppedText(resources: Resources, stats: SpamStats, countText: String): String {
    val count = stats.blockedLast7Days
    return if (count > 0) {
        resources.getQuantityString(R.plurals.w7d_banner_stopped, count, countText)
    } else {
        resources.getString(R.string.w7d_banner_none)
    }
}

/** What the line shows: "Smart - 12 stopped this week". */
internal fun blockedLineText(
    resources: Resources,
    stats: SpamStats,
    mode: UnknownSenderMode,
    countText: String,
): String = resources.getString(
    R.string.blocked_line_join, resources.getString(blockedModeLabel(mode)), stoppedText(resources, stats, countText),
)

/** What a screen reader says: "Smart protection, 12 stopped this week". */
internal fun blockedLineDescription(
    resources: Resources,
    stats: SpamStats,
    mode: UnknownSenderMode,
    countText: String,
): String = resources.getString(
    R.string.blocked_line_cd, resources.getString(levelText(mode)), stoppedText(resources, stats, countText),
)

/**
 * The one thin line under the Blocked tab's top bar (layout B): shield, the level and the week's count, with a
 * chevron. The whole line opens the Spam protection page, where the level is changed. It grows with the font size.
 */
@Composable
fun BlockedLine(
    stats: SpamStats,
    mode: UnknownSenderMode,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val resources = LocalResources.current
    val countText = localizedNumber(stats.blockedLast7Days, uiLocale())
    val text = blockedLineText(resources, stats, mode, countText)
    val description = blockedLineDescription(resources, stats, mode, countText)
    Row(
        modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(R.string.spam_blocked_open_settings),
                role = Role.Button,
                onClick = onOpenSettings,
            )
            .heightIn(min = LINE_HEIGHT)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_spam_shield_check),
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = colors.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(Symbols.ChevronRight, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
    }
}

private val LINE_HEIGHT = 40.dp
