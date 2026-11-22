package com.glooboo.umessage.receivers

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.data.ProviderTruthSmsFixture
import com.glooboo.umessage.testing.SmsPdus
import com.glooboo.umessage.testing.offMainThread
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M-T5, J3-6: a text from a sender the real filter judges Spam, while the queue write fails (spamless.db closed), with
 * provider truth on. The text is never lost and never silent by mistake: it is stored once, either read in the
 * sender's Spam chat (listed there) or unread in Messages with a notification. It is never forwarded.
 */
@RunWith(AndroidJUnit4::class)
class SmsReceiverSpamLossTest : ProviderTruthSmsFixture() {

    @Test
    fun aSpamTextWhoseQueueWriteFails_isStoredOnce_inSpamOrAnnouncedUnread_andNotForwarded() {
        forwardTo(TARGET)
        db.close()

        offMainThread {
            SmsReceiver().handle(app, SmsPdus.deliverIntent(SmsPdus.deliverPdu(STRANGER, TEXT)), housekeeping = false)
        }

        val row = inbox().single()
        assertThat(row["body"]).isEqualTo(TEXT)
        val inSpam = row.int("read") == 1 && spamList().contains(store.threadOf(STRANGER))
        val announced = row.int("read") == 0 && posted.size == 1
        assertThat(inSpam || announced).isTrue()
        assertThat(forwards()).isEmpty()
    }

    private companion object {
        const val TEXT = "Big sale today only"
    }
}
