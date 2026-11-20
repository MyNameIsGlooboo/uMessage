package com.glooboo.umessage.ui.main

/** The cards that can sit above the inbox's first conversation, in the order they get the one slot. */
internal enum class InboxCard { SpamMoved, SpamHeld, Checklist, CodeCleanup }

/** U03: the inbox shows at most one card, so the first conversation is on the first screen. */
internal object InboxCardPolicy {

    /**
     * The card to show: provider truth's "N chats are now in Spam" first (U13: after every first sort, until tapped;
     * it replaces the "Spam held" card), then held spam, then the set-up checklist, then the read-codes question;
     * null for none.
     */
    fun pick(
        spamHeldDue: Boolean,
        checklistDue: Boolean,
        cleanupDue: Boolean,
        spamMovedDue: Boolean = false,
    ): InboxCard? = when {
        spamMovedDue -> InboxCard.SpamMoved
        spamHeldDue -> InboxCard.SpamHeld
        checklistDue -> InboxCard.Checklist
        cleanupDue -> InboxCard.CodeCleanup
        else -> null
    }
}
