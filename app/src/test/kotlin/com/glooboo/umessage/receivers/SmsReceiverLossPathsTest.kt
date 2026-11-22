package com.glooboo.umessage.receivers

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.testing.SmsPdus
import com.glooboo.umessage.testing.SpamTestWiring
import com.glooboo.umessage.testing.WatchedBroadcast
import com.glooboo.umessage.testing.joinAppBackgroundThreads
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where an incoming SMS could be lost: a PDU that cannot be read, a failed write of the pending queue, and a failure
 * inside [SmsReceiver.onReceive] (the broadcast must always be finished, or the system holds the process for it).
 */
@RunWith(AndroidJUnit4::class)
class SmsReceiverLossPathsTest {

    /** The flag-off cases as before; the M-T5 cases switch it on in their body (the rule puts it back). */
    @get:Rule val flag = ProviderTruthFlagRule(enabled = false)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var telephony: FakeTelephonyProvider
    private lateinit var spamDb: SpamDatabase

    @Before
    fun setUp() {
        telephony = FakeTelephonyProvider.install()
        spamDb = SpamTestWiring.freshSpamDatabase(context)
        SpamTestWiring.resetPendingLeases()
        UMessage.log(context).clear()
        SpamTestWiring.installFilter { FilterDecision.Allow(DecisionReason.NO_MATCH) }
    }

    @After
    fun tearDown() {
        // onReceive tidies up on a worker after finishing the broadcast: let it end before the database goes.
        joinAppBackgroundThreads()
        SpamTestWiring.uninstall()
        SpamTestWiring.clearSpamDatabase()
    }

    private fun inbox() = telephony.smsRows().map { it["body"] }
    private fun logReasons() = UMessage.log(context).entries().map { it.reason }
    private fun garbagePduIntent() = SmsPdus.deliverIntent(byteArrayOf(1, 2, 3))
    private fun goodIntent(body: String) = SmsPdus.deliverIntent(SmsPdus.deliverPdu("+15551234567", body))
    private fun flagOn() {
        ProviderTruthFlag.enabled = true
    }

    @Test
    fun anUnparsablePdu_isLoggedAndStoresNothing() {
        SmsReceiver().handle(context, garbagePduIntent())

        assertThat(inbox()).isEmpty()
        assertThat(spamDb.spamDao().getPending()).isEmpty()
        assertThat(logReasons().any { it == "NO_PDU" || it.startsWith("UNREADABLE_PDU_") }).isTrue()
    }

    @Test
    fun anIntentWithoutPdus_isLoggedAndStoresNothing() {
        SmsReceiver().handle(context, Intent("android.provider.Telephony.SMS_DELIVER"))

        assertThat(inbox()).isEmpty()
        assertThat(spamDb.spamDao().getPending()).isEmpty()
        assertThat(logReasons().any { it == "NO_PDU" || it.startsWith("UNREADABLE_PDU_") }).isTrue()
    }

    @Test
    fun aFailedQueueWrite_stillDeliversTheMessageToTheInboxOnce() {
        // The write-ahead row cannot be written (add returns 0); the message is processed directly all the same.
        spamDb.close()

        SmsReceiver().handle(context, goodIntent("Your code is 4821"))

        assertThat(inbox()).containsExactly("Your code is 4821")
    }

    @Test
    fun whenNeitherTheQueueNorTheInboxTakesIt_handleEndsWithoutThrowing() {
        spamDb.close()
        telephony.failInserts = true

        SmsReceiver().handle(context, goodIntent("Your code is 4821"))

        assertThat(inbox()).isEmpty()
    }

    @Test
    fun mT5_whenNeitherTheQueueNorTheInboxTakesIt_theLossIsLogged_neverSilent() {
        spamDb.close()
        telephony.failInserts = true

        SmsReceiver().handle(context, goodIntent("Your code is 4821"))

        assertThat(inbox()).isEmpty()
        assertThat(logReasons()).contains("LOST")
    }

    @Test
    fun mT5_withProviderTruth_anUnparsablePdu_isLoggedAndStoresNothing() {
        flagOn()

        SmsReceiver().handle(context, garbagePduIntent())

        assertThat(inbox()).isEmpty()
        assertThat(spamDb.spamDao().getPending()).isEmpty()
        assertThat(logReasons().any { it == "NO_PDU" || it.startsWith("UNREADABLE_PDU_") }).isTrue()
    }

    @Test
    fun mT5_withProviderTruth_aFailedQueueWrite_stillDeliversTheMessageToTheInboxOnce() {
        flagOn()
        spamDb.close()

        SmsReceiver().handle(context, goodIntent("Your code is 4821"))

        assertThat(inbox()).containsExactly("Your code is 4821")
    }

    @Test
    fun mT5_withProviderTruth_whenNeitherTheQueueNorTheInboxTakesIt_theLossIsLogged() {
        flagOn()
        spamDb.close()
        telephony.failInserts = true

        SmsReceiver().handle(context, goodIntent("Your code is 4821"))

        assertThat(inbox()).isEmpty()
        assertThat(logReasons()).contains("LOST")
    }

    @Test
    fun onReceive_finishesTheBroadcastAfterStoringAMessage() {
        val receiver = SmsReceiver()
        val broadcast = WatchedBroadcast.attachTo(receiver)

        receiver.onReceive(context, goodIntent("Hello there"))

        assertThat(broadcast.awaitFinished()).isTrue()
        joinAppBackgroundThreads()
        assertThat(inbox()).containsExactly("Hello there")
    }

    @Test
    fun onReceive_finishesTheBroadcastForAnUnparsablePdu() {
        val receiver = SmsReceiver()
        val broadcast = WatchedBroadcast.attachTo(receiver)

        receiver.onReceive(context, garbagePduIntent())

        assertThat(broadcast.awaitFinished()).isTrue()
    }

    @Test
    fun onReceive_finishesTheBroadcastWhenHandlingThrows() {
        val receiver = SmsReceiver()
        val broadcast = WatchedBroadcast.attachTo(receiver)
        // Every read of the SIM extra fails: handle() throws before anything is stored.
        val failing = object : Intent("android.provider.Telephony.SMS_DELIVER") {
            override fun getIntExtra(name: String?, defaultValue: Int): Int = error("broken intent")
        }

        receiver.onReceive(context, failing)

        assertThat(broadcast.awaitFinished()).isTrue()
    }
}
