package com.glooboo.umessage.ui.thread

import android.content.Intent
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.glooboo.umessage.R
import com.glooboo.umessage.export.ConversationExportJob
import com.glooboo.umessage.ui.components.showComposeDialog
import com.glooboo.umessage.ui.lock.LockScope
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
    /** Builds the file; tests pass their own. */
    private val runJob: (ExportRun, ThreadExportOptions) -> ConversationExportJob.Result = { run, options ->
        ConversationExportJob(activity)
            .run(run.threadId, run.title, run.numbers, options.format, options.range, run.cancelled::get)
    },
) {
    /**
     * One export, from the tap to its end. J5-10: each has its own [done], so an export still winding down never
     * closes the progress of a later one; [stoppedByLeave] when its dialog was closed by the chat, not by Cancel.
     */
    class ExportRun(val threadId: Long, val title: String, val numbers: List<String>) {
        val cancelled = AtomicBoolean(false)
        val stoppedByLeave = AtomicBoolean(false)
        internal val done: MutableState<Boolean> = mutableStateOf(false)
    }

    fun start() {
        ThreadExportSheet(activity) { export(it) }
    }

    internal fun export(options: ThreadExportOptions): ExportRun {
        val run = ExportRun(threadId(), title(), numbers())
        showProgress(run)
        ensureBackgroundThread {
            val result = try {
                runJob(run, options)
            } catch (_: Exception) {
                null
            }
            activity.runOnUiThread {
                run.done.value = true
                finish(result, run)
            }
        }
        return run
    }

    private fun finish(result: ConversationExportJob.Result?, run: ExportRun) {
        when {
            run.stoppedByLeave.get() -> whenBack { showMessage(activity.getString(R.string.export_conv_stopped)) }
            run.cancelled.get() || result is ConversationExportJob.Result.Cancelled -> Unit
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
        com.glooboo.umessage.helpers.CacheCleanup.deleteAfterShare(done.file)
    }

    /**
     * J5-10, R3-6: a stopped export is told about once the chat shows again: in front, past the app lock, and not
     * covered by a gate (a hidden or Spam chat). Not on the resume that puts the lock screen in front.
     */
    private fun whenBack(block: () -> Unit) {
        if (chatShows()) {
            block()
            return
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                if (!chatShows()) return
                owner.lifecycle.removeObserver(this)
                block()
            }
        })
    }

    /** The screen is resumed and its content is drawn: the lock's cover and the gates hide the content view. */
    private fun chatShows(): Boolean =
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !LockScope.isLockedNow(activity) &&
            activity.findViewById<View>(android.R.id.content)?.visibility == View.VISIBLE

    private fun showProgress(run: ExportRun) {
        val cancelled = run.cancelled
        activity.showComposeDialog { dismiss ->
            val done = run.done.value
            LaunchedEffect(done) { if (done) dismiss() }
            // K2-6: closed before the export ended (the chat left under the app lock, or another chat loaded in
            // place): the export stops, so no share sheet opens later on its own. Cancel set the mark first.
            DisposableEffect(Unit) {
                onDispose { if (!run.done.value && !cancelled.getAndSet(true)) run.stoppedByLeave.set(true) }
            }
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
