package com.glooboo.umessage.helpers

import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.engine.UnknownNotifyPolicy

/** How loud a received message's notification is. */
enum class NotifyMode { NORMAL, SILENT, NONE }

/**
 * What the pipeline knows about a received message when it asks for a notification: how loud it is, why the filter
 * let it in (null when unknown, e.g. an MMS or a failed filter) and whether the thread is a group.
 */
data class NotifyContext(
    val mode: NotifyMode = NotifyMode.NORMAL,
    val reason: DecisionReason? = null,
    val isGroup: Boolean = false,
) {
    val knownSender: Boolean get() = UnknownNotifyPolicy.isKnown(reason)
    val unknownSender: Boolean get() = UnknownNotifyPolicy.isUnknown(reason)

    companion object {
        /** No notification at all (restored or replayed messages). */
        val NONE = NotifyContext(NotifyMode.NONE)
    }
}
