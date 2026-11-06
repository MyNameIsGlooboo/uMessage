package com.glooboo.umessage.ui.details

/** U15: what happens to a sender's texts. A sender is never allowed and blocked at once, so it is one choice. */
internal enum class SenderChoice { NORMAL, ALLOW, BLOCK }

/** The choice that matches today's two flags; [allowed] null means Always allow is not offered. */
internal fun senderChoice(blocked: Boolean, allowed: Boolean?): SenderChoice = when {
    blocked -> SenderChoice.BLOCK
    allowed == true -> SenderChoice.ALLOW
    else -> SenderChoice.NORMAL
}

/**
 * Moves from [current] to [target] with the two existing calls: blocking removes the sender's allow rules and allowing
 * removes the block, so one call is enough unless the target is Normal, which undoes whichever is on.
 */
internal fun applySenderChoice(
    target: SenderChoice,
    current: SenderChoice,
    onBlockedChange: (Boolean) -> Unit,
    onAllowedChange: (Boolean) -> Unit,
) {
    if (target == current) return
    when (target) {
        SenderChoice.BLOCK -> onBlockedChange(true)
        SenderChoice.ALLOW -> onAllowedChange(true)
        SenderChoice.NORMAL -> if (current == SenderChoice.BLOCK) onBlockedChange(false) else onAllowedChange(false)
    }
}
