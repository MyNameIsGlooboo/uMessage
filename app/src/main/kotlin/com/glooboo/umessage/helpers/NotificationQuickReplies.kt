package com.glooboo.umessage.helpers

import android.content.Context

/** The saved texts offered as reply choices under a message notification (I10). */
object NotificationQuickReplies {

    /** Up to [SavedTexts.QUICK_REPLY_MAX_COUNT] short saved texts, or null when there is none (no choices shown). */
    fun choices(context: Context): Array<CharSequence>? =
        SavedTexts(context.composerPrefs).quickReplies().map { it.text as CharSequence }.toTypedArray()
            .takeIf { it.isNotEmpty() }
}
