package com.glooboo.umessage.ui.thread

import android.content.Context
import android.provider.Telephony
import com.glooboo.umessage.extensions.clearExpiredScheduledMessages
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.filterNotInByKey
import com.glooboo.umessage.extensions.getMessages
import com.glooboo.umessage.extensions.getOlderThreadMessages
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.extensions.toSortedMessages
import com.glooboo.umessage.helpers.MESSAGES_LIMIT
import com.glooboo.umessage.models.Message

/** Where the thread screen reads its messages from: the app's database and the phone's SMS/MMS provider. */
interface ThreadMessageSource {
    /** The "recycle bin" setting: deleted messages wait there instead of being removed. */
    val useRecycleBin: Boolean

    /** The app's own copy of the thread, shown at once while the provider is read. */
    fun cached(threadId: Long, recycleBin: Boolean): List<Message>

    /** Deletes scheduled messages among [cached] whose time is long past. */
    fun clearExpiredScheduled(threadId: Long, cached: List<Message>)

    /** The newest page from the provider (and the app's scheduled messages, when [includeScheduled]). */
    fun newest(threadId: Long, includeScheduled: Boolean = true, limit: Int = MESSAGES_LIMIT): List<Message>

    fun recycled(threadId: Long): List<Message>
    fun scheduled(threadId: Long): List<Message>

    /** The page of messages before [cutoff] (seconds), leaving out those already [loaded]. */
    fun older(threadId: Long, cutoff: Int, loaded: List<Message>): List<Message>

    /** Whether the provider has anything before [cutoff]; null when it could not answer. */
    fun hasOlder(threadId: Long, cutoff: Int): Boolean?
}

/**
 * The thread's loaded messages and how they change: the cached copy, the provider's newest page, older pages while
 * scrolling up, the refresh after every new message, and single messages added, replaced or removed. Every change
 * goes through one [ThreadMessageList], whichever background thread makes it. Messages are told apart by
 * [Message.key] (SMS 57 and MMS 57 are different messages).
 */
