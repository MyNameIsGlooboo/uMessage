package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.helpers.NotifyMode
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.UnknownInboxNotify

/** I03: how a message that reached the inbox is announced, from why the filter let it in. */
object UnknownNotifyPolicy {

    /** A saved contact, an allow rule or someone the user has written to: a notification with links is safe. */
    fun isKnown(reason: DecisionReason?): Boolean =
        reason == DecisionReason.CONTACT || reason == DecisionReason.ALLOW_RULE ||
            reason == DecisionReason.KNOWN_CONVERSATION

    /** Let in only because the filter was off or could not tell: the "unknown sender in the inbox" case. */
    fun isUnknown(reason: DecisionReason?): Boolean =
        reason == DecisionReason.MODE_OFF || reason == DecisionReason.CONTACTS_UNAVAILABLE

    /** A verification code is never silenced, whatever the setting. */
    fun modeFor(reason: DecisionReason?, setting: UnknownInboxNotify, hasCode: Boolean): NotifyMode = when {
        hasCode || !isUnknown(reason) -> NotifyMode.NORMAL
        setting == UnknownInboxNotify.SILENT -> NotifyMode.SILENT
        setting == UnknownInboxNotify.NONE -> NotifyMode.NONE
        else -> NotifyMode.NORMAL
    }
}
