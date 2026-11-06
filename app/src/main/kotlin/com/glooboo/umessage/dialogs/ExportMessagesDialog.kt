package com.glooboo.umessage.dialogs

import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.activities.SimpleActivity
import com.glooboo.umessage.export.ExportFile
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.helpers.BackupJob
import com.glooboo.umessage.helpers.BackupSummaryText
import com.glooboo.umessage.services.BackupService
import com.glooboo.umessage.ui.components.showComposeDialog
import org.fossify.commons.extensions.getCurrentFormattedDateTime
import org.fossify.commons.extensions.isAValidFilename
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast

/**
 * Asks what to export (the file name and type, which conversations, SMS and MMS, an optional password); [callback]
 * receives the file so the host can open the system file picker, then the host calls [exportMessages] with the
 * chosen uri. The backup runs in [BackupService], so it keeps going if the dialog is hidden or the app is left; the
 * dialog shows progress and then what was saved, including anything that could not be read.
 */
class ExportMessagesDialog(
    private val activity: SimpleActivity,
    private val callback: (ExportFile) -> Unit,
) {
    private val config = activity.config
    private var started by mutableStateOf(false)
    private val options = ExportOptionsState(
        initialName = "${activity.getString(R.string.messages)}_${activity.getCurrentFormattedDateTime()}",
        initialSms = config.exportSms,
        initialMms = config.exportMms,
    )

    init {
        activity.showComposeDialog { dismiss -> Content(dismiss) }
    }

    @Composable
    private fun Content(dismiss: () -> Unit) {
        val context = LocalContext.current
        val job by BackupJob.state.collectAsState()
        val done = (job as? BackupJob.State.ExportDone)?.takeIf { started }
        val running = started && done == null
        val close = {
            if (done != null) BackupJob.acknowledge()
            dismiss()
        }
        AlertDialog(
            onDismissRequest = { if (!running) close() },
            title = {
                Text(
                    done?.let { BackupSummaryText.exportTitle(context, it.summary, it.error) }
                        ?: stringResource(R.string.export_messages)
                )
            },
            text = { Body(done, running, job) },
            confirmButton = { ConfirmButton(done != null, running, close, dismiss) },
            dismissButton = { DismissButton(done != null, running, dismiss) },
        )
    }

    @Composable
    private fun Body(done: BackupJob.State.ExportDone?, running: Boolean, job: BackupJob.State) {
        val context = LocalContext.current
        when {
            done != null -> BackupResultContent(BackupSummaryText.export(context, done.summary, done.error))
            running -> BackupRunningContent(
                job as? BackupJob.State.Running ?: BackupJob.State.Running(BackupJob.Kind.EXPORT, 0, null)
            )

            else -> ExportOptionsForm(options, onChooseConversations = ::chooseConversations)
        }
    }

    @Composable
    private fun ConfirmButton(finished: Boolean, running: Boolean, close: () -> Unit, dismiss: () -> Unit) {
        when {
            finished -> TextButton(onClick = close) { Text(stringResource(R.string.screens_ok)) }
            running -> TextButton(onClick = dismiss) { Text(stringResource(R.string.backup_hide)) }
            else -> TextButton(onClick = ::confirm) { Text(stringResource(R.string.screens_ok)) }
        }
    }

    @Composable
    private fun DismissButton(finished: Boolean, running: Boolean, dismiss: () -> Unit) {
        when {
            finished -> Unit
            running -> TextButton(onClick = BackupJob::requestCancel) { Text(stringResource(R.string.backup_stop)) }
            else -> TextButton(onClick = dismiss) { Text(stringResource(R.string.screens_cancel)) }
        }
    }

    private fun chooseConversations() {
        ExportConversationPicker(activity, options.chosen.orEmpty()) { ids ->
            when {
                ids.isNotEmpty() -> options.chosen = ids
                options.chosen == null -> options.chooseConversations = false
            }
        }
    }

    private fun confirm() {
        config.exportSms = options.sms
        config.exportMms = options.mms
        val problem = options.passwordProblem
        when {
            !options.sms && !options.mms -> activity.toast(R.string.no_option_selected)
            BackupJob.isRunning -> activity.toast(R.string.backup_already_running)
            options.name.isEmpty() -> activity.toast(org.fossify.commons.R.string.empty_name)
            !options.name.isAValidFilename() -> activity.toast(org.fossify.commons.R.string.invalid_name)
            options.chooseConversations && options.chosen.isNullOrEmpty() ->
                activity.toast(R.string.export_pick_nothing_chosen)

            problem != null -> activity.toast(problem)
            else -> callback(ExportFile(options.fileName, options.mimeType))
        }
    }

    fun exportMessages(uri: Uri) {
        if (BackupJob.isRunning) {
            activity.toast(R.string.backup_already_running)
            return
        }
        BackupJob.acknowledge()
        // The password goes to the service through memory, never through the intent.
        BackupJob.stagePassword(if (options.protect) options.password.toCharArray() else null)
        started = try {
            BackupService.startExport(
                activity, uri, options.sms, options.mms, options.format, options.chosen, options.protect,
            )
            true
        } catch (e: Exception) {
            BackupJob.stagePassword(null)
            activity.showErrorToast(e)
            false
        }
        options.password = ""
        options.confirm = ""
    }
}