class ThreadMessagesRepository(
    private val source: ThreadMessageSource,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val store = ThreadMessageList()

    val current: List<Message> get() = store.current

    /** True once paging reached the start of the thread; a refresh clears it again. */
    @Volatile
    var allFetched = false

    /** True while an older page (or a jump to a search result) is being loaded. */
    @Volatile
    var loadingOlder = false

    fun isEmpty() = current.isEmpty()

    /** Everything is scheduled: the thread exists only in the app until its first message is sent. */
    fun hasOnlyScheduled(): Boolean = current.let { it.isNotEmpty() && it.all { m -> m.isScheduled } }

    fun reset() {
        store.set(emptyList())
        allFetched = false
        loadingOlder = false
    }

    fun update(transform: (List<Message>) -> List<Message>): List<Message> = store.update(transform)

    /** Shows the app's cached copy: expired scheduled messages are dropped, and at most one page is kept. */
    fun loadCached(threadId: Long, recycleBin: Boolean): List<Message> {
        var cached = runCatching { source.cached(threadId, recycleBin) }.getOrDefault(emptyList())
        source.clearExpiredScheduled(threadId, cached)
        cached = cached.filterNot { it.isExpiredScheduled(now()) }.toSortedMessages()
        if (cached.size > MESSAGES_LIMIT) cached = cached.takeLast(MESSAGES_LIMIT)
        store.set(cached)
        return cached
    }

    /** Replaces the list with the provider's newest page, without messages waiting in the recycle bin. */
    fun loadNewest(threadId: Long): List<Message> {
        var fresh = source.newest(threadId)
        if (source.useRecycleBin) {
            val recycled = source.recycled(threadId)
            fresh = fresh.filterNotInByKey(recycled) { it.getStableId() }
        }
        return fresh.toSortedMessages().also { store.set(it) }
    }

    /**
     * Pages in the messages before [cutoff]. An empty page marks the history as complete only when the provider
     * confirms nothing older exists, so a provider hiccup is retried on the next scroll.
     */
    fun fetchOlder(threadId: Long, cutoff: Int): List<Message> {
        val older = source.older(threadId, cutoff, current)
        if (older.isEmpty()) {
            allFetched = isHistoryExhausted(pageSize = 0, probeHasOlder = source.hasOlder(threadId, cutoff))
            return older
        }
        store.update { loaded -> (older.filterNotInByKey(loaded) { it.getStableId() } + loaded).toSortedMessages() }
        return older
    }

    /** The cutoff for the next older page: the oldest loaded message's date, or null with nothing loaded. */
    fun oldestDate(): Int? = current.toSortedMessages().firstOrNull()?.date

    /** The provider's newest [limit] messages that are not loaded yet: what a send just added. */
    fun newProviderRows(threadId: Long, limit: Int): List<Message> {
        val loaded = current.toSortedMessages()
        return source.newest(threadId, limit = limit).filterNotInByKey(loaded) { it.getStableId() }
    }

    /** The provider's newest page without scheduled messages, for [refresh]. */
    fun newestFromProvider(threadId: Long): List<Message> = source.newest(threadId, includeScheduled = false)

    /**
     * After a new message or a delivery report: merges [fresh] (the provider's newest page, without scheduled
     * messages) with the scheduled ones, keeping older history already paged in, and drops recycled messages.
     */
    fun refresh(threadId: Long, fresh: List<Message>): List<Message> {
        allFetched = false
        val freshIsWholeThread = fresh.size < MESSAGES_LIMIT
        val scheduled = source.scheduled(threadId).filterNot { it.isExpiredScheduled(now()) }
        val recycled = if (source.useRecycleBin) source.recycled(threadId).toSet() else emptySet()
        return store.update { loaded ->
            mergeRefreshedMessages(loaded, fresh + scheduled, freshIsWholeThread)
                .filterNot { it in recycled }
                .toSortedMessages()
        }
    }

    /** Adds [message], or replaces the loaded one with the same key. */
    fun upsert(message: Message): List<Message> = store.update { loaded ->
        val updated = loaded.toSortedMessages().toMutableList()
        val index = updated.indexOfFirst { it.key == message.key }
        if (index != -1) updated[index] = message else updated.add(message)
        updated.toSortedMessages()
    }

    /** Adds messages that moved into this thread (scheduled ones adopted from a temporary thread). */
    fun addAll(messages: List<Message>) {
        store.update { loaded -> (loaded.filterNotInByKey(messages) { it.getStableId() } + messages).toSortedMessages() }
    }

    fun remove(messages: Collection<Message>): List<Message> {
        val set = messages.toSet()
        return store.update { loaded -> loaded.toSortedMessages().filterNot { it in set } }
    }

    /** Marks every loaded message read; returns whether any was unread. */
    fun markAllRead(threadId: Long): Boolean {
        val hadUnread = current.any { !it.read && it.threadId == threadId }
        if (hadUnread) store.update { loaded -> loaded.map { if (it.read) it else it.copy(read = true) } }
        return hadUnread
    }
}

/** [ThreadMessageSource] on the real database and telephony provider. */
class ContextThreadMessageSource(private val context: Context) : ThreadMessageSource {
    override val useRecycleBin: Boolean get() = context.config.useRecycleBin

    override fun cached(threadId: Long, recycleBin: Boolean): List<Message> = when {
        recycleBin -> context.messagesDB.getThreadMessagesFromRecycleBin(threadId)
        context.config.useRecycleBin -> context.messagesDB.getNonRecycledThreadMessages(threadId)
        else -> context.messagesDB.getThreadMessages(threadId)
    }

    override fun clearExpiredScheduled(threadId: Long, cached: List<Message>) =
        context.clearExpiredScheduledMessages(threadId, cached)

    override fun newest(threadId: Long, includeScheduled: Boolean, limit: Int): List<Message> =
        context.getMessages(threadId, includeScheduledMessages = includeScheduled, limit = limit)

    override fun recycled(threadId: Long): List<Message> = context.messagesDB.getThreadMessagesFromRecycleBin(threadId)

    override fun scheduled(threadId: Long): List<Message> = context.messagesDB.getScheduledThreadMessages(threadId)

    override fun older(threadId: Long, cutoff: Int, loaded: List<Message>): List<Message> =
        context.getOlderThreadMessages(threadId, cutoff, loaded)

    /** Asks the SMS and MMS tables directly; SMS dates are in milliseconds, MMS dates in seconds. */
    override fun hasOlder(threadId: Long, cutoff: Int): Boolean? = try {
        val args = arrayOf(threadId.toString())
        val resolver = context.contentResolver
        val sms = resolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.DATE} < ${cutoff.toLong() * 1000}", args, null
        )?.use { it.count > 0 }
        val mms = resolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID),
            "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.DATE} < $cutoff", args, null
        )?.use { it.count > 0 }
        if (sms == null || mms == null) null else sms || mms
    } catch (e: Exception) {
        null
    }
}
