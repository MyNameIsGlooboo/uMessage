package com.glooboo.umessage.receivers

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteException
import androidx.core.net.toUri
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.deleteMessage
import com.glooboo.umessage.extensions.getThreadParticipants
import com.glooboo.umessage.extensions.notificationHelper
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.helpers.refreshMessages
import com.glooboo.umessage.messaging.getMmsSendingError
import java.io.File

/** Handles updating databases and states when a MMS message is sent. */
class MmsSentReceiver : SendStatusReceiver() {

    override fun updateAndroidDatabase(context: Context, intent: Intent, receiverResultCode: Int) {
        val uri = intent.getStringExtra(EXTRA_CONTENT_URI)?.toUri()
        val originalResentMessageId = intent.getLongExtra(EXTRA_ORIGINAL_RESENT_MESSAGE_ID, -1L)
        val messageBox = if (receiverResultCode == Activity.RESULT_OK) {
            Telephony.Mms.MESSAGE_BOX_SENT
        } else {
            context.getMmsSendingError(
                receiverResultCode,
                intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)
            )?.let { msg ->
                context.toast(msg = msg, length = Toast.LENGTH_LONG)
            }
            Telephony.Mms.MESSAGE_BOX_FAILED
        }

        val values = ContentValues(1).apply {
            put(Telephony.Mms.MESSAGE_BOX, messageBox)
        }

        // Without the content uri there is no row to update; still clean up below.
        if (uri != null) {
            try {
                context.contentResolver.update(uri, values, null, null)
            } catch (e: SQLiteException) {
                context.showErrorToast(e)
            }
        }

        if (messageBox == Telephony.Mms.MESSAGE_BOX_FAILED && uri != null) {
            showSendingFailedNotification(context, uri)
        }

        // In case of resent message, delete original to prevent duplication
        if (originalResentMessageId != -1L) {
            context.deleteMessage(originalResentMessageId, true)
        }

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        if (filePath != null) {
            File(filePath).delete()
        }
    }

    override fun updateAppDatabase(context: Context, intent: Intent, receiverResultCode: Int) {
        refreshMessages()
        refreshConversations()
    }

    /** Like a failed SMS: the toast is easy to miss after leaving the app, so a notification says it too. */
    private fun showSendingFailedNotification(context: Context, uri: Uri) {
        // Reading the current state is fine off the main thread; only changing it is not.
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return
        }
        val threadId = try {
            context.contentResolver.query(uri, arrayOf(Telephony.Mms.THREAD_ID), null, null, null)?.use {
                if (it.moveToFirst()) it.getLong(0) else null
            }
        } catch (_: Exception) {
            null
        } ?: return
        val recipientName = runCatching { context.conversationsDB.getConversationWithThreadId(threadId)?.title }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: runCatching { context.getThreadParticipants(threadId, null).joinToString { it.name } }.getOrNull()
            ?: ""
        Handler(Looper.getMainLooper()).post {
            context.notificationHelper.showSendingFailedNotification(recipientName, threadId)
        }
    }

    companion object {
        private const val EXTRA_CONTENT_URI = "content_uri"
        private const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_ORIGINAL_RESENT_MESSAGE_ID = "original_message_id"
    }
}
