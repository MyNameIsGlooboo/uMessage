package com.glooboo.umessage.export

import android.content.Context
import com.glooboo.umessage.helpers.HiddenChats

/**
 * Which conversations an export may include. Chats the user keeps hidden behind the app lock are left out until the
 * hidden chats are unlocked, the same as everywhere else in the app.
 */
object ExportScope {
    /** The thread ids that must stay out of an export right now. */
    fun hiddenIds(context: Context): Set<Long> = HiddenChats.get(context).hiddenIds()

    /** [ids] without the hidden conversations. */
    fun exportable(context: Context, ids: List<Long>): List<Long> = hiddenIds(context).let { hidden ->
        if (hidden.isEmpty()) ids else ids.filterNot { it in hidden }
    }
}
