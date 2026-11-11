package com.glooboo.umessage.ui.conversations

import java.util.Locale

/**
 * Everything a list row shows or is derived from, for one chat. While a chat's input equals the one its cached row was
 * built from, the row is reused, so a selection tap or an unrelated change does not redo the regex, classification and
 * formatting of every chat. [minute] makes relative dates refresh as time passes; [otpFresh] expires the code chip.
 */
internal data class RowInput(
    val threadId: Long,
    val title: String,
    val snippet: String,
    val date: Int,
    val read: Boolean,
    val unreadCount: Int,
    val photoUri: String,
    val isGroup: Boolean,
    val phoneNumber: String,
    val scheduled: Boolean,
    val customTitle: Boolean,
    val draft: String,
    val simSlot: Int?,
    val simName: String,
    val pinned: Boolean,
    val muted: Boolean,
    val otpFresh: Boolean,
    val hasNotes: Boolean,
    val minute: Long,
    val locale: Locale,
)

/** Remembers one derived value per key together with the input it was derived from. Not thread safe. */
internal class RowCache<K : Any, I : Any, V : Any> {
    private val entries = HashMap<K, Pair<I, V>>()

    /** The cached value when [input] equals the cached input of [key]; else [compute]d, and remembered. */
    fun get(key: K, input: I, compute: () -> V): V {
        lookup(key, input)?.let { return it }
        return compute().also { store(key, input, it) }
    }

    fun lookup(key: K, input: I): V? = entries[key]?.takeIf { it.first == input }?.second

    fun store(key: K, input: I, value: V) {
        entries[key] = input to value
    }

    /** Forgets every key not in [keys], so deleted chats do not pile up. */
    fun retainOnly(keys: Set<K>) {
        entries.keys.retainAll(keys)
    }

    val size: Int get() = entries.size
}
