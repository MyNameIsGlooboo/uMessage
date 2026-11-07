package com.glooboo.umessage.backup

import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.MutePrefs
import com.glooboo.umessage.helpers.SavedTextAdd
import com.glooboo.umessage.helpers.SavedTexts
import com.glooboo.umessage.interfaces.DraftsDao
import com.glooboo.umessage.models.AppDataBackup
import com.glooboo.umessage.models.ChatRefBackup
import com.glooboo.umessage.models.Draft
import com.glooboo.umessage.models.DraftBackup
import com.glooboo.umessage.models.MuteBackup
import com.glooboo.umessage.models.SavedTextBackup

/**
 * Drafts, muted chats, hidden chats and saved texts. Chats are named by the numbers of their people and found again
 * with [threadFor], like the other app data. Restoring only adds: a chat that already has a draft keeps it, a longer
 * mute is kept, and a saved text the phone has (same text) is not added twice. A hidden chat stays out of sight only
 * while an app lock is on; the lock itself is never in a backup.
 */
class ChatStatePart(
    private val drafts: DraftsDao,
    private val mutes: MutePrefs,
    private val hidden: HiddenChats,
    private val savedTexts: SavedTexts,
    private val addressesOf: (threadId: Long) -> Set<String>,
    private val threadFor: (addresses: Set<String>) -> Long?,
) : AppDataPart {

    override suspend fun collect(request: AppDataRequest, base: AppDataBackup): AppDataBackup {
        val wanted = request.threadIds
        fun people(threadId: Long) = addressesOf(threadId).sorted().takeIf { it.isNotEmpty() }
        return base.copy(
            drafts = drafts.getAll().filter { it.threadId in wanted && it.body.isNotBlank() }.mapNotNull { draft ->
                people(draft.threadId)?.let { DraftBackup(it, draft.body, draft.date) }
            },
            muted = mutes.activeMutes().filterKeys { it in wanted }.mapNotNull { (threadId, until) ->
                people(threadId)?.let { MuteBackup(it, until) }
            },
            hidden = hidden.chosenIds().filter { it in wanted }.mapNotNull { threadId ->
                people(threadId)?.let(::ChatRefBackup)
            },
            savedTexts = if (request.wholePhone) {
                savedTexts.all().map { SavedTextBackup(it.title, it.text) }
            } else {
                emptyList()
            },
        )
    }

    override suspend fun restore(data: AppDataBackup): Int =
        restoreDrafts(data) + restoreMutes(data) + restoreHidden(data) + restoreSavedTexts(data)

    private fun restoreDrafts(data: AppDataBackup): Int {
        var taken = 0
        for (draft in data.drafts) {
            val threadId = if (draft.body.isBlank()) null else threadFor(draft.addresses.toSet())
            if (threadId == null || !drafts.getDraftById(threadId)?.body.isNullOrBlank()) continue
            drafts.insertOrUpdate(Draft(threadId, draft.body, draft.date))
            taken++
        }
        return taken
    }

    private fun restoreMutes(data: AppDataBackup): Int =
        data.muted.count { mute ->
            val threadId = threadFor(mute.addresses.toSet())
            threadId != null && mutes.muteUntil(threadId, mute.until)
        }

    private fun restoreHidden(data: AppDataBackup): Int {
        var taken = 0
        for (chat in data.hidden) {
            val threadId = threadFor(chat.addresses.toSet())
            if (threadId == null || hidden.isChosen(threadId)) continue
            hidden.hide(threadId)
            taken++
        }
        return taken
    }

    private fun restoreSavedTexts(data: AppDataBackup): Int {
        var taken = 0
        for (saved in data.savedTexts) {
            when (savedTexts.add(saved.title, saved.text)) {
                SavedTextAdd.Added -> taken++
                SavedTextAdd.Full -> break
                else -> Unit
            }
        }
        return taken
    }
}
