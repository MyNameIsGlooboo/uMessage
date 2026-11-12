package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.ui.thread.isSameSender
import com.glooboo.umessage.ui.thread.planSpamReport

/** spamui-21: when the "Always allow" button of the Why-blocked dialog is offered for a sender. */
internal object AlwaysAllowPolicy {

    /**
     * True when no allow rule names [address] yet and the sender is not a name anyone can fake (see
     * [SenderBlocking.isSpoofable]; "Not spam" adds no rule for those either). Uses phone-number matching, so call it
     * off the main thread.
     */
    fun canOffer(rules: Collection<SpamRule>, address: String): Boolean {
        if (address.isBlank() || SenderBlocking.isSpoofable(address)) return false
        val type = planSpamReport(emptyList(), address).type
        return rules.none { it.kind == ListKind.ALLOW && it.type == type && isSameSender(it.pattern, address, type) }
    }
}
