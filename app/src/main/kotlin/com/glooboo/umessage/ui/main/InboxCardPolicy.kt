package com.glooboo.umessage.ui.main

/** The cards that can sit above the inbox's first conversation, in the order they get the one slot. */
internal enum class InboxCard { SpamMoved, Checklist, CodeCleanup }

/** U03: the inbox shows at most one card, so the first conversation is on the first screen. */
internal object InboxCardPolicy {

    /**
     * The card to show: provider truth's "N chats are now in Spam" first (U13: after every first sort, until tapped),
     * then the set-up checklist, then the read-codes question; null for none.
     */
    fun pick(
        checklistDue: Boolean,
        cleanupDue: Boolean,
        spamMovedDue: Boolean = false,
    ): InboxCard? = when {
        spamMovedDue -> InboxCard.SpamMoved
        checklistDue -> InboxCard.Checklist
        cleanupDue -> InboxCard.CodeCleanup
        else -> null
    }
}
