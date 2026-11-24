package com.glooboo.umessage.extensions

import android.content.Context
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms

/**
 * J24: where a chat's newest text stood when the user confirmed a delete: the highest SMS id and the highest MMS id
 * of its thread in the phone's store (0 for none), and whether it was in Spam. A delete waits for its Undo window, and
 * a text that comes in meanwhile (a verification code that moves the chat to Messages) must never go with it, unseen:
 * the delete takes only the rows up to these ids ([deleteConversation]).
 */
data class ChatMark(val smsId: Long, val mmsId: Long, val inSpam: Boolean) {

    /** Why a chat was kept instead of deleted. */
    enum class Kept { NEWER_TEXT, LEFT_SPAM, UNREADABLE }

    companion object {
        /** The mark of [threadId] now; null when the store could not be read. Blocking. */
        fun read(context: Context, threadId: Long): ChatMark? = runCatching {
            ChatMark(
                newestId(context, Sms.CONTENT_URI, threadId) ?: return@runCatching null,
                newestId(context, Mms.CONTENT_URI, threadId) ?: return@runCatching null,
                inSpam = context.conversationsDB.isInSpam(threadId),
            )
        }.getOrNull()

        /** The highest `_id` of [threadId] in [uri], 0 for an empty thread, null when the provider gave no cursor. */
        private fun newestId(context: Context, uri: Uri, threadId: Long): Long? =
            context.contentResolver.query(
                uri, arrayOf(Sms._ID), "${Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), "${Sms._ID} DESC",
            )?.use { if (it.moveToFirst()) it.getLong(0) else 0L }

        /**
         * Why a chat that was marked [confirmed] must not be deleted now ([now] is its mark read again), or null when
         * it may go. A mark that could not be read at either time keeps the chat: never lost. A newer text is not
         * judged here: the delete takes only the rows up to [confirmed] and reports it ([DeleteResult.NEWER_TEXT]).
         */
        fun keepReason(confirmed: ChatMark?, now: ChatMark?, needsSpam: Boolean): Kept? = when {
            confirmed == null || now == null -> Kept.UNREADABLE
            needsSpam && !now.inSpam -> Kept.LEFT_SPAM
            else -> null
        }
    }
}
