package com.glooboo.umessage.ui.spam

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R

/** U18: remembers that the Blocked list's gesture tip was dismissed. */
internal object BlockedTipStore {
    private const val PREFS = "spamless_blocked_ui"
    private const val KEY = "tip_seen"

    fun seen(context: Context): Boolean =
        runCatching { context.getSharedPreferences(PREFS, 0).getBoolean(KEY, false) }.getOrDefault(false)

    fun markSeen(context: Context) {
        runCatching { context.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY, true).apply() }
    }
}

/** One line above the Blocked list that names its two hidden gestures, until the user taps "Got it". */
@Composable
internal fun BlockedTip(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(!BlockedTipStore.seen(context)) }
    if (!visible) return
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.ux8c_tip),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                BlockedTipStore.markSeen(context)
                visible = false
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.ux8c_tip_dismiss)) }
    }
}
