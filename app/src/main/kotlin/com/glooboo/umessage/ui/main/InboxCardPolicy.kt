package com.glooboo.umessage.ui.main

/** The cards that can sit above the inbox's first conversation, in the order they get the one slot. */
internal enum class InboxCard { SpamHeld, Checklist, CodeCleanup }

/** U03: the inbox shows at most one card, so the first conversation is on the first screen. */
internal object InboxCardPolicy {

    /** The card to show: held spam first, then the set-up checklist, then the read-codes question; null for none. */
    fun pick(spamHeldDue: Boolean, checklistDue: Boolean, cleanupDue: Boolean): InboxCard? = when {
        spamHeldDue -> InboxCard.SpamHeld
        checklistDue -> InboxCard.Checklist
        cleanupDue -> InboxCard.CodeCleanup
        else -> null
    }
}
