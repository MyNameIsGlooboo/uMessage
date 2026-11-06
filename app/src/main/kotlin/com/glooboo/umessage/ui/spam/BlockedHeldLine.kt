package com.glooboo.umessage.ui.spam

import androidx.annotation.StringRes
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason

/** U06: the "Held: ..." line above a message of the sender thread, from the reason the filter logged for it. */
@StringRes
internal fun heldLineText(message: BlockedMessage): Int = when (message.reason) {
    DecisionReason.UNKNOWN_SENDER ->
        if (BlockedHints.hasLink(message.body)) R.string.ux8c_held_unknown_link else R.string.ux8c_held_unknown
    DecisionReason.FOREIGN_NUMBER -> R.string.ux8c_held_foreign
    DecisionReason.BLOCK_RULE -> R.string.ux8c_held_rule
    else -> R.string.ux8c_held_other
}

/** The ids of the messages that start a run with the same held line, oldest first, so one reason is said once. */
internal fun heldLineShownFor(messages: List<BlockedMessage>): Set<Long> {
    val shown = HashSet<Long>()
    var previous: Int? = null
    messages.sortedBy { it.receivedAt }.forEach { message ->
        val line = heldLineText(message)
        if (line != previous) shown += message.id
        previous = line
    }
    return shown
}
