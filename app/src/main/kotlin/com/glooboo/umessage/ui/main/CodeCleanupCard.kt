package com.glooboo.umessage.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.helpers.RetentionRunner
import com.glooboo.umessage.helpers.cleanupPrefs
import org.fossify.commons.helpers.ensureBackgroundThread

/** The age the card offers: a week is long enough to look a code up again. */
private const val CARD_DAYS = 7

/**
 * I02: a one-time card at the top of the inbox, like the one Google Messages shows: delete read verification codes
 * after a week. It says that they go to the recycle bin; "Turn on" also switches the bin on when it is off. Gone for
 * good once answered, and not shown to anyone who already chose an age in Settings.
 */
@Composable
fun CodeCleanupCard(modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember(context) { context.cleanupPrefs }
    var visible by remember { mutableStateOf(!prefs.codeCardDismissed && prefs.codeDays == 0) }
    if (!visible) return
    val close = {
        prefs.codeCardDismissed = true
        visible = false
        onDone()
    }
    val turnOn = {
        context.config.useRecycleBin = true
        prefs.codeDays = CARD_DAYS
        close()
        ensureBackgroundThread { RetentionRunner(context).runNow(System.currentTimeMillis()) }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                stringResource(R.string.cleanup_card_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.cleanup_card_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = close) { Text(stringResource(R.string.cleanup_card_no)) }
                TextButton(onClick = turnOn) { Text(stringResource(R.string.cleanup_card_yes)) }
            }
        }
    }
}
