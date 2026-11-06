package com.glooboo.umessage.helpers

import android.content.Context
import com.glooboo.umessage.helpers.smartreply.SmartReply
import com.glooboo.umessage.helpers.smartreply.SmartReplyPolicy
import com.glooboo.umessage.helpers.smartreply.SmartReplyTables
import com.glooboo.umessage.helpers.smartreply.smartReplyPrefs

/**
 * Suggested replies as the chips under a message notification (the "Okay / Thanks" row of Google Messages). They are
 * the choices of the notification's Reply input, which Android draws as chips; a tap sends the text through the
 * same quick-reply path as typing in Reply. No extra action buttons are used.
 */
internal class NotificationSmartReplies(private val context: Context) {

    /** What the notification is, as far as the gating needs to know. */
    data class Gate(
        val isMms: Boolean,
        val isGroup: Boolean,
        val knownSender: Boolean,
        val hiddenChat: Boolean,
    )

    /** The chip texts for [body], or none when the gating says no or the matcher is unsure. */
    fun replies(address: String, body: String, gate: Gate, seed: Long = 0L): List<String> {
        val match = if (offered(address, body, gate)) matchOf(body, seed) else null
        return pick(match?.replies.orEmpty(), MAX_CHIPS)
    }

    private fun offered(address: String, body: String, gate: Gate): Boolean {
        val prefs = runCatching { context.smartReplyPrefs }.getOrNull() ?: return false
        return shouldOffer(prefs.enabled, prefs.notificationsEnabled, gate, address, body, System.currentTimeMillis())
    }

    private fun matchOf(body: String, seed: Long): SmartReply? {
        val language = context.resources.configuration.locales[0].language
        return runCatching { SmartReplyTables.matcher(context).match(body, language, seed) }.getOrNull()
    }

    companion object {
        const val MAX_CHIPS = 3

        /** The best replies first, no blanks or repeats, at most [max]. */
        fun pick(replies: List<String>, max: Int): List<String> =
            replies.filter { it.isNotBlank() }.distinct().take(max.coerceAtLeast(0))

        /** The checks that need no matcher; the in-thread chips apply the same [SmartReplyPolicy]. */
        fun shouldOffer(
            smartRepliesOn: Boolean,
            inNotificationsOn: Boolean,
            gate: Gate,
            address: String,
            body: String,
            nowMillis: Long,
        ): Boolean {
            if (!smartRepliesOn || !inNotificationsOn) return false
            if (gate.isMms || gate.hiddenChat) return false
            val candidate = SmartReplyPolicy.Candidate(
                address = address,
                body = body,
                receivedMillis = nowMillis,
                isReceived = true,
                isGroup = gate.isGroup,
                knownSender = gate.knownSender,
            )
            return SmartReplyPolicy.allows(candidate, nowMillis)
        }
    }
}
