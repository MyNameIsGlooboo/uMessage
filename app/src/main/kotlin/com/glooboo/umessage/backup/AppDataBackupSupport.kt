package com.glooboo.umessage.backup

import android.content.Context
import android.provider.Telephony
import androidx.core.content.edit
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.getMessagesDB
import com.glooboo.umessage.extensions.getThreadParticipants
import com.glooboo.umessage.helpers.StarredMessages
import com.glooboo.umessage.interfaces.ConversationsDao
import com.glooboo.umessage.interfaces.LabelsDao
import com.glooboo.umessage.interfaces.StarredMessagesDao
import com.glooboo.umessage.models.AppDataBackup
import com.glooboo.umessage.models.ChatRefBackup
import com.glooboo.umessage.models.ConversationLabel
import com.glooboo.umessage.models.Label
import com.glooboo.umessage.models.LabelBackup
import com.glooboo.umessage.models.LabelLinkBackup
import com.glooboo.umessage.models.StarBackup
import com.glooboo.umessage.models.StarredMessage
import kotlin.math.abs

/**
 * Reads the stars, labels and archived chats of this phone into an [AppDataBackup] and puts a backup's back. Chats
 * are named by the numbers of their people ([addressesOf]) and found again with [threadFor] (the telephony store's
 * thread for those numbers), so a restore on another install or after the ids changed still lands on the right chat.
 *
 * Restoring only adds: a star, label or link the phone already has is left alone, and a label is matched by name.
 */
