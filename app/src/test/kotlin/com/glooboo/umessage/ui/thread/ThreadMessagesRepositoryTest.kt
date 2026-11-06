package com.glooboo.umessage.ui.thread

import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.MESSAGES_LIMIT
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Loading, paging and refreshing the thread's messages, on a fake database and provider (TU-50). */
@RunWith(AndroidJUnit4::class)
class ThreadMessagesRepositoryTest {

    private class FakeSource : ThreadMessageSource {
        override var useRecycleBin = false
        var cached: List<Message> = emptyList()
        var newest: List<Message> = emptyList()
        var recycled: List<Message> = emptyList()
        var scheduled: List<Message> = emptyList()
        var olderPages = ArrayDeque<List<Message>>()
        var hasOlder: Boolean? = false
        var clearedExpired = false

        override fun cached(threadId: Long, recycleBin: Boolean) = cached
        override fun clearExpiredScheduled(threadId: Long, cached: List<Message>) {
            clearedExpired = true
        }

        override fun newest(threadId: Long, includeScheduled: Boolean, limit: Int) =
            if (includeScheduled) newest + scheduled else newest

        override fun recycled(threadId: Long) = recycled
        override fun scheduled(threadId: Long) = scheduled
        override fun older(threadId: Long, cutoff: Int, loaded: List<Message>) = olderPages.removeFirstOrNull().orEmpty()
        override fun hasOlder(threadId: Long, cutoff: Int) = hasOlder
    }

    private val now = 10_000_000L * 1000

    private fun message(id: Long, date: Int, isMMS: Boolean = false, scheduled: Boolean = false, read: Boolean = true) = Message(
        id = id, body = "m$id", type = Telephony.Sms.MESSAGE_TYPE_INBOX, status = -1, participants = ArrayList(),
        date = date, read = read, threadId = THREAD, isMMS = isMMS, attachment = null, senderPhoneNumber = "",
        senderName = "", senderPhotoUri = "", subscriptionId = -1, isScheduled = scheduled,
    )

    private fun page(fromDate: Int, count: Int, firstId: Long) =
        (0 until count).map { message(firstId + it, fromDate + it) }

    @Test
    fun cachedCopyDropsExpiredScheduledMessagesAndKeepsOnePage() {
        val source = FakeSource()
        val nowSeconds = (now / 1000).toInt()
        source.cached = page(1, MESSAGES_LIMIT + 5, firstId = 1) +
            message(900, date = nowSeconds - 3600, scheduled = true) +
            message(901, date = nowSeconds + 3600, scheduled = true)
        val repository = ThreadMessagesRepository(source) { now }

        val loaded = repository.loadCached(THREAD, recycleBin = false)

        assertThat(source.clearedExpired).isTrue()
        assertThat(loaded).hasSize(MESSAGES_LIMIT)
        assertThat(loaded.map { it.id }).doesNotContain(900L)
        assertThat(loaded.last().id).isEqualTo(901L)
    }

    @Test
    fun newestPageLeavesOutRecycledMessages() {
        val source = FakeSource().apply {
            useRecycleBin = true
            newest = listOf(message(1, 10), message(2, 20))
            recycled = listOf(message(2, 20))
        }
        val repository = ThreadMessagesRepository(source) { now }

        assertThat(repository.loadNewest(THREAD).map { it.id }).containsExactly(1L)
    }

    @Test
    fun pagingAddsOlderMessagesAndStopsOnlyWhenTheProviderConfirms() {
        val source = FakeSource().apply { newest = listOf(message(10, 100), message(11, 110)) }
        val repository = ThreadMessagesRepository(source) { now }
        repository.loadNewest(THREAD)
        source.olderPages.add(listOf(message(5, 50), message(10, 100)))

        repository.fetchOlder(THREAD, repository.oldestDate()!!)

        assertThat(repository.current.map { it.id }).containsExactly(5L, 10L, 11L).inOrder()
        assertThat(repository.oldestDate()).isEqualTo(50)

        // An empty page while the provider cannot answer: try again later.
        source.hasOlder = null
        repository.fetchOlder(THREAD, 50)
        assertThat(repository.allFetched).isFalse()

        // The provider says nothing older exists: the start of the thread.
        source.hasOlder = false
        repository.fetchOlder(THREAD, 50)
        assertThat(repository.allFetched).isTrue()
    }

    @Test
    fun refreshKeepsPagedInHistoryAndAddsScheduledMessages() {
        val source = FakeSource()
        val repository = ThreadMessagesRepository(source) { now }
        // Older history paged in, then a full fresh page arrives (so older messages exist in the provider).
        val history = page(1, 3, firstId = 1)
        val fresh = page(100, MESSAGES_LIMIT, firstId = 100)
        repository.update { history + fresh.take(10) }
        source.scheduled = listOf(message(9_000, date = (now / 1000).toInt() + 3600, scheduled = true))
        repository.allFetched = true

        repository.refresh(THREAD, fresh)

        val ids = repository.current.map { it.id }
        assertThat(ids.take(3)).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(ids).contains(9_000L)
        assertThat(ids).hasSize(3 + MESSAGES_LIMIT + 1)
        assertThat(repository.allFetched).isFalse()
    }

    @Test
    fun refreshWithTheWholeThreadReplacesTheList() {
        val source = FakeSource()
        val repository = ThreadMessagesRepository(source) { now }
        repository.update { listOf(message(1, 1), message(2, 2)) }

        repository.refresh(THREAD, listOf(message(2, 2), message(3, 3)))

        assertThat(repository.current.map { it.id }).containsExactly(2L, 3L).inOrder()
    }

    @Test
    fun upsertTellsSmsAndMmsWithTheSameIdApart() {
        val repository = ThreadMessagesRepository(FakeSource()) { now }
        repository.upsert(message(57, 10))
        repository.upsert(message(57, 11, isMMS = true))
        repository.upsert(message(57, 10).copy(body = "edited"))

        assertThat(repository.current.map { it.key }).containsExactly(MessageKey(57, false), MessageKey(57, true)).inOrder()
        assertThat(repository.current.first().body).isEqualTo("edited")
    }

    @Test
    fun markAllReadReportsWhetherAnythingWasUnread() {
        val repository = ThreadMessagesRepository(FakeSource()) { now }
        repository.update { listOf(message(1, 1, read = false), message(2, 2)) }

        assertThat(repository.markAllRead(THREAD)).isTrue()
        assertThat(repository.current.all { it.read }).isTrue()
        assertThat(repository.markAllRead(THREAD)).isFalse()
    }

    private companion object {
        const val THREAD = 3L
    }
}
