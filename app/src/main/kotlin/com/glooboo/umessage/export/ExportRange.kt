package com.glooboo.umessage.export

import com.glooboo.umessage.models.Message
import java.time.LocalDate
import java.time.ZoneId

/** Which part of a conversation an export covers. */
sealed interface ExportRange {
    /** The messages of [messages] that fall inside the range; [nowMillis] is the time of the export. */
    fun filter(messages: List<Message>, nowMillis: Long, zone: ZoneId): List<Message>

    data object All : ExportRange {
        override fun filter(messages: List<Message>, nowMillis: Long, zone: ZoneId) = messages
    }

    /** The last [days] days before the export, to the minute the export is made. */
    data class LastDays(val days: Int) : ExportRange {
        override fun filter(messages: List<Message>, nowMillis: Long, zone: ZoneId): List<Message> {
            val since = nowMillis - days * MILLIS_PER_DAY
            return messages.filter { it.millis() in since..nowMillis }
        }
    }

    /** Whole days, [from] to [to] both included, in the phone's time zone. A swapped pair is put right. */
    data class Between(val from: LocalDate, val to: LocalDate) : ExportRange {
        override fun filter(messages: List<Message>, nowMillis: Long, zone: ZoneId): List<Message> {
            val first = minOf(from, to)
            val last = maxOf(from, to)
            val start = first.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            return messages.filter { it.millis() in start until end }
        }
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}
