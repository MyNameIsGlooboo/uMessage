package com.glooboo.umessage.receivers

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.data.ProviderTruthSmsFixture
import com.glooboo.umessage.testing.SmsPdus
import com.glooboo.umessage.testing.offMainThread
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M-T5, J3-6, R2-2: a text the filter judges Spam while the pending-queue write fails, with provider truth on. Only the
 * queue is broken (a trigger refuses inserts into pending_sms), so the real filter, with its rules and settings, gives
 * the Spam verdict. A stub filter cannot: the provider-truth path judges with SpamEngine.judge, which needs the real
 * filter and treats any other as failed (Messages). The text is stored once, read in its Spam chat, silent, and not
 * forwarded.
 *
 * The chat is cached first, as the app does from the phone's store: the Spam list shows only cached chats, and the
 * fixture's stand-in store has no conversation query, so the app cannot cache it here by itself. R3-1: in the
 * second test it is not cached at arrival; the app then asks for the chat at once, and it is listed once cached.
 */
@RunWith(AndroidJUnit4::class)
class SmsReceiverSpamLossTest : ProviderTruthSmsFixture() {

    @Test
    fun aSpamTextWhoseQueueWriteFails_isStoredOnceInItsSpamChat_silent_andNotForwarded() {
        forwardTo(TARGET)
        val thread = store.threadOf(STRANGER)
        cache(STRANGER, thread)
        refuseQueueWrites()

        receiveText()

        // The queue write did fail: nothing is queued.
        assertThat(offMainThread { db.spamDao().getPending() }).isEmpty()
        val row = inbox().single()
        assertThat(row["body"]).isEqualTo(TEXT)
        assertThat(row.int("read")).isEqualTo(1)
        assertThat(spamRow()?.sender).isEqualTo(STRANGER)
        assertThat(spamList()).contains(thread)
        assertThat(posted).isEmpty()
        assertThat(forwards()).isEmpty()
    }

    @Test
    fun r3_1_aSpamTextWhoseChatIsNotCachedAtArrival_asksForItsChatAtOnce_andIsListedInSpamOnceCached() {
        forwardTo(TARGET)
        val thread = store.threadOf(STRANGER)
        refuseQueueWrites()

        receiveText()

        val row = inbox().single()
        assertThat(row["body"]).isEqualTo(TEXT)
        assertThat(row.int("read")).isEqualTo(1)
        // The Spam row is written before the chat is cached; the store lists no such thread, so no list shows it yet.
        assertThat(spamRow()?.sender).isEqualTo(STRANGER)
        assertThat(spamList()).doesNotContain(thread)
        // R3-1: the app asked for that chat at once, instead of waiting for the next full pass.
        assertThat(askedToCache.toList()).contains(thread)
        assertThat(posted).isEmpty()
        assertThat(forwards()).isEmpty()

        // Once the chat is cached (what the request does when the store lists the thread), it is listed in Spam.
        cache(STRANGER, thread)
        assertThat(spamList()).contains(thread)
        assertThat(messagesList()).doesNotContain(thread)
    }

    /** A trigger refuses every insert into the pending-SMS queue. */
    private fun refuseQueueWrites() = offMainThread {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER refuse_pending BEFORE INSERT ON pending_sms BEGIN SELECT RAISE(ABORT, 'refused'); END"
        )
    }

    private fun receiveText() = offMainThread {
        SmsReceiver().handle(app, SmsPdus.deliverIntent(SmsPdus.deliverPdu(STRANGER, TEXT)), housekeeping = false)
    }

    private companion object {
        const val TEXT = "Big sale today only"
    }
}
