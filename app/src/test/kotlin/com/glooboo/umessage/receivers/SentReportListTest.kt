package com.glooboo.umessage.receivers

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony.Sms
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.updateLastConversationMessage
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.testing.CountingInboxProvider
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R3-1, R3-3: a sent report names its chat (J2-4), so the list no longer runs a full pass for it. The report reads that
 * one chat from the store again, so its row is right even when nothing saved the sent text: "respond via message" from
 * the dialer, a carrier report, or a Back before the chat screen saved its copy.
 */
@RunWith(AndroidJUnit4::class)
class SentReportListTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var provider: CountingInboxProvider

    @Before
    fun setUp() {
        provider = CountingInboxProvider.install()
        provider.seed(threads = THREADS.toInt(), messagesPerThread = 2)
        offMainThread { context.updateLastConversationMessage((1L..THREADS).toList()) }
    }

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    private fun saved(): List<Conversation> = offMainThread { context.conversationsDB.getAllRows().filterNot { it.isArchived } }

    @Test
    fun aSentReportMovesAnOldRowUpWithTheNewText() {
        val before = saved().single { it.threadId == THREAD }
        assertThat(saved().maxBy { it.date }.threadId).isNotEqualTo(THREAD)
        // Sent by another app's request: the provider has the text, the app's saved row and message cache do not.
        val uri = context.contentResolver.insert(
            Sms.CONTENT_URI,
            ContentValues().apply {
                put(Sms.THREAD_ID, THREAD)
                put(Sms.ADDRESS, CountingInboxProvider.address(THREAD))
                put(Sms.BODY, TEXT)
                put(Sms.DATE, CountingInboxProvider.BASE_MS + 999_000_000)
                put(Sms.TYPE, Sms.MESSAGE_TYPE_OUTBOX)
            },
        )!!

        offMainThread {
            SmsStatusSentReceiver().handle(
                context,
                Intent(SendStatusReceiver.SMS_SENT_ACTION, uri).putExtra(SendStatusReceiver.EXTRA_PART_COUNT, 1),
                Activity.RESULT_OK,
            )
        }

        val row = saved().single { it.threadId == THREAD }
        assertThat(row.snippet).isEqualTo(TEXT)
        assertThat(row.date).isGreaterThan(before.date)
        assertThat(saved().maxBy { it.date }.threadId).isEqualTo(THREAD)
    }

    private companion object {
        const val THREADS = 5L
        const val THREAD = 2L
        const val TEXT = "On my way"
    }
}
