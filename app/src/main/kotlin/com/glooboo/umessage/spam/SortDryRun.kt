package com.glooboo.umessage.spam

import android.content.Context
import com.glooboo.umessage.spam.data.RemovedMembers
import com.glooboo.umessage.spam.engine.SenderJudge
import com.glooboo.umessage.ui.spam.ChatMoveCounter
import com.glooboo.umessage.ui.spam.SortChange
import com.glooboo.umessage.ui.spam.applying
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * G-B3 (answer 18, U11): the [ChatMoveCounter] of the switch confirm, by a dry run of what the change would do. The
 * snapshot of now with the changed settings sorts the cached chats ([ChatSorter.countMoves]); for the switch going on,
 * whose pass checks the history first (3.5), the senders the history check would add join that snapshot first
 * ([HistoryCheck.dryRun]), less those the user removed from a group (B-9), as the real join leaves them out. Nothing is
 * written: no member, no row, no snapshot kept. Off the main thread ([dispatcher]); null when a read failed, or while
 * the first sort was not published (nothing moves before it; the first run sorts every chat).
 */
class SortDryRun(
    private val snapshot: () -> SenderJudge.Snapshot,
    private val sorter: () -> ChatSorter,
    /** A history check over the given snapshot; only its [HistoryCheck.dryRun] is used. */
    private val history: (snapshot: () -> SenderJudge.Snapshot) -> HistoryCheck,
    private val published: () -> Boolean,
    private val removed: (AllowGroup, Collection<String>) -> Boolean = RemovedMembers::contains,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ChatMoveCounter {

    override suspend fun chatsMoving(change: SortChange): Int? = withContext(dispatcher) {
        runCatching { count(change) }.getOrNull()
    }

    /** The count, on the caller's thread. */
    internal fun count(change: SortChange): Int? {
        if (!published()) return null
        val now = snapshot()
        val after = now.changed(settings = now.settings.applying(change))
        val switchOn = change is SortChange.Filter && change.on && !now.settings.filterOn
        if (!switchOn) return sorter().countMoves(after)
        val joins = history { after }.dryRun() ?: return null
        return sorter().countMoves(after.changed(moreMembers = membersOf(joins, after)))
    }

    /** The members the history check would add, as HISTORY members. */
    private fun membersOf(joins: HistoryJoins, snap: SenderJudge.Snapshot): List<AllowGroupMember> =
        (joins.wrote.map { AllowGroup.WROTE to it } + joins.code.map { AllowGroup.CODE to it })
            .filterNot { (group, address) ->
                val keys = snap.readers.senderKeys(address) + snap.readers.senderKey(address)
                removed(group, keys.filter { it.isNotEmpty() })
            }
            .map { (group, address) -> AllowGroupMember(group, "", address, source = MemberSource.HISTORY) }

    companion object {
        /** The counter of this app: the shared snapshot (R-7), the app's sorter and the phone's history. */
        fun forApp(context: Context): SortDryRun {
            val app = context.applicationContext
            return SortDryRun(
                snapshot = { ChatSorter.snapshots(app).get() },
                sorter = { ChatSorter.forApp(app) },
                // The dry run never joins; a join that writes nothing, should it ever be called.
                history = { snap -> HistoryCheck(PhoneHistoryStore(app), snap, join = { _, _ -> 0 }) },
                published = { FirstRun.state(app).value.sortPublished },
            )
        }
    }
}
