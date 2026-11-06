package com.glooboo.umessage.models

/**
 * Thread item representations for the main thread recyclerview. [Message] is also a [ThreadItem]
 */
sealed class ThreadItem {
    data class ThreadDateTime(val date: Int, val simID: String) : ThreadItem()
    /** The failed message is [messageId] of type [isMMS] (see [MessageKey]); Retry acts on that pair. */
    data class ThreadError(val messageId: Long, val messageText: String, val isMMS: Boolean = false) : ThreadItem() {
        val key: MessageKey get() = MessageKey(messageId, isMMS)
    }
    data class ThreadSent(val messageId: Long, val delivered: Boolean) : ThreadItem()
    data class ThreadSending(val messageId: Long) : ThreadItem()

    /** The "Unread messages" line above the message with [messageId] (its [Message.getStableId]). */
    data class ThreadUnreadDivider(val messageId: Long) : ThreadItem()
}
