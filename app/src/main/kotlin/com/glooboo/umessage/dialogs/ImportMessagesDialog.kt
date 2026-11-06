package com.glooboo.umessage.dialogs

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.activities.SimpleActivity
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.helpers.BackupJob
import com.glooboo.umessage.helpers.BackupSummaryText
import com.glooboo.umessage.helpers.ImportPasswordCheck
import com.glooboo.umessage.services.BackupService
import com.glooboo.umessage.ui.components.CheckboxRow
import com.glooboo.umessage.ui.components.showComposeDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast

/**
 * Choose SMS and/or MMS and restore them from the backup at [uri]. A file protected with a password asks for it
 * first; a wrong password is said so and nothing is restored. The restore runs in [BackupService], so it keeps
 * going if the dialog is hidden or the app is left; the dialog shows its progress and then the summary of what was
 * added, skipped and failed.
 */
class ImportMessagesDialog(
    private val activity: SimpleActivity,
    private val uri: Uri,
) {
    private val config = activity.config

    init {
        activity.showComposeDialog { dismiss -> Content(dismiss) }
    }

    @Composable
    private fun Content(dismiss: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val form = remember { ImportFormState(config.importSms, config.importMms) }
        LaunchedEffect(Unit) {
            form.protectedFile = withContext(Dispatchers.IO) { ImportPasswordCheck.isProtected(activity, uri) }
        }
        val job by BackupJob.state.collectAsState()
        val done = (job as? BackupJob.State.ImportDone)?.takeIf { form.started }
        val running = form.started && done == null
        val close = {
            if (done != null) BackupJob.acknowledge()
            dismiss()
        }
        AlertDialog(
            onDismissRequest = { if (!running && !form.checking) close() },
            title = {
                Text(
                    done?.let { BackupSummaryText.importTitle(context, it.summary) }
                        ?: stringResource(R.string.import_messages)
                )
            },
            text = { Body(form, done, running, job) },
            confirmButton = {
                ConfirmButton(form, done != null, running, close, dismiss) { start(form, scope) }
            },
            dismissButton = { DismissButton(form.checking, done != null, running, dismiss) },
        )
    }

    @Composable
    private fun Body(form: ImportFormState, done: BackupJob.State.ImportDone?, running: Boolean, job: BackupJob.State) {
        val context = LocalContext.current
        when {
            done != null -> BackupResultContent(BackupSummaryText.import(context, done.summary))
            running -> BackupRunningContent(
                job as? BackupJob.State.Running ?: BackupJob.State.Running(BackupJob.Kind.IMPORT, 0, null)
            )

            else -> Column {
                CheckboxRow(stringResource(R.string.import_sms), form.sms, { form.sms = it })
                CheckboxRow(stringResource(R.string.import_mms), form.mms, { form.mms = it })
                if (form.protectedFile) {
                    PasswordAsk(
                        form.password, { form.password = it; form.wrongPassword = false },
                        form.wrongPassword, form.checking,
                    )
                }
            }
        }
    }

    @Composable
    private fun ConfirmButton(
        form: ImportFormState,
        finished: Boolean,
        running: Boolean,
        close: () -> Unit,
        dismiss: () -> Unit,
        onStart: () -> Unit,
    ) {
        when {
            finished -> TextButton(onClick = close) { Text(stringResource(R.string.screens_ok)) }
            running -> TextButton(onClick = dismiss) { Text(stringResource(R.string.backup_hide)) }
            else -> TextButton(onClick = onStart, enabled = !form.checking) {
                Text(stringResource(R.string.screens_ok))
            }
        }
    }

    @Composable
    private fun DismissButton(checking: Boolean, finished: Boolean, running: Boolean, dismiss: () -> Unit) {
        when {
            finished -> Unit
            running -> TextButton(onClick = BackupJob::requestCancel) {
                Text(stringResource(R.string.backup_stop))
            }
            else -> TextButton(onClick = dismiss, enabled = !checking) {
                Text(stringResource(R.string.screens_cancel))
            }
        }
    }

    private fun start(form: ImportFormState, scope: CoroutineScope) {
        when {
            !form.sms && !form.mms -> activity.toast(R.string.no_option_selected)
            BackupJob.isRunning -> activity.toast(R.string.backup_already_running)
            form.protectedFile && form.password.isEmpty() -> form.wrongPassword = true
            form.protectedFile -> checkPassword(form, scope)
            else -> form.started = begin(form.sms, form.mms, null)
        }
    }

    private fun checkPassword(form: ImportFormState, scope: CoroutineScope) {
        form.checking = true
        scope.launch {
            val typed = form.password.toCharArray()
            val opens = withContext(Dispatchers.IO) { ImportPasswordCheck.opens(activity, uri, typed) }
            form.checking = false
            if (opens) form.started = begin(form.sms, form.mms, form.password) else form.wrongPassword = true
        }
    }

    @Composable
    private fun PasswordAsk(
        password: String,
        onPasswordChange: (String) -> Unit,
        wrong: Boolean,
        checking: Boolean,
    ) {
        Text(
            text = stringResource(R.string.export_password_import_hint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )
        ExportPasswordField(
            value = password,
            onValueChange = onPasswordChange,
            label = stringResource(R.string.export_password_field),
            error = when {
                wrong -> stringResource(R.string.export_password_wrong)
                checking -> stringResource(R.string.export_password_checking)
                else -> null
            },
            imeAction = ImeAction.Done,
        )
    }

    /** Starts the restore; true when the service was started. A password goes to it through memory only. */
    private fun begin(sms: Boolean, mms: Boolean, password: String?): Boolean {
        config.importSms = sms
        config.importMms = mms
        BackupJob.acknowledge()
        BackupJob.stagePassword(password?.toCharArray())
        return try {
            BackupService.startImport(activity, uri, sms, mms)
            true
        } catch (e: Exception) {
            BackupJob.stagePassword(null)
            activity.showErrorToast(e)
            false
        }
    }
}
