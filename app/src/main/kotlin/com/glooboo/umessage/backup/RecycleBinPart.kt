package com.glooboo.umessage.backup

import android.content.Context
import androidx.core.content.edit
import com.glooboo.umessage.helpers.StarredMessages
import com.glooboo.umessage.interfaces.MessagesDao
import com.glooboo.umessage.models.AppDataBackup
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.models.RecycleBinMessage
import com.glooboo.umessage.models.RecycledBackup
import kotlinx.serialization.json.Json

/**
 * The messages in the recycle bin. They are in the file as normal messages (the system store keeps them), so the
 * entry only names them; a restore moves them back into the bin once they are cached under their new ids, which is
 * why [PendingRecycled] holds the names until [PendingRecycled.applyTo] finds the messages.
 */
class RecycleBinPart(
    private val messages: MessagesDao,
    private val pending: PendingRecycled,
    private val now: () -> Long = System::currentTimeMillis,
) : AppDataPart {

    override suspend fun collect(request: AppDataRequest, base: AppDataBackup): AppDataBackup {
        val recycled = messages.getAllRecycleBinMessages()
            .filter { it.threadId in request.threadIds && !it.isScheduled }
            .filter { if (it.isMMS) request.mms else request.sms }
            .map {
                RecycledBackup(
                    address = StarredMessages.addressOf(it),
                    date = it.date.toLong(),
                    bodyHash = StarredMessages.fingerprint(it.body),
                    isMms = it.isMMS,
                )
            }
        return base.copy(recycled = recycled)
    }

    override suspend fun restore(data: AppDataBackup): Int {
        if (data.recycled.isEmpty()) return 0
        val before = pending.size()
        pending.addAll(data.recycled, now())
        val moved = pending.applyTo(messages, now())
        return moved + maxOf(0, pending.size() - before)
    }
}

/**
 * Recycle-bin names a restore could not match to a cached message yet. [applyTo] puts the matching messages in the
 * bin, as moving them there would (the bin starts counting its retention at that moment) and forgets those names; a
 * name that still matches nothing after [MAX_AGE_MS] is dropped, and at most [MAX_ITEMS] are kept.
 */
class PendingRecycled(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("restored_recycled", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun size(): Int = stored().size

    fun addAll(items: List<RecycledBackup>, nowMillis: Long) {
        val all = stored().toMutableMap()
        items.forEach { all.putIfAbsent(json.encodeToString(RecycledBackup.serializer(), it), nowMillis) }
        write(all.entries.sortedByDescending { it.value }.take(MAX_ITEMS).associate { it.key to it.value })
    }

    /** Puts the waiting messages that are cached now in the bin; returns how many it moved. */
    fun applyTo(messages: MessagesDao, nowMillis: Long = System.currentTimeMillis()): Int {
        val waiting = stored()
        if (waiting.isEmpty()) return 0
        val inBin = messages.getAllRecycleBinEntries().mapTo(HashSet()) { it.key }
        var moved = 0
        val keep = linkedMapOf<String, Long>()
        for ((text, queuedAt) in waiting) {
            val item = runCatching { json.decodeFromString(RecycledBackup.serializer(), text) }.getOrNull() ?: continue
            val target = findMessage(messages, item)
            when {
                target == null -> if (nowMillis - queuedAt < MAX_AGE_MS) keep[text] = queuedAt
                target in inBin -> Unit
                else -> {
                    messages.insertRecycleBinEntry(RecycleBinMessage(target.id, target.isMMS, nowMillis))
                    inBin += target
                    moved++
                }
            }
        }
        if (keep.size != waiting.size) write(keep)
        return moved
    }

    private fun findMessage(messages: MessagesDao, item: RecycledBackup): MessageKey? {
        val address = StarredMessages.normalizedAddress(item.address)
        return messages.getMessagesAtDate(item.date.toInt()).firstOrNull {
            it.isMMS == item.isMms && StarredMessages.fingerprint(it.body) == item.bodyHash &&
                StarredMessages.normalizedAddress(StarredMessages.addressOf(it)) == address
        }?.key
    }

    private fun stored(): Map<String, Long> = prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNull {
        val at = it.substringBefore(SEP).toLongOrNull()
        if (at == null || SEP !in it) null else it.substringAfter(SEP) to at
    }.toMap()

    private fun write(items: Map<String, Long>) {
        prefs.edit { putStringSet(KEY, items.mapTo(HashSet()) { (text, at) -> "$at$SEP$text" }) }
    }

    private companion object {
        const val KEY = "items"
        const val SEP = '|'
        const val MAX_ITEMS = 5_000
        const val MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000
    }
}
