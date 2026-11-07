package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.models.ThreadItem

/** Where a bubble sits inside a run of consecutive messages from the same side, which decides its corners. */
enum class BubbleGroup { Single, First, Middle, Last }

/** One visual row of the chat list, derived from the flat [ThreadItem] list. */
sealed interface ThreadRow {
    val key: String

    /**
     * [showDay] is false for a later header on the same calendar day (TH-01: the day is written once, the later ones
     * only carry the time); [showSim] is always true: every header names its SIM (the screen shows it only on a
     * dual-SIM phone);
     * [scheduled] when it stands above a scheduled message, so it reads "Scheduled for ..." (B16).
     */
    data class DateHeader(
        val date: Int,
        val simLabel: String,
        override val key: String,
        val showDay: Boolean = true,
        val showSim: Boolean = true,
        val scheduled: Boolean = false,
    ) : ThreadRow

    data class Bubble(
        val message: Message,
        val group: BubbleGroup,
        /** Group chats label received runs with the sender's name. */
        val showSender: Boolean,
        override val key: String,
    ) : ThreadRow

    data class Sending(val messageId: Long, override val key: String) : ThreadRow

    data class Failed(val message: MessageKey, val text: String, override val key: String) : ThreadRow

    data class Sent(
        val messageId: Long,
        val delivered: Boolean,
        override val key: String,
        val deliveryFailed: Boolean = false,
    ) : ThreadRow

    /** I26: the line between what was read and what was unread when the thread opened. */
    data class UnreadDivider(override val key: String) : ThreadRow
}

private fun sameRun(a: Message, b: Message): Boolean {
    if (a.isReceivedMessage() != b.isReceivedMessage()) return false
    if (a.isScheduled != b.isScheduled) return false
    return !a.isReceivedMessage() || a.senderPhoneNumber == b.senderPhoneNumber
}

/**
 * Turns the chronological [items] into rows, oldest first. Messages that sit directly next to each other
 * (no date header or status row between them) and come from the same side form one run of grouped bubbles.
 */
fun buildThreadRows(items: List<ThreadItem>, isGroup: Boolean): List<ThreadRow> {
    val rows = ArrayList<ThreadRow>(items.size)
    val usedKeys = HashSet<String>()

    fun uniqueKey(base: String): String {
        var key = base
        var n = 1
        while (!usedKeys.add(key)) key = "$base#${n++}"
        return key
    }

    var previousDay: java.time.LocalDate? = null
    val zone = java.time.ZoneId.systemDefault()

    items.forEachIndexed { index, item ->
        when (item) {
            is ThreadItem.ThreadDateTime -> {
                val day = java.time.Instant.ofEpochSecond(item.date.toLong()).atZone(zone).toLocalDate()
                // A scheduled time always names its day: "Scheduled for Tomorrow · 9:00".
                val scheduled = (items.getOrNull(index + 1) as? Message)?.isScheduled == true
                rows.add(
                    ThreadRow.DateHeader(
                        item.date, item.simID, uniqueKey("d${item.date}/${item.simID}"),
                        showDay = scheduled || day != previousDay, showSim = true,
                        scheduled = scheduled,
                    )
                )
                previousDay = day
            }

            is Message -> {
                val previous = items.getOrNull(index - 1) as? Message
                val next = items.getOrNull(index + 1) as? Message
                val joinsPrevious = previous != null && sameRun(previous, item)
                val joinsNext = next != null && sameRun(item, next)
                val group = when {
                    joinsPrevious && joinsNext -> BubbleGroup.Middle
                    joinsPrevious -> BubbleGroup.Last
                    joinsNext -> BubbleGroup.First
                    else -> BubbleGroup.Single
                }
                val showSender = isGroup && item.isReceivedMessage() && !joinsPrevious
                rows.add(ThreadRow.Bubble(item, group, showSender, uniqueKey("m${item.getStableId()}")))
            }

            is ThreadItem.ThreadError ->
                rows.add(
                    ThreadRow.Failed(
                        item.key, item.messageText, uniqueKey("e${item.messageId}${if (item.isMMS) "m" else "s"}")
                    )
                )

            is ThreadItem.ThreadSending -> rows.add(ThreadRow.Sending(item.messageId, uniqueKey("s${item.messageId}")))

            is ThreadItem.ThreadUnreadDivider -> rows.add(ThreadRow.UnreadDivider(uniqueKey("unread")))

            is ThreadItem.ThreadSent -> {
                val key = uniqueKey("t${item.messageId}")
                rows.add(ThreadRow.Sent(item.messageId, item.delivered, key, item.deliveryFailed))
            }
        }
    }
    return rows
}
