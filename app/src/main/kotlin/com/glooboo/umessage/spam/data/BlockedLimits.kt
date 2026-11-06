package com.glooboo.umessage.spam.data

/** Size caps applied before a blocked message is stored, so hostile senders cannot bloat the database. */
object BlockedLimits {
    const val MAX_BODY_CHARS = 10_000
    const val MAX_ADDRESS_CHARS = 200

    /** Two blocked rows with the same address and body this close together are the same message delivered twice. */
    const val DUPLICATE_WINDOW_MS = 10_000L

    fun limitBody(body: String): String =
        if (body.length <= MAX_BODY_CHARS) body else body.take(MAX_BODY_CHARS - 1) + "\u2026"

    fun limitAddress(address: String): String = address.take(MAX_ADDRESS_CHARS)
}
