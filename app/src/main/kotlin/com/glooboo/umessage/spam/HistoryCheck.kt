package com.glooboo.umessage.spam

import android.content.Context
import android.provider.Telephony.Sms
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.getConversationsOrNull
import com.glooboo.umessage.spam.engine.AllowingCode
import com.glooboo.umessage.spam.engine.ContactLookup
import com.glooboo.umessage.spam.engine.SenderJudge
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.spam.engine.SpamEngine
import kotlinx.coroutines.runBlocking

/** What one history check found: senders who joined "People you wrote to" ([wrote]) and the code group ([code]). */
data class HistoryResult(val wrote: Int, val code: Int) {
    /** "M senders allowed automatically" of the "What changed" page. */
    val allowed: Int get() = wrote + code
}

/** The senders a history check adds to "People you wrote to" ([wrote]) and to the code group ([code]). */
data class HistoryJoins(val wrote: List<String>, val code: List<String>)

/** What the history check reads from the phone. Every read that fails says so (null or UNKNOWN); none throws. */
interface HistoryStore {
    /** Every chat of the phone's store (not the app's cache: it may not be filled yet); null when unreadable. */
    fun chats(): List<SortableChat>?

    /** The app's cached chats: cheap, for the check of one sender on the spot. */
    fun cachedChats(): List<SortableChat>

    /** Whether the phone's store holds a text the user sent to [address] (the 0.4.14 "wrote to" reading). */
    fun wroteTo(address: String): ContactLookup

    /** Of [threadIds], those with a received text that [matches]; null when the store could not be read. */
    fun threadsWithReceived(threadIds: Collection<Long>, matches: (String) -> Boolean): Set<Long>?
}

/**
 * Provider truth (3.5: R2, U3, R5): before chats of unknown senders go to Spam, their old texts are checked. For each
 * one-to-one chat whose sender is unknown (the verdict is Spam for an unknown sender, or Messages only because the
 * filter is off):
 *
 * - the phone's store holds a text the user sent to that number: the sender joins "People you wrote to";
 * - else a received text is a verification code ([AllowingCode], the same test as a new text): the sender joins
 *   "Senders of a verification code";
 * - else nothing; the chat goes to Spam at the sort.
 *
 * Members join with source HISTORY, so they are born acknowledged (no line in old chats, answer 4), in one batch per
 * group (R-3, bulk joinGroups), which never adds a sender with a block rule. A blocked or allowed sender, a contact, a
 * group chat and a sender that cannot be read are never candidates. A failed read stops the check (null): it is tried
 * again later, and nothing is sorted on a half-known history.
 */
