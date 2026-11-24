package com.glooboo.umessage.ui.spam

import android.content.Context
import com.glooboo.umessage.extensions.ChatMark
import com.glooboo.umessage.extensions.DeleteResult
import com.glooboo.umessage.extensions.deleteConversation
import com.glooboo.umessage.spam.data.BlockedNotifier
import org.fossify.commons.extensions.notificationManager

/**
 * Deletes the chats of [marks] with [deleter], each only up to its mark. A chat whose mark could not be read, or (when
 * [now] is given, the Spam tab) that is no longer in Spam, is not given to [deleter]. Blocking.
 */
internal fun deleteMarked(
    marks: Map<Long, ChatMark?>,
    isDefault: Boolean,
    deleter: (Map<Long, ChatMark>, Boolean) -> Map<Long, DeleteResult>,
    now: ((Long) -> ChatMark?)? = null,
): DeleteReport {
    val go = LinkedHashMap<Long, ChatMark>()
    var other = 0
    marks.forEach { (id, confirmed) ->
        if (confirmed == null) {
            other++
            return@forEach
        }
        val current = if (now == null) confirmed else now(id)
        if (ChatMark.keepReason(confirmed, current, needsSpam = now != null) != null) other++ else go[id] = confirmed
    }
    val results = if (go.isEmpty()) emptyMap() else deleter(go, isDefault)
    return DeleteReport(
        deleted = results.filterValues { it == DeleteResult.DELETED }.keys,
        keptNewText = results.values.count { it == DeleteResult.NEWER_TEXT },
        keptOther = other,
        failure = results.values.firstNotNullOfOrNull { it.messageRes },
    )
}

/** Deletes each chat of [marks] from the phone's store up to its mark. A chat that went loses its notices. Blocking. */
internal fun deleteSpamChats(
    context: Context,
    marks: Map<Long, ChatMark>,
    isDefault: Boolean,
): Map<Long, DeleteResult> = marks.mapValues { (id, mark) ->
    val result = runCatching { context.deleteConversation(id, isDefault, mark) }.getOrDefault(DeleteResult.FAILED)
    if (result == DeleteResult.DELETED) {
        runCatching { context.notificationManager.cancel(id.hashCode()) }
        BlockedNotifier.cancelChat(context, id)
    }
    result
}
