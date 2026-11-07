package com.glooboo.umessage.receivers

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony.Sms
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import org.fossify.commons.extensions.getMyContactsCursor
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.getMessageRecipientAddress
import com.glooboo.umessage.extensions.getNameFromAddress
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.extensions.messagingUtils
import com.glooboo.umessage.extensions.notificationHelper
import com.glooboo.umessage.helpers.cancelFailedSendNotification
import com.glooboo.umessage.helpers.hasFailedSendNotification
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.helpers.refreshMessages
import com.glooboo.umessage.messaging.SentParts
import com.glooboo.umessage.spam.data.ForwardDelivery

/** Handles updating databases and states when a SMS message is sent. */
class SmsStatusSentReceiver : SendStatusReceiver() {

    /** What this part's report means for the message row; worked out once, used by both updates. */
    private var outcome: SentParts.Outcome? = null

    override fun updateAndroidDatabase(context: Context, intent: Intent, receiverResultCode: Int) {
        val messageUri: Uri? = intent.data
        val messagingUtils = context.messagingUtils

        val partSent = receiverResultCode == Activity.RESULT_OK
        val partCount = intent.getIntExtra(EXTRA_PART_COUNT, 1)
        val outcome = if (messageUri != null) {
            SentParts.record(context, messageUri, partSent, partCount)
        } else if (partSent) {
            SentParts.Outcome.SENT
        } else {
            SentParts.Outcome.FAILED_FIRST
        }
        this.outcome = outcome

        when (outcome) {
            SentParts.Outcome.SENT -> messagingUtils.updateSmsMessageSendingStatus(messageUri, Sms.MESSAGE_TYPE_SENT)
            SentParts.Outcome.FAILED_FIRST, SentParts.Outcome.FAILED_AGAIN ->
                messagingUtils.updateSmsMessageSendingStatus(messageUri, Sms.MESSAGE_TYPE_FAILED)

            SentParts.Outcome.STILL_SENDING -> Unit
        }
        // A copy of a group text failed: the group's summary row stops saying "Sent" (MS1).
        if (outcome.isFailure()) {
            groupRowUri(intent)?.let { messagingUtils.updateSmsMessageSendingStatus(it, Sms.MESSAGE_TYPE_FAILED) }
        }
        if (outcome == SentParts.Outcome.FAILED_FIRST) {
            messagingUtils.maybeShowErrorToast(
                resultCode = receiverResultCode,
                errorCode = intent.getIntExtra(EXTRA_ERROR_CODE, NO_ERROR_CODE),
                noDefault = intent.getBooleanExtra(EXTRA_NO_DEFAULT, false)
            )
        }
    }

    override fun updateAppDatabase(context: Context, intent: Intent, receiverResultCode: Int) {
        val messageId = messageIdOf(intent.data) ?: return
        // Already on the worker thread, inside the goAsync window.
        val type = when (outcome) {
            SentParts.Outcome.SENT -> Sms.MESSAGE_TYPE_SENT
            SentParts.Outcome.FAILED_FIRST, SentParts.Outcome.FAILED_AGAIN -> Sms.MESSAGE_TYPE_FAILED
            SentParts.Outcome.STILL_SENDING, null -> return
        }

        context.messagesDB.updateType(messageId, isMMS = false, type = type)
        val groupUri = groupRowUri(intent)
        if (outcome.isFailure()) {
            groupUri?.let { messageIdOf(it) }?.let { context.messagesDB.updateType(it, isMMS = false, type = type) }
        }
        refreshMessages()
        refreshConversations()
        // Once per message, however many of its parts failed.
        if (outcome == SentParts.Outcome.FAILED_FIRST) {
            runCatching { logFailedForward(context, intent) }
            showSendingFailedNotification(context, messageId, groupUri)
        } else if (outcome == SentParts.Outcome.SENT) {
            // A retry that went out: the old "Message not sent" notification has done its job (MS3).
            runCatching { cancelFailedNotification(context, messageId, groupUri) }
        }
    }

    private fun SentParts.Outcome?.isFailure() =
        this == SentParts.Outcome.FAILED_FIRST || this == SentParts.Outcome.FAILED_AGAIN

    private fun groupRowUri(intent: Intent): Uri? = intent.getStringExtra(EXTRA_GROUP_ROW_URI)?.toUri()

    /** The chat the notification is about: a group text's own chat, else the chat of the recipient's copy. */
    private fun threadOf(context: Context, messageId: Long, groupUri: Uri?): Long? {
        if (groupUri == null) return context.getThreadId(context.getMessageRecipientAddress(messageId))
        return context.contentResolver.query(groupUri, arrayOf(Sms.THREAD_ID), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
    }

    private fun cancelFailedNotification(context: Context, messageId: Long, groupUri: Uri?) {
        if (!context.hasFailedSendNotification()) return
        threadOf(context, messageId, groupUri)?.takeIf { it > 0 }?.let { context.cancelFailedSendNotification(it) }
    }

    /**
     * A failed forward is marked failed in the forward log, found by the log row id the sent report carries (never by
     * its number), and goes in the decision log too (with its target, never its text).
     */
    private fun logFailedForward(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val logId = intent.getLongExtra(EXTRA_FORWARD_LOG_ID, NO_LOG_ID).takeIf { it > 0 }
        context.contentResolver.query(uri, arrayOf(Sms.ADDRESS, Sms.BODY), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return
            ForwardDelivery.onRadioFailure(
                context,
                address = cursor.getString(0).orEmpty(),
                body = cursor.getString(1),
                subscriptionId = intent.getIntExtra(EXTRA_SUB_ID, -1),
                logId = logId,
            )
        }
    }

    private fun showSendingFailedNotification(context: Context, messageId: Long, groupUri: Uri?) {
        // Reading the current state is fine off the main thread; only changing it is not.
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return
        }
        // A group text: one notice for the group's chat (not one per person, each opening that person's own chat).
        val threadId = threadOf(context, messageId, groupUri) ?: return
        val recipientName = if (groupUri != null) {
            runCatching { context.conversationsDB.getConversationWithThreadId(threadId)?.title }.getOrNull().orEmpty()
        } else {
            val address = context.getMessageRecipientAddress(messageId)
            context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use {
                context.getNameFromAddress(address, it)
            }
        }
        Handler(Looper.getMainLooper()).post {
            context.notificationHelper.showSendingFailedNotification(recipientName, threadId)
        }
    }

    companion object {
        /** Long extra of the sent report: the forward-log row of a forward (H2). */
        const val EXTRA_FORWARD_LOG_ID = "com.glooboo.spamless.extra.FORWARD_LOG_ID"

        /** String extra of the sent report: the summary row of a group text sent as separate copies (MS1). */
        const val EXTRA_GROUP_ROW_URI = "com.glooboo.spamless.extra.GROUP_ROW_URI"
        private const val NO_LOG_ID = -1L
    }
}
