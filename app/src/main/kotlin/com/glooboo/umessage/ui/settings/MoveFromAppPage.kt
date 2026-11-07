package com.glooboo.umessage.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.localizeDigits
import com.glooboo.umessage.ui.components.uiLocale

/** The steps of the "Move from another app" guide, in order. */
internal val moveFromAppSteps = listOf(
    R.string.move_from_app_step1,
    R.string.move_from_app_step2,
    R.string.move_from_app_step3,
)

/**
 * R10: a guide for people coming from another SMS app. There is no cloud sync and no network code here: the old app
 * makes a backup file (for example SMS Backup & Restore, saved to Google Drive or to the phone), and the button opens
 * the same file picker as Backup and restore > Restore > Messages, which reads that XML format.
 */
@Composable
internal fun MoveFromAppPage(onImport: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.move_from_app_intro), style = MaterialTheme.typography.bodyLarge)
        moveFromAppSteps.forEachIndexed { index, text -> MoveStep(index + 1, stringResource(text)) }
        Text(
            stringResource(R.string.backup_drive_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.move_from_app_format),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onImport,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.import_messages)) }
    }
}

@Composable
private fun MoveStep(number: Int, text: String) {
    val badge = localizeDigits(number.toString(), uiLocale())
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                badge,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}
