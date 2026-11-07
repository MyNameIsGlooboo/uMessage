package com.glooboo.umessage.messaging

/**
 * I16: the service centre's send time of the SMS being stored right now (`SmsMessage.timestampMillis`). The receiver
 * sets it for the duration of its own synchronous store, and the provider insert reads it, so `date_sent` carries the
 * time the carrier stamped, not only the arrival time. A thread-local, so the long chain of spam-filter calls between
 * the two needs no extra parameter; stores outside the receiver (restores, replays) simply have none.
 */
object IncomingDateSent {
    private val current = ThreadLocal<Long?>()

    fun <T> during(dateSentMillis: Long, block: () -> T): T {
        current.set(dateSentMillis.takeIf { it > 0L })
        try {
            return block()
        } finally {
            current.remove()
        }
    }

    /** The time to store as `date_sent`, or null when none is known. */
    fun current(): Long? = current.get()
}
