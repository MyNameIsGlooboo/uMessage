package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.models.Message

/**
 * Results that do not change from one message to the next, kept while a conversation is open: whether the sender is
 * a saved contact or a stranger, and the SIM header labels. Every refresh used to ask the contacts, the database and
 * the telephony service again.
 *
 * A result is valid for the [stamp] it was computed with; asking with another stamp computes it again. Contacts and
 * SIM changes call [invalidateContacts] and [invalidateSims].
 */
class ThreadLookupCache {
    private data class Entry<T>(val stamp: Any?, val value: T)

    private var contacts: Entry<Boolean>? = null
    private var stranger: Entry<Boolean>? = null
    private var sims: Entry<Any?>? = null

    /** The contacts changed: the saved-contact and stranger results are looked up again. */
    @Synchronized
    fun invalidateContacts() {
        contacts = null
        stranger = null
    }

    /** The SIM cards changed. */
    @Synchronized
    fun invalidateSims() {
        sims = null
    }

    /** Forgets everything, as when another conversation is loaded in place. */
    @Synchronized
    fun clear() {
        contacts = null
        stranger = null
        sims = null
    }

    /** Whether [number] is a saved contact: [compute] runs once per [number] until the contacts change. */
    fun isSaved(number: String, compute: () -> Boolean): Boolean {
        synchronized(this) { contacts?.takeIf { it.stamp == number }?.let { return it.value } }
        return compute().also { value -> synchronized(this) { contacts = Entry(number, value) } }
    }

    /**
     * Whether the sender is a stranger. The answer depends on [number], on [isSaved], and on whether the user has
     * [hasSent] a message to them (writing to someone makes them known), so a change of any of these asks again.
     */
    fun isStranger(number: String?, isSaved: Boolean, hasSent: Boolean, compute: () -> Boolean): Boolean {
        val stamp = Triple(number, isSaved, hasSent)
        synchronized(this) { stranger?.takeIf { it.stamp == stamp }?.let { return it.value } }
        return compute().also { value -> synchronized(this) { stranger = Entry(stamp, value) } }
    }

    /** The SIM-derived value [compute] makes, kept until [invalidateSims]. */
    @Suppress("UNCHECKED_CAST")
    fun <T> sims(compute: () -> T): T {
        synchronized(this) { sims?.let { return it.value as T } }
        return compute().also { value -> synchronized(this) { sims = Entry(null, value) } }
    }
}

/**
 * The loaded messages that are new or changed against the app's cached copy [cached]: only these are written back to
 * the database when a conversation opens, instead of the whole first page every time.
 */
internal fun messagesToCache(fresh: List<Message>, cached: List<Message>): List<Message> {
    if (cached.isEmpty()) return fresh
    val byKey = HashMap<com.glooboo.umessage.models.MessageKey, Message>(cached.size * 2)
    cached.forEach { byKey[it.key] = it }
    return fresh.filter { byKey[it.key] != it }
}

/**
 * How many rows above [anchorKey] count as new, in a list of row keys that is newest first: the rows that arrived
 * since the reader scrolled away from the bottom, where [anchorKey] was the newest row. Null or a row that is gone: 0.
 * Only rows for which [counted] is true add up (received messages; not date headers or the user's own messages).
 */
internal fun newRowsSince(rowKeys: List<String>, anchorKey: String?, counted: (String) -> Boolean = { true }): Int {
    if (anchorKey == null) return 0
    val at = rowKeys.indexOf(anchorKey)
    if (at <= 0) return 0
    return rowKeys.subList(0, at).count(counted)
}
