package com.glooboo.umessage.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.BackupJob

/** Progress of a running backup or restore, shared by the export and import dialogs. */
@Composable
internal fun BackupRunningContent(state: BackupJob.State.Running) {
    val percent = state.fraction?.let { (it * 100).toInt() }
    Column {
        if (state.fraction != null) {
            LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp))
        }
        val text = when (state.kind) {
            BackupJob.Kind.IMPORT -> if (percent != null) {
                pluralStringResource(R.plurals.backup_progress_import_percent, state.count, state.count, percent)
            } else {
                pluralStringResource(R.plurals.backup_progress_import, state.count, state.count)
            }

            BackupJob.Kind.EXPORT -> if (percent != null) {
                pluralStringResource(R.plurals.backup_progress_export_percent, state.count, state.count, percent)
            } else {
                pluralStringResource(R.plurals.backup_progress_export, state.count, state.count)
            }
        }
        Text(text)
        Text(
            stringResource(R.string.backup_runs_in_background),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** A result summary, scrollable because a restore with many problems can list several reasons. */
@Composable
internal fun BackupResultContent(text: String) {
    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
        Text(text)
    }
}
