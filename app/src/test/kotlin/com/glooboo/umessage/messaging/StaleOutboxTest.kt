package com.glooboo.umessage.messaging

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony.Sms
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StaleOutboxTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_800_000_000_000L

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    private fun insert(address: String, type: Int, ageMillis: Long) {
        context.contentResolver.insert(Sms.CONTENT_URI, ContentValues().apply {
            put(Sms.ADDRESS, address)
            put(Sms.BODY, "Call me when you land")
            put(Sms.DATE, now - ageMillis)
            put(Sms.TYPE, type)
        })
    }

    @Test
    fun messageStuckSendingAfterARestartIsMarkedFailed() {
        // RM-07: the phone restarted mid-send, so no sent report will ever come for these rows.
        val provider = FakeSmsMmsProvider.installSms()
        insert("stuck", Sms.MESSAGE_TYPE_OUTBOX, ageMillis = STALE_OUTBOX_AGE_MILLIS + 1)
        insert("in-flight", Sms.MESSAGE_TYPE_OUTBOX, ageMillis = 5_000)
        insert("old-sent", Sms.MESSAGE_TYPE_SENT, ageMillis = STALE_OUTBOX_AGE_MILLIS * 10)

        val marked = offMainThread { context.failStaleOutboxMessages(now = now) }

        assertThat(marked).isEqualTo(1)
        assertThat(provider.smsTypes()).containsExactly(
            "stuck" to Sms.MESSAGE_TYPE_FAILED,
            "in-flight" to Sms.MESSAGE_TYPE_OUTBOX,
            "old-sent" to Sms.MESSAGE_TYPE_SENT,
        ).inOrder()
    }

    @Test
    fun noProviderMeansNothingMarkedAndNoCrash() {
        assertThat(offMainThread { context.failStaleOutboxMessages(now = now) }).isEqualTo(0)
    }
}
