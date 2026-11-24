package com.glooboo.umessage.ui.spam

/**
 * J24: what a delete of the chats marked at the confirm did: the chats that went, how many were kept because a new
 * text came in, how many were kept for another reason (left Spam, a mark that could not be read), and the message of
 * the first real failure (S14), if any.
 */
internal data class DeleteReport(
    val deleted: Set<Long>,
    val keptNewText: Int,
    val keptOther: Int,
    val failure: Int?,
)
