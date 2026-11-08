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
    /** [deliveryFailed]: the carrier's delivery report says the text did not arrive (SMS only). */
    data class ThreadSent(
        val messageId: Long,
        val delivered: Boolean,
        val deliveryFailed: Boolean = false,
    ) : ThreadItem()
    data class ThreadSending(val messageId: Long) : ThreadItem()

    /**
     * Q40: the picture message [messageId] was announced by the carrier but never downloaded. It replaces the empty
     * bubble; [expirySeconds] is the carrier's expiry (0 when unknown).
     */
    data class ThreadMmsDownload(val messageId: Long, val expirySeconds: Long) : ThreadItem()

    /** The "Unread messages" line above the message with [messageId] (its [Message.getStableId]). */
    data class ThreadUnreadDivider(val messageId: Long) : ThreadItem()
}
