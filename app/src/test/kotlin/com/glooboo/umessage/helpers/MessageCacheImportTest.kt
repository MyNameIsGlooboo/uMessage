package com.glooboo.umessage.helpers

import com.glooboo.umessage.models.Message
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** DL-10 / MC-16: the cache import reads whole threads and resumes after an interrupted start. */
class MessageCacheImportTest {

    private class MemoryProgress : MessageCacheImport.Progress {
        override var complete = false
        val done = HashSet<Long>()
        override fun isThreadDone(threadId: Long) = threadId in done
        override fun markThreadDone(threadId: Long) {
            done.add(threadId)
        }
    }

    /** A provider that pages like getMessages / getOlderThreadMessages: newest first, [MESSAGES_LIMIT] per page. */
    private class FakeProvider(val threads: Map<Long, List<Message>>) {
        val reads = ArrayList<Long>()
        var failThread: Long? = null

        fun page(threadId: Long, cutoff: Int, loaded: List<Message>): List<Message> {
            reads.add(threadId)
            if (threadId == failThread) error("provider error")
            val all = threads[threadId].orEmpty().sortedByDescending { it.date }
            if (cutoff == -1) return all.take(MESSAGES_LIMIT)
            val loadedKeys = loaded.map { it.id }.toSet()
            val sameSecond = all.filter { it.date <= cutoff }.take(MESSAGES_LIMIT).filterNot { it.id in loadedKeys }
            if (sameSecond.isNotEmpty()) return sameSecond
            return all.filter { it.date < cutoff }.take(MESSAGES_LIMIT)
        }
    }

    private fun message(id: Long, threadId: Long, date: Int) = Message(
        id = id, body = "m$id", type = 1, status = -1, participants = ArrayList(), date = date, read = true,
        threadId = threadId, isMMS = false, attachment = null, senderPhoneNumber = "", senderName = "",
        senderPhotoUri = "", subscriptionId = 0,
    )

    private fun thread(threadId: Long, count: Int, firstId: Long = threadId * 1000) =
        // Three messages per second, so pages end inside a second.
        (0 until count).map { message(firstId + it, threadId, date = 10_000 + it / 3) }

    @Test
    fun importsWholeThreadsNotJustTheNewestPage() {
        val provider = FakeProvider(mapOf(1L to thread(1, 175), 2L to thread(2, 7)))
        val saved = ArrayList<Message>()
        val progress = MemoryProgress()

        val complete = MessageCacheImport(progress, provider::page) { saved.addAll(it) }.run(listOf(1, 2))

        assertThat(complete).isTrue()
        assertThat(progress.complete).isTrue()
        assertThat(saved.map { it.id }.toSet()).hasSize(182)
    }

    @Test
    fun resumesWhereAnInterruptedImportStopped() {
        val provider = FakeProvider(mapOf(1L to thread(1, 60), 2L to thread(2, 60), 3L to thread(3, 5)))
        val saved = HashSet<Long>()
        val progress = MemoryProgress()
        provider.failThread = 2

        val first = MessageCacheImport(progress, provider::page) { m -> saved.addAll(m.map { it.id }) }
            .run(listOf(1, 2, 3))
        assertThat(first).isFalse()
        assertThat(progress.complete).isFalse()
        assertThat(progress.done).containsExactly(1L, 3L)

        provider.failThread = null
        provider.reads.clear()
        val second = MessageCacheImport(progress, provider::page) { m -> saved.addAll(m.map { it.id }) }
            .run(listOf(1, 2, 3))
        assertThat(second).isTrue()
        assertThat(provider.reads.toSet()).containsExactly(2L)
        assertThat(saved).hasSize(125)
    }

    @Test
    fun cancelledThreadIsNotMarkedDone() {
        val provider = FakeProvider(mapOf(1L to thread(1, 120)))
        val progress = MemoryProgress()
        var pages = 0
        val import = MessageCacheImport(progress, { t, c, l -> pages++; provider.page(t, c, l) }) {}

        assertThat(import.run(listOf(1)) { pages >= 1 }).isFalse()
        assertThat(progress.done).isEmpty()
        assertThat(progress.complete).isFalse()
    }

    @Test
    fun completeImportReadsNothing() {
        val provider = FakeProvider(mapOf(1L to thread(1, 10)))
        val progress = MemoryProgress().apply { complete = true }

        assertThat(MessageCacheImport(progress, provider::page) {}.run(listOf(1))).isTrue()
        assertThat(provider.reads).isEmpty()
    }

    @Test
    fun emptyThreadIsDone() {
        val provider = FakeProvider(emptyMap())
        val progress = MemoryProgress()
        assertThat(MessageCacheImport(progress, provider::page) {}.run(listOf(4))).isTrue()
        assertThat(progress.done).containsExactly(4L)
    }
}