class AppDataBackupSupport(
    private val stars: StarredMessagesDao,
    private val labels: LabelsDao,
    private val conversations: ConversationsDao,
    private val pendingArchive: PendingArchive,
    private val addressesOf: (threadId: Long) -> Set<String>,
    private val threadFor: (addresses: Set<String>) -> Long?,
    private val parts: List<AppDataPart> = emptyList(),
) {

    /**
     * The data of the chats in [threadIds] (stars whose chat is unknown go along); null when there is none.
     * [wholePhone] is true when every chat is exported: only then does data that belongs to no chat go along.
     */
    suspend fun collect(threadIds: Set<Long>, sms: Boolean, mms: Boolean, wholePhone: Boolean = true): AppDataBackup? {
        val starRows = stars.getAll()
            .filter { it.threadId == 0L || it.threadId in threadIds }
            .filter { if (it.isMMS) mms else sms }
            .map { StarBackup(it.address, it.date, it.bodyHash, it.starredTS, it.snapshotBody, it.isMMS) }
        val allLabels = labels.getLabels()
        val byId = allLabels.associateBy { it.id }
        val links = labels.getLinks().filter { it.threadId in threadIds }.mapNotNull { link ->
            val label = byId[link.labelId] ?: return@mapNotNull null
            val people = addressesOf(link.threadId).sorted()
            if (people.isEmpty()) null else LabelLinkBackup(people, label.name)
        }
        val archived = conversations.getAllArchived().filter { it.threadId in threadIds }.mapNotNull {
            addressesOf(it.threadId).sorted().takeIf { people -> people.isNotEmpty() }?.let(::ChatRefBackup)
        }
        val base = AppDataBackup(
            stars = starRows,
            labels = allLabels.map { LabelBackup(it.name, it.color, it.sortOrder) },
            labelLinks = links,
            archived = archived,
        )
        val request = AppDataRequest(threadIds, sms, mms, wholePhone)
        val data = parts.fold(base) { so, part -> part.collect(request, so) }
        return data.takeUnless { it.isEmpty }
    }

    /** Adds what [data] holds that this phone does not have yet; returns how many items were taken. */
    suspend fun restore(data: AppDataBackup): Int =
        restoreStars(data.stars) + restoreLabels(data) + restoreArchived(data.archived) +
            parts.sumOf { part -> part.restore(data) }

    private suspend fun restoreStars(incoming: List<StarBackup>): Int {
        val known = stars.getAll().toMutableList()
        var taken = 0
        for (star in incoming) {
            val address = StarredMessages.normalizedAddress(star.address)
            val exists = known.any {
                it.isMMS == star.isMms && it.date == star.date && it.bodyHash == star.bodyHash &&
                    StarredMessages.normalizedAddress(it.address) == address
            }
            if (exists) continue
            // Id and chat are not known on this phone; StarredMessages.relink points the star at its message later.
            val row = StarredMessage(
                id = placeholderId(star), isMMS = star.isMms, threadId = 0L, address = star.address,
                date = star.date, bodyHash = star.bodyHash, starredTS = star.starredTs, snapshotBody = star.body,
            )
            stars.insert(row)
            known += row
            taken++
        }
        return taken
    }

    /** A negative id from what identifies the star, so restoring the same file twice makes the same row. */
    private fun placeholderId(star: StarBackup): Long =
        -(abs("${star.address}|${star.date}|${star.bodyHash}|${star.isMms}".hashCode().toLong()) + 1)

    private suspend fun restoreLabels(data: AppDataBackup): Int {
        var taken = 0
        val existing = labels.getLabels().toMutableList()
        fun find(name: String) = existing.firstOrNull { it.name.equals(name, ignoreCase = true) }
        for (label in data.labels) {
            if (label.name.isBlank() || find(label.name) != null) continue
            val row = Label(name = label.name, color = label.color, sortOrder = label.sortOrder)
            existing += row.copy(id = labels.insertLabel(row))
            taken++
        }
        val current = labels.getLinks().map { it.threadId to it.labelId }.toMutableSet()
        for (link in data.labelLinks) {
            val label = find(link.label)
            val threadId = if (label == null) null else threadFor(link.addresses.toSet())
            if (label != null && threadId != null && current.add(threadId to label.id)) {
                labels.addToConversation(ConversationLabel(threadId, label.id))
                taken++
            }
        }
        return taken
    }

    private fun restoreArchived(chats: List<ChatRefBackup>): Int {
        var taken = 0
        for (chat in chats) {
            val threadId = threadFor(chat.addresses.toSet()) ?: continue
            val row = conversations.getConversationWithThreadId(threadId)
            if (row == null) {
                pendingArchive.add(threadId)
                taken++
            } else if (!row.isArchived) {
                conversations.moveToArchive(threadId)
                taken++
            }
        }
        return taken
    }

    companion object {
        fun of(context: Context): AppDataBackupSupport {
            val app = context.applicationContext
            val db = app.getMessagesDB()
            val addressesOf = { threadId: Long ->
                app.getThreadParticipants(threadId, null)
                    .mapNotNull { it.phoneNumbers.firstOrNull()?.normalizedNumber?.takeIf(String::isNotBlank) }
                    .toSet()
            }
            val threadFor = { addresses: Set<String> ->
                runCatching { Telephony.Threads.getOrCreateThreadId(app, addresses) }
                    .getOrNull()?.takeIf { it > 0 }
            }
            return AppDataBackupSupport(
                stars = db.StarredMessagesDao(),
                labels = db.LabelsDao(),
                conversations = app.conversationsDB,
                pendingArchive = PendingArchive(app),
                addressesOf = addressesOf,
                threadFor = threadFor,
                parts = AppDataParts.of(app, addressesOf, threadFor),
            )
        }
    }
}

/**
 * Chats a restore archived before the conversation list knew them. The list sync calls [applyTo] once it has stored
 * the rows, which archives the ones that exist now and keeps waiting for the others.
 */
class PendingArchive(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("restored_archive", Context.MODE_PRIVATE)

    fun add(threadId: Long) {
        prefs.edit { putStringSet(KEY, ids() + threadId.toString()) }
    }

    /** Archives the waiting chats whose row exists; returns how many were archived. */
    fun applyTo(conversations: ConversationsDao): Int {
        val waiting = ids()
        if (waiting.isEmpty()) return 0
        val done = waiting.filter { id ->
            val row = id.toLongOrNull()?.let(conversations::getConversationWithThreadId) ?: return@filter false
            if (!row.isArchived) conversations.moveToArchive(row.threadId)
            true
        }.toSet()
        if (done.isNotEmpty()) prefs.edit { putStringSet(KEY, waiting - done) }
        return done.size
    }

    private fun ids(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()

    private companion object {
        const val KEY = "threads"
    }
}