class HistoryCheck(
    private val store: HistoryStore,
    private val snapshot: () -> SenderJudge.Snapshot,
    private val join: (AllowGroup, List<String>) -> Int,
    private val isCode: (String) -> Boolean = AllowingCode::isAllowingCode,
) {

    /** The whole check, over every chat of the phone's store. Null when a read failed. */
    fun run(): HistoryResult? = runCatching { runChecked() }.getOrNull()

    /**
     * G-B3, a dry run of [run]: the senders it would add to each group, without adding them (join is never called).
     * Null when a read failed.
     */
    fun dryRun(): HistoryJoins? = runCatching { found() }.getOrNull()

    private fun runChecked(): HistoryResult? {
        val found = found() ?: return null
        return HistoryResult(
            wrote = if (found.wrote.isEmpty()) 0 else join(AllowGroup.WROTE, found.wrote),
            code = if (found.code.isEmpty()) 0 else join(AllowGroup.CODE, found.code),
        )
    }

    /** The senders of [run] for each group; reads only. */
    private fun found(): HistoryJoins? {
        val snap = snapshot()
        val chats = store.chats() ?: return null
        val threadsBySender = chats.filter { it.sortable && isCandidate(snap.verdict(it.phoneNumber)) }
            .groupBy({ it.phoneNumber }, { it.threadId })
        val sent = threadsBySender.keys.associateWith { store.wroteTo(it) }
        if (ContactLookup.UNKNOWN in sent.values) return null
        val wrote = sent.filterValues { it == ContactLookup.FOUND }.keys.toList()
        val rest = sent.filterValues { it == ContactLookup.NOT_FOUND }.keys.toList()
        val restThreads = rest.flatMap { threadsBySender.getValue(it) }
        val withCode = if (restThreads.isEmpty()) {
            emptySet()
        } else {
            store.threadsWithReceived(restThreads, isCode) ?: return null
        }
        val code = rest.filter { sender -> threadsBySender.getValue(sender).any { it in withCode } }
        return HistoryJoins(wrote = wrote, code = code)
    }

    /**
     * R2: a text from [address] arrived while the whole check has not finished; its sender is checked now, before the
     * text's verdict, so nothing is stored silently on a half-known history. Reads the received texts of the app's
     * cached chats of that sender and of [threadId], the text's own thread in the phone's store (B-5: its chat may
     * not be cached yet). False when a read failed (the caller then fails open).
     */
    fun checkSender(address: String, threadId: Long? = null): Boolean =
        runCatching { checkSenderChecked(address, threadId) }.getOrDefault(false)

    private fun checkSenderChecked(address: String, threadId: Long?): Boolean {
        val snap = snapshot()
        if (!isCandidate(snap.verdict(address))) return true
        val wrote = store.wroteTo(address)
        if (wrote == ContactLookup.FOUND) join(AllowGroup.WROTE, listOf(address))
        if (wrote != ContactLookup.NOT_FOUND) return wrote == ContactLookup.FOUND
        val threads = store.cachedChats()
            .filter { it.sortable && (it.phoneNumber == address || snap.readers.same(it.phoneNumber, address)) }
            .map { it.threadId } + listOfNotNull(threadId?.takeIf { it > 0L })
        val withCode = if (threads.isEmpty()) {
            emptySet()
        } else {
            store.threadsWithReceived(threads, isCode) ?: return false
        }
        if (withCode.isNotEmpty()) join(AllowGroup.CODE, listOf(address))
        return true
    }

    private fun isCandidate(verdict: SenderVerdict): Boolean = when (verdict) {
        is SenderVerdict.Spam -> verdict.cause == SpamCause.UNKNOWN
        is SenderVerdict.Messages -> verdict.reason == DecisionReason.MODE_OFF
    }

    companion object {
        /**
         * The check of this app: the phone's store, the shared snapshot (R-7) and the repository's batch join. [chats]:
         * the store's chats when the caller read them already (one read per pass for the check and the sort).
         */
        fun forApp(context: Context, chats: List<SortableChat>? = null): HistoryCheck {
            val app = context.applicationContext
            val repo = SpamEngine.repository(app)
            return HistoryCheck(
                store = PhoneHistoryStore(app, chats),
                snapshot = { ChatSorter.snapshots(app).get() },
                join = { group, senders -> runBlocking { repo.joinGroups(group, senders, MemberSource.HISTORY) } },
            )
        }

        /**
         * For the incoming paths (step D, R2): checks the sender of a text now when the first run's history check has
         * not finished yet; does nothing otherwise. False when the check failed.
         */
        fun onTheSpot(context: Context, address: String, threadId: Long? = null): Boolean {
            if (!FirstRun.state(context).value.historyPending) return true
            return forApp(context).checkSender(address, threadId)
        }
    }
}

/** [HistoryStore] on the phone's own message store. */
internal class PhoneHistoryStore(
    private val context: Context,
    private val preloaded: List<SortableChat>? = null,
) : HistoryStore {

    /** B-16: also the chats of numbers on Android's block list (they are judged, and never join a group). */
    override fun chats(): List<SortableChat>? = preloaded ?: context.getConversationsOrNull(includeBlocked = true)
        ?.map { SortableChat(it.threadId, it.phoneNumber, isGroup = it.isGroupConversation, isScheduled = false) }

    override fun cachedChats(): List<SortableChat> = context.conversationsDB.getSortableChats()

    override fun wroteTo(address: String): ContactLookup =
        runCatching { SpamEngine.contactChecker(context).sentTo(address) }.getOrDefault(ContactLookup.UNKNOWN)

    override fun threadsWithReceived(threadIds: Collection<Long>, matches: (String) -> Boolean): Set<Long>? =
        runCatching {
            val found = HashSet<Long>()
            threadIds.distinct().chunked(CHUNK).forEach { chunk ->
                val marks = chunk.joinToString(",") { "?" }
                context.contentResolver.query(
                    Sms.CONTENT_URI,
                    arrayOf(Sms.THREAD_ID, Sms.BODY),
                    "${Sms.TYPE} = ${Sms.MESSAGE_TYPE_INBOX} AND ${Sms.THREAD_ID} IN ($marks)",
                    chunk.map { it.toString() }.toTypedArray(),
                    null,
                )?.use {
                    while (it.moveToNext()) {
                        val thread = it.getLong(0)
                        if (thread !in found && matches(it.getString(1).orEmpty())) found += thread
                    }
                } ?: error("no cursor")
            }
            found
        }.getOrNull()

    private companion object {
        /** Thread ids per query; well below SQLite's limit of bound arguments. */
        const val CHUNK = 200
    }
}
