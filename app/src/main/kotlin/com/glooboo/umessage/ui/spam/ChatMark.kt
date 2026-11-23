package com.glooboo.umessage.ui.spam

import android.content.Context
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import com.glooboo.umessage.extensions.conversationsDB

/**
 * J24: where a chat's newest text stood when the user confirmed a delete: the highest SMS id and the highest MMS id
 * of its thread in the phone's store, and whether it was in Spam. A delete waits for its Undo window, and a text that
 * comes in meanwhile (a verification code that moves the chat to Messages) must never go with it, unseen.
 */
data class ChatMark(val smsId: Long, val mmsId: Long, val inSpam: Boolean) {
    companion object {
        /** The mark of [threadId] now; null when the store could not be read. Blocking. */
        fun read(context: Context, threadId: Long): ChatMark? = runCatching {
            ChatMark(
                newestId(context, Sms.CONTENT_URI, threadId) ?: return@runCatching null,
                newestId(context, Mms.CONTENT_URI, threadId) ?: return@runCatching null,
                inSpam = context.conversationsDB.getSpamThread(threadId) != null,
            )
        }.getOrNull()

        private fun newestId(context: Context, uri: android.net.Uri, threadId: Long): Long? =
            context.contentResolver.query(
                uri, arrayOf(Sms._ID), "${Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), "${Sms._ID} DESC LIMIT 1",
            )?.use { if (it.moveToFirst()) it.getLong(0) else 0L }

        /** The marks of [ids] now. Blocking. */
        fun readAll(context: Context, ids: Collection<Long>): Map<Long, ChatMark?> =
            ids.associateWith { read(context, it) }

        /** [ids] split into those that may go ([safeToDelete], read now) and those kept. Blocking. */
        fun split(
            context: Context,
            ids: List<Long>,
            confirmed: Map<Long, ChatMark?>,
            needsSpam: Boolean,
        ): Pair<List<Long>, List<Long>> = ids.partition { safeToDelete(confirmed[it], read(context, it), needsSpam) }

        /**
         * Whether the chat may be deleted: nothing newer than [confirmed] is in it, and (when [needsSpam]) it is still
         * in Spam. A mark that could not be read at either time says no: the chat is kept, never lost.
         */
        fun safeToDelete(confirmed: ChatMark?, now: ChatMark?, needsSpam: Boolean): Boolean =
            confirmed != null && now != null && now.smsId <= confirmed.smsId && now.mmsId <= confirmed.mmsId &&
                (!needsSpam || now.inSpam)
    }
}
