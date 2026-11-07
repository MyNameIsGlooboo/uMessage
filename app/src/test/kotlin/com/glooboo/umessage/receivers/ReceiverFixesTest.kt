package com.glooboo.umessage.receivers

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.provider.Telephony.Mms
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.messaging.STALE_OUTBOX_AGE_MILLIS
import com.glooboo.umessage.messaging.failStaleOutboxMessages
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.SmsPdus
import com.glooboo.umessage.testing.SpamTestWiring
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** I16 (date_sent), B19 (stuck MMS), PF-B6 (a notification action must not crash the app). */
@RunWith(AndroidJUnit4::class)
class ReceiverFixesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        SpamTestWiring.uninstall()
        resetSharedMessagesDatabase()
    }

    @Test
    fun theCarriersSendTimeIsStoredAsDateSent() {
        val telephony = FakeTelephonyProvider.install()
        SpamTestWiring.freshSpamDatabase(context)
        SpamTestWiring.resetPendingLeases()
        UMessage.log(context).clear()
        SpamTestWiring.installFilter { FilterDecision.Allow(DecisionReason.NO_MATCH) }

        val intent = SmsPdus.deliverIntent(SmsPdus.deliverPdu("+15551234567", "Hello there"))
        SmsReceiver().handle(context, intent)

        val row = telephony.smsRows().single()
        // The test PDU is stamped 2026-01-01 12:34:56 by the service centre, long before it "arrived" now.
        val dateSent = row["date_sent"] as Long
        assertThat(dateSent).isGreaterThan(0L)
        assertThat(dateSent).isLessThan(row["date"] as Long)
    }

    @Test
    fun aPictureMessageStuckInTheOutboxIsMarkedFailed() {
        val provider = FakeSmsMmsProvider.installMms()
        val now = 1_800_000_000_000L
        fun insert(box: Int, ageMillis: Long) = context.contentResolver.insert(Mms.CONTENT_URI, ContentValues().apply {
            put(Mms.MESSAGE_BOX, box)
            put(Mms.DATE, (now - ageMillis) / 1000) // the MMS provider keeps seconds
        })!!.lastPathSegment!!.toLong()
        val stuck = insert(Mms.MESSAGE_BOX_OUTBOX, STALE_OUTBOX_AGE_MILLIS + 60_000)
        val inFlight = insert(Mms.MESSAGE_BOX_OUTBOX, 5_000)
        val sent = insert(Mms.MESSAGE_BOX_SENT, STALE_OUTBOX_AGE_MILLIS * 10)

        val marked = offMainThread { context.failStaleOutboxMessages(now = now) }

        assertThat(marked).isEqualTo(1)
        assertThat(provider.intColumn("mms", stuck, "msg_box")).isEqualTo(Mms.MESSAGE_BOX_FAILED)
        assertThat(provider.intColumn("mms", inFlight, "msg_box")).isEqualTo(Mms.MESSAGE_BOX_OUTBOX)
        assertThat(provider.intColumn("mms", sent, "msg_box")).isEqualTo(Mms.MESSAGE_BOX_SENT)
    }

    @Test
    fun aNotificationActionThatThrowsDoesNotKillTheApp() {
        // E.g. a SecurityException from the SMS provider once uMessage is no longer the default SMS app.
        val uncaught = AtomicReference<Throwable?>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.set(e) }
        try {
            val ran = CountDownLatch(1)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: android.content.Intent) = Unit
            }

            receiver.runInBackground("test") {
                ran.countDown()
                throw SecurityException("not the default SMS app")
            }

            assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue()
            Thread.sleep(300)
            assertThat(uncaught.get()).isNull()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
