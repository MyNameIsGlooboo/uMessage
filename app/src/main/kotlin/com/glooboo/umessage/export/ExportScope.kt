package com.glooboo.umessage.export

import android.content.Context
import com.glooboo.umessage.helpers.HiddenChats

/**
 * Which conversations an export may include. A chosen set of chats never holds the chats the user keeps hidden behind
 * the app lock (they cannot be chosen while locked). A full backup (nothing chosen) holds every chat, hidden ones
 * included, so that a locked chat is never silently missing from the only copy of the messages (DA1).
 */
object ExportScope {
    /** The thread ids that must stay out of an export right now. */
    fun hiddenIds(context: Context): Set<Long> = HiddenChats.get(context).hiddenIds()

    /** [ids] without the hidden conversations. */
    fun exportable(context: Context, ids: List<Long>): List<Long> = hiddenIds(context).let { hidden ->
        if (hidden.isEmpty()) ids else ids.filterNot { it in hidden }
    }

    /**
     * The chats an export writes: [chosen] without the hidden ones, or every chat from [all] for a full backup. A full
     * backup leaves the hidden chats out only when [skipHidden] is set (the user chose so, see [HiddenBackupPolicy]).
     */
    fun idsFor(context: Context, chosen: List<Long>?, skipHidden: Boolean = false, all: () -> List<Long>): List<Long> =
        when {
            chosen != null -> exportable(context, chosen)
            skipHidden -> withoutChosen(all(), HiddenChats.get(context).chosenIds())
            else -> all()
        }

    /** [ids] without [hidden]. */
    fun withoutChosen(ids: List<Long>, hidden: Set<Long>): List<Long> =
        if (hidden.isEmpty()) ids else ids.filterNot { it in hidden }
}
