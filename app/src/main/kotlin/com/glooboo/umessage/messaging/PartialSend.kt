package com.glooboo.umessage.messaging

import android.content.Context
import androidx.core.content.edit
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageKey

/**
 * This scheduled message as it stands after [outcome] went out in part: only the attachments that were not handed off
 * are left, and the text is gone when it already went with the last attachment. Saved back to the held row, so a retry
 * sends only what the recipient does not have yet.
 */
fun Message.withUnsentOnly(outcome: SendOutcome): Message = copy(
    body = if (outcome.textSent) "" else body,
    attachment = attachment?.copy(attachments = ArrayList(outcome.remainingAttachments)),
)

/**
 * What an earlier Retry of one failed row (several attachments) already handed off. The row itself cannot say: its
 * parts all stay in it until it is deleted, and it is deleted only once everything went out, so a second Retry after
 * a partial one would send the delivered pictures again. [attachmentUris] are the delivered attachments, [textSent]
 * whether the text went with the last one.
 */
data class ResendLedger(val attachmentUris: Set<String> = emptySet(), val textSent: Boolean = false) {

    /** The attachments in [all] this ledger has not delivered yet. */
    fun undelivered(all: List<Attachment>): List<Attachment> = all.filter { it.uriString !in attachmentUris }

    /** [text] to send now: nothing when it already went. */
    fun textToSend(text: String): String = if (textSent) "" else text

    /** This ledger plus what [outcome] delivered ([sentUris] are the attachments that were handed off). */
    fun plus(sentUris: List<String>, textNowSent: Boolean) =
        ResendLedger(attachmentUris + sentUris, textSent || textNowSent)

    fun encode(): String = (listOf(if (textSent) TEXT_SENT else TEXT_NOT_SENT) + attachmentUris).joinToString("\n")

    companion object {
        private const val TEXT_SENT = "1"
        private const val TEXT_NOT_SENT = "0"

        fun decode(value: String?): ResendLedger {
            val lines = value?.split('\n').orEmpty()
            if (lines.isEmpty()) return ResendLedger()
            return ResendLedger(lines.drop(1).filter { it.isNotEmpty() }.toSet(), lines[0] == TEXT_SENT)
        }
    }
}

/** Keeps a [ResendLedger] per failed provider row, between Retry taps. */
object ResendLedgers {
    private const val PREFS = "mms_resend_ledgers"
    private val lock = Any()

    private fun key(row: MessageKey) = "${if (row.isMMS) "mms" else "sms"}:${row.id}"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, row: MessageKey): ResendLedger = synchronized(lock) {
        ResendLedger.decode(prefs(context).getString(key(row), null))
    }

    fun save(context: Context, row: MessageKey, ledger: ResendLedger) = synchronized(lock) {
        prefs(context).edit(commit = true) { putString(key(row), ledger.encode()) }
    }

    fun forget(context: Context, row: MessageKey) = synchronized(lock) {
        prefs(context).edit(commit = true) { remove(key(row)) }
    }
}
