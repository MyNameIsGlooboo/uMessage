package com.glooboo.umessage.export

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * D14: the UTC offsets a zone had between two moments, for the header of an export. Every row is written with the
 * rules of its own date, so a header that printed only today's offset was wrong for rows from the other season (or
 * from before the zone's rules changed). The header now lists each offset the rows can carry.
 */
object ZoneOffsets {
    private const val MAX_TRANSITIONS = 400

    /** The distinct offsets of [zone] from [fromMillis] to [toMillis], oldest first. */
    fun between(zone: ZoneId, fromMillis: Long, toMillis: Long): List<ZoneOffset> {
        val rules = zone.rules
        val start = Instant.ofEpochMilli(minOf(fromMillis, toMillis))
        val end = Instant.ofEpochMilli(maxOf(fromMillis, toMillis))
        val found = LinkedHashSet<ZoneOffset>()
        found.add(rules.getOffset(start))
        var at = start
        var transitions = 0
        while (transitions++ < MAX_TRANSITIONS) {
            val next = rules.nextTransition(at)?.takeIf { !it.instant.isAfter(end) } ?: break
            found.add(next.offsetAfter)
            at = next.instant
        }
        return found.toList()
    }

    /** "UTC+03:30", or "UTC+03:30, UTC+04:30" when the offset changed in the range. */
    fun text(offsets: List<ZoneOffset>): String =
        offsets.joinToString(", ") { "UTC" + it.id.replace("Z", "") }
}
