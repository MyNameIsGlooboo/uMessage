package com.glooboo.umessage.messaging

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

/**
 * A long SMS goes out as several parts, and the radio reports each part on its own. One message row stands for all
 * of them, so its state is worked out from every report: it is sent only when every part was sent, and failed as
 * soon as any part failed. A later part that succeeds never turns a failed message back into a sent one.
 */
object SentParts {

    /** What one part's report means for the message row. */
    enum class Outcome {
        /** Every part has been sent. */
        SENT,

        /** This is the first part to fail: mark the row failed and tell the user once. */
        FAILED_FIRST,

        /** An earlier part already failed: keep the row failed, do not tell the user again. */
        FAILED_AGAIN,

        /** Parts are still outstanding and none failed: leave the row as sending. */
        STILL_SENDING,
    }

    /** Reports seen so far for one message. */
    data class Progress(val sent: Int = 0, val failed: Int = 0) {
        fun reported() = sent + failed
    }

    /** Applies one part's report to [progress]. [partCount] is how many parts the message was split into. */
    fun reduce(progress: Progress, partSent: Boolean, partCount: Int): Pair<Progress, Outcome> {
        val next = if (partSent) progress.copy(sent = progress.sent + 1) else progress.copy(failed = progress.failed + 1)
        val outcome = when {
            !partSent -> if (progress.failed == 0) Outcome.FAILED_FIRST else Outcome.FAILED_AGAIN
            progress.failed > 0 -> Outcome.FAILED_AGAIN
            next.sent >= partCount.coerceAtLeast(1) -> Outcome.SENT
            else -> Outcome.STILL_SENDING
        }
        return next to outcome
    }

    private const val PREFS = "sms_sent_parts"
    private val lock = Any()

    /** Forgets earlier reports for [messageUri]; called right before the message (or a resend of it) goes out. */
    fun start(context: Context, messageUri: Uri) {
        synchronized(lock) {
            // Written synchronously: the first part's report can arrive on another thread right after this.
            prefs(context).edit(commit = true) { remove(messageUri.toString()) }
        }
    }

    /** Records one part's report for [messageUri] and returns what it means for the message row. */
    fun record(context: Context, messageUri: Uri, partSent: Boolean, partCount: Int): Outcome {
        if (partCount <= 1) {
            return if (partSent) Outcome.SENT else Outcome.FAILED_FIRST
        }
        synchronized(lock) {
            val prefs = prefs(context)
            val key = messageUri.toString()
            val (next, outcome) = reduce(decode(prefs.getString(key, null)), partSent, partCount)
            prefs.edit(commit = true) {
                if (next.reported() >= partCount) remove(key) else putString(key, "${next.sent},${next.failed}")
            }
            return outcome
        }
    }

    private fun decode(value: String?): Progress {
        val parts = value?.split(',') ?: return Progress()
        return Progress(
            sent = parts.getOrNull(0)?.toIntOrNull() ?: 0,
            failed = parts.getOrNull(1)?.toIntOrNull() ?: 0
        )
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
