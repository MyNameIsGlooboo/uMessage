package com.glooboo.umessage.ui.thread

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.glooboo.umessage.R
import com.glooboo.umessage.export.ConversationExportJob
import com.glooboo.umessage.ui.components.showComposeDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.helpers.ensureBackgroundThread
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The thread menu's "Export conversation": asks PDF or text and which dates, builds the file in the background with
 * a Cancel button, then hands it to the share sheet. Nothing is kept: the file lives in the app cache until the next
 * export replaces it.
 */
class ThreadExportController(
    private val activity: BaseSimpleActivity,
    private val threadId: () -> Long,
    private val title: () -> String,
    private val numbers: () -> List<String>,
    /** Shows a short note to the user (a snackbar). */
    private val showMessage: (String) -> Unit,
) {
    private var progressDone by mutableStateOf(false)

    fun start() {
        ThreadExportSheet(activity, ::export)
    }

    private fun export(options: ThreadExportOptions) {
        val cancelled = AtomicBoolean(false)
        progressDone = false
        showProgress(cancelled)
        val id = threadId()
        val name = title()
        val phoneNumbers = numbers()
        ensureBackgroundThread {
            val result = try {
                ConversationExportJob(activity)
                    .run(id, name, phoneNumbers, options.format, options.range, cancelled::get)
            } catch (_: Exception) {
                null
            }
            activity.runOnUiThread {
                progressDone = true
                finish(result, cancelled.get())
            }
        }
    }

    private fun finish(result: ConversationExportJob.Result?, cancelled: Boolean) {
        when {
            cancelled || result is ConversationExportJob.Result.Cancelled -> Unit
            result == null -> showMessage(activity.getString(R.string.export_conv_failed))
            result is ConversationExportJob.Result.Empty ->
                showMessage(activity.getString(R.string.export_conv_nothing))

            result is ConversationExportJob.Result.Done -> share(result)
        }
    }

    private fun share(done: ConversationExportJob.Result.Done) {
        val total = done.export.count
        val count = activity.resources.getQuantityString(R.plurals.export_pdf_count, total, total)
        val subject = activity.getString(R.string.export_conv_subject)
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.provider", done.file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(done.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(
                Intent.EXTRA_TEXT,
                activity.getString(R.string.export_conv_share_text, done.export.title, count, done.export.sha256),
            )
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(send, subject))
    }

    private fun showProgress(cancelled: AtomicBoolean) {
        activity.showComposeDialog { dismiss ->
            LaunchedEffect(progressDone) { if (progressDone) dismiss() }
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.export_conv_title)) },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.export_conv_preparing))
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(
                        onClick = {
                            cancelled.set(true)
                            dismiss()
                        },
                    ) { Text(stringResource(R.string.screens_cancel)) }
                },
            )
        }
    }
}
