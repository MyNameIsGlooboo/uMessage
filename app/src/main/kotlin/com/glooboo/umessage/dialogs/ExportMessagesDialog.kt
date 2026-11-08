package com.glooboo.umessage.dialogs

import android.content.Context
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.activities.SimpleActivity
import com.glooboo.umessage.backup.DriveHandoff
import com.glooboo.umessage.export.ExportFile
import com.glooboo.umessage.export.HiddenBackupPolicy
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.helpers.BackupJob
import com.glooboo.umessage.helpers.BackupSummaryText
import com.glooboo.umessage.services.BackupService
import com.glooboo.umessage.ui.components.showComposeDialog
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.getCurrentFormattedDateTime
import org.fossify.commons.extensions.isAValidFilename
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import java.io.File

/**
 * Asks what to export (the file name and type, which conversations, SMS and MMS, an optional password); [callback]
 * receives the file so the host can open the system file picker, then the host calls [exportMessages] with the
 * chosen uri. The backup runs in [BackupService], so it keeps going if the dialog is hidden or the app is left; the
 * dialog shows progress and then what was saved, including anything that could not be read.
 *
 * With [toDrive] ("Save to Google Drive") the same questions are asked, but the backup is written to one file in the
 * app's cache and, when it is finished, handed to the Google Drive app ([DriveHandoff]); the file picker is the
 * fallback when Drive is not installed. A backup that finishes while the dialog is hidden is handed over from its
 * notification instead.
 */
class ExportMessagesDialog(
    private val activity: SimpleActivity,
    private val toDrive: Boolean = false,
    private val callback: (ExportFile) -> Unit,
) {
    private var driveFile: File? = null
    private var driveMime: String? = null
    private var handedOver = false
    private var skipHidden = false
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
        LaunchedEffect(done) {
            if (done != null && done.error == null) handOverOnce()
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
            done != null -> BackupResultContent(
                BackupSummaryText.export(context, done.summary, done.error) +
                    driveNote(context, toDrive, driveFile?.let(DriveHandoff::isReady) == true, finished = true)
            )
            running -> BackupRunningContent(
                job as? BackupJob.State.Running ?: BackupJob.State.Running(BackupJob.Kind.EXPORT, 0, null)
            )

            else -> Column {
                if (toDrive) {
                    Text(driveNote(context, toDrive, ready = false, finished = false), Modifier.padding(bottom = 8.dp))
                }
                ExportOptionsForm(options, onChooseConversations = {
                    ExportConversationPicker(activity, options.chosen.orEmpty()) { ids ->
                        when {
                            ids.isNotEmpty() -> options.chosen = ids
                            options.chosen == null -> options.chooseConversations = false
                        }
                    }
                })
            }
        }
    }

    /** The automatic hand-over when the backup finishes while the dialog is open: once, and only for a real file. */
    private fun handOverOnce() {
        if (handedOver || driveFile?.let(DriveHandoff::isReady) != true) return
        handedOver = true
        handOver()
    }

    /** Opens Drive's "Save to Drive" screen for the finished backup; the file stays until the daily clean-up. */
    private fun handOver() {
        val file = driveFile ?: return
        activity.appLockManager.allowOwnTrip() // going to Drive and back is not leaving the app
        val opened = DriveHandoff.handOff(activity, file, driveMime ?: "application/octet-stream")
        activity.toast(if (opened) R.string.backup_drive_finish_hint else R.string.backup_drive_missing)
    }

    /** Starts the backup: for the picked file, or (Google Drive) into the cache file that is handed over afterwards. */
    private fun proceed(file: ExportFile) {
        if (!toDrive) return callback(file)
        if (!DriveHandoff.isAvailable(activity)) {
            // No Drive app: the file picker is the way ("pick Google Drive in the file picker" says the hint).
            activity.toast(R.string.backup_drive_missing)
            return callback(file)
        }
        val target = DriveHandoff.newFile(activity, file.name)
        driveFile = target
        driveMime = file.mimeType
        exportMessages(DriveHandoff.uriFor(activity, target))
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
            finished -> if (driveFile?.let(DriveHandoff::isReady) == true) {
                TextButton(onClick = ::handOver) { Text(stringResource(R.string.backup_save_drive)) }
            }

            running -> TextButton(onClick = BackupJob::requestCancel) { Text(stringResource(R.string.backup_stop)) }
            else -> TextButton(onClick = dismiss) { Text(stringResource(R.string.screens_cancel)) }
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
            else -> startUnlessHiddenAsk(ExportFile(options.fileName, options.mimeType), skipChosen = false)
        }
    }

    /**
     * Q14: a full backup while the hidden chats are locked first asks to unlock them or to skip them; the answer
     * goes to the service as a flag, so the service never prompts.
     */
    private fun startUnlessHiddenAsk(file: ExportFile, skipChosen: Boolean) {
        val full = options.chosen == null
        when (HiddenBackupPolicy.decideNow(activity, full, skipChosen)) {
            HiddenBackupPolicy.Decision.ASK ->
                HiddenBackupPrompt.show(activity) { skip -> startUnlessHiddenAsk(file, skip) }

            HiddenBackupPolicy.Decision.SKIP -> {
                skipHidden = true
                proceed(file)
            }

            HiddenBackupPolicy.Decision.INCLUDE -> {
                skipHidden = false
                proceed(file)
            }
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
                driveFile, driveMime, skipHidden,
            )
            true
        } catch (e: Exception) {
            BackupJob.stagePassword(null)
            driveFile = null
            activity.showErrorToast(e)
            false
        }
        options.password = ""
        options.confirm = ""
    }
}

/** What the user needs to know about the Google Drive hand-over: before the backup, and once a file is ready. */
private fun driveNote(context: Context, toDrive: Boolean, ready: Boolean, finished: Boolean): String = when {
    !toDrive -> ""
    !finished -> context.getString(R.string.backup_save_drive_summary) + " " +
        context.getString(R.string.backup_drive_stays)

    ready -> "\n\n" + context.getString(R.string.backup_drive_finish_hint) + " " +
        context.getString(R.string.backup_drive_stays)

    else -> ""
}
