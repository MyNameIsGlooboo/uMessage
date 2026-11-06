package com.glooboo.umessage.ui.spam

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One message the user moved from Blocked to the inbox (BR-10). No message text is kept, only who and when. */
data class RestoredEntry(val address: String, val receivedAt: Long, val restoredAt: Long)

/**
 * The text form of the restored list: one `restoredAt TAB receivedAt TAB address` line per entry, newest first.
 * Entries older than [RestoredLog.KEEP_MS] or beyond [RestoredLog.MAX_ENTRIES] are dropped.
 */
object RestoredLogCodec {
    private val breaks = Regex("[\\t\\n\\r]")

    fun encode(entries: List<RestoredEntry>): String =
        entries.joinToString("\n") { "${it.restoredAt}\t${it.receivedAt}\t${it.address.replace(breaks, " ")}" }

    fun decode(text: String?): List<RestoredEntry> = text.orEmpty().lineSequence().mapNotNull { line ->
        val parts = line.split('\t', limit = 3)
        val restoredAt = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val receivedAt = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
        val address = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        RestoredEntry(address, receivedAt, restoredAt)
    }.toList()

    /** [added] in front of [current], newest first, without expired entries and capped at [max]. */
    fun merge(
        current: List<RestoredEntry>,
        added: List<RestoredEntry>,
        now: Long,
        keepMs: Long,
        max: Int,
    ): List<RestoredEntry> =
        (added + current)
            .filter { now - it.restoredAt in 0..keepMs }
            .sortedByDescending { it.restoredAt }
            .take(max)
}

/**
 * The recently restored messages behind the "N restored" link of the Blocked banner (BR-10), kept in a small private
 * preferences file. Never throws: a storage failure just means an empty list.
 */
class RestoredLog(private val prefs: SharedPreferences?, private val now: () -> Long = System::currentTimeMillis) {
    private val state = MutableStateFlow(read())

    val entries: StateFlow<List<RestoredEntry>> = state.asStateFlow()

    fun record(added: List<RestoredEntry>) {
        if (added.isEmpty()) return
        val merged = RestoredLogCodec.merge(state.value, added, now(), KEEP_MS, MAX_ENTRIES)
        state.value = merged
        runCatching { prefs?.edit { putString(KEY, RestoredLogCodec.encode(merged)) } }
    }

    /** Undo of a restore takes its entries back out. */
    fun remove(entries: List<RestoredEntry>) {
        if (entries.isEmpty()) return
        val gone = entries.toSet()
        val kept = state.value.filterNot { it in gone }
        state.value = kept
        runCatching { prefs?.edit { putString(KEY, RestoredLogCodec.encode(kept)) } }
    }

    private fun read(): List<RestoredEntry> = runCatching {
        val stored = RestoredLogCodec.decode(prefs?.getString(KEY, null))
        RestoredLogCodec.merge(stored, emptyList(), now(), KEEP_MS, MAX_ENTRIES)
    }.getOrDefault(emptyList())

    companion object {
        const val KEEP_DAYS = 30
        const val KEEP_MS = KEEP_DAYS * 24L * 60 * 60 * 1000
        const val MAX_ENTRIES = 100
        private const val PREFS = "spamless_restored"
        private const val KEY = "entries"

        @Volatile private var shared: RestoredLog? = null

        /** One log per process, so the list and the Blocked screen agree after a rotation. */
        fun get(context: Context): RestoredLog = shared ?: synchronized(this) {
            shared ?: RestoredLog(
                runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
                    .getOrNull(),
            ).also { shared = it }
        }
    }
}

/** A day header of the Blocked list (BR-17) and the sender groups whose newest message came that day. */
data class BlockedDay(val day: LocalDate, val groups: List<SenderGroup>)

/** Splits [groups] (newest first) by the day of each group's newest message. */
fun groupByDay(groups: List<SenderGroup>, zone: ZoneId = ZoneId.systemDefault()): List<BlockedDay> {
    val days = mutableListOf<BlockedDay>()
    groups.forEach { group ->
        val day = Instant.ofEpochMilli(group.latest.receivedAt).atZone(zone).toLocalDate()
        val last = days.lastOrNull()
        if (last != null && last.day == day) {
            days[days.lastIndex] = last.copy(groups = last.groups + group)
        } else {
            days += BlockedDay(day, listOf(group))
        }
    }
    return days
}
