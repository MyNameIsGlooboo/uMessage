package com.glooboo.umessage.spam.data

import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S13 and M-D2: auto-delete of Spam in the phone's store deletes only received texts of Spam chats that arrived while
 * the chat was in Spam and are older than the chosen days; sent, scheduled, starred and noted texts stay; a stale
 * cache row deletes nothing; nothing without the default role or with "Keep forever". Every number is made up.
 */
@RunWith(AndroidJUnit4::class)
class SpamAutoDeleteTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val thread =
        SpamThread(threadId = 7, sender = SENDER, cause = SpamCause.UNKNOWN, spamSince = now - 60 * day)

    private class FakeSource(
        val threads: List<SpamThread>,
        val messages: MutableList<Message>,
        var isDefault: Boolean = true,
        val kept: Set<Long> = emptySet(),
        /** Cached ids whose store row is another message now (a rebuilt store gave the id again). */
        val stale: Set<Long> = emptySet(),
    ) : AutoDeleteSource {
        val deleted = mutableListOf<Long>()
        val after = mutableListOf<Long>()

        override fun canDelete() = isDefault

        override fun spamThreads() = threads

        override fun cached(threadId: Long) = messages.filter { it.threadId == threadId }

        override fun isKept(message: Message) = message.id in kept

        override fun storeMatches(message: Message, thread: SpamThread) =
            message.id !in stale && message.threadId == thread.threadId

        override fun delete(message: Message): Boolean {
            deleted += message.id
            messages.remove(message)
            return true
        }

        override fun afterThread(threadId: Long) {
            after += threadId
        }
    }

    private fun text(id: Long, at: Long, type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX, scheduled: Boolean = false) =
        Message(
            id = id, body = "Advert $id", type = type, status = 0, participants = ArrayList(),
            date = (at / 1000).toInt(), read = true, threadId = thread.threadId, isMMS = false, attachment = null,
            senderPhoneNumber = SENDER, senderName = "", senderPhotoUri = "", subscriptionId = 1,
            isScheduled = scheduled,
        )

    @Test
    fun onlyReceivedTextsThatArrivedInSpamAndAreOlderThanTheDaysGo() {
        val source = FakeSource(
            listOf(thread),
            mutableListOf(
                text(1, now - 40 * day),
                text(2, now - 10 * day),
                // Before the chat entered Spam: it was a Messages text then.
                text(3, now - 90 * day),
                text(4, now - 40 * day, type = Telephony.Sms.MESSAGE_TYPE_SENT),
                text(5, now - 40 * day, scheduled = true),
            ),
        )

        val deleted = SpamAutoDeleter(source) { now }.run(days = 30)

        assertThat(deleted).isEqualTo(1)
        assertThat(source.deleted).containsExactly(1L)
        assertThat(source.after).containsExactly(thread.threadId)
    }

    @Test
    fun starredAndNotedTextsStay() {
        val messages = mutableListOf(text(1, now - 40 * day), text(2, now - 41 * day))
        val source = FakeSource(listOf(thread), messages, kept = setOf(2))

        SpamAutoDeleter(source) { now }.run(days = 30)

        assertThat(source.deleted).containsExactly(1L)
    }

    @Test
    fun aStaleCacheRowDeletesNothing() {
        val source = FakeSource(listOf(thread), mutableListOf(text(1, now - 40 * day)), stale = setOf(1))

        val deleted = SpamAutoDeleter(source) { now }.run(days = 30)

        assertThat(deleted).isEqualTo(0)
        assertThat(source.after).isEmpty()
    }

    @Test
    fun keepForeverOrNoDefaultRoleDeletesNothing() {
        val messages = mutableListOf(text(1, now - 400 * day))
        val source = FakeSource(listOf(thread), messages)

        assertThat(SpamAutoDeleter(source) { now }.run(days = 0)).isEqualTo(0)
        source.isDefault = false
        assertThat(SpamAutoDeleter(source) { now }.run(days = 30)).isEqualTo(0)
        assertThat(source.deleted).isEmpty()
    }

    @Test
    fun aSpamSinceOfZeroOrOneIsUnknownAndNothingOfTheChatIsDeleted() {
        for (since in listOf(0L, 1L)) {
            val unknown = thread.copy(spamSince = since)
            val source = FakeSource(listOf(unknown), mutableListOf(text(1, now - 40 * day), text(2, now - day)))

            SpamAutoDeleter(source) { now }.run(days = 30)

            assertThat(source.deleted).isEmpty()
        }
    }

    @Test
    fun theTextThatPutTheChatInSpamCountsAlthoughTheCacheKeepsWholeSeconds() {
        // spam_since in milliseconds, the cached date cut to the second: the same arrival.
        val since = now - 40 * day + 789
        val source = FakeSource(listOf(thread.copy(spamSince = since)), mutableListOf(text(1, since)))

        SpamAutoDeleter(source) { now }.run(days = 30)

        assertThat(source.deleted).containsExactly(1L)
    }

    @Test
    fun aChatThatIsNotInSpamIsNeverLookedAt() {
        val other = text(9, now - 40 * day).copy(threadId = 99)
        val source = FakeSource(listOf(thread), mutableListOf(other))

        SpamAutoDeleter(source) { now }.run(days = 30)

        assertThat(source.deleted).isEmpty()
    }

    private companion object {
        const val SENDER = "+15550001234"
    }
}
