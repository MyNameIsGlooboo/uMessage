package com.glooboo.umessage.ui.spam

import android.content.res.Resources
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
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.localizedNumber
import com.glooboo.umessage.ui.components.uiLocale

/** Whether the Spam tab shows the week's line: only with texts to count, and never on the empty tab. */
internal fun spamWeekLineShown(count: Int, tabEmpty: Boolean): Boolean = count > 0 && !tabEmpty

/** "12 texts went to Spam in the last 7 days"; [countText] is the count in the user's digits. */
internal fun spamWeekLineText(resources: Resources, count: Int, countText: String): String =
    resources.getQuantityString(R.plurals.spam_week_line, count, countText)

/**
 * The one quiet line under the Spam tab's strips: how many texts went to Spam in the last 7 days. It is not a button;
 * the tab's menu opens the Spam settings. It grows with the font size.
 */
@Composable
internal fun BlockedLine(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val colors = MaterialTheme.colorScheme
    val text = spamWeekLineText(LocalResources.current, count, localizedNumber(count, uiLocale()))
    Row(
        modifier.fillMaxWidth().heightIn(min = LINE_HEIGHT).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_spam_shield_check),
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

private val LINE_HEIGHT = 40.dp
