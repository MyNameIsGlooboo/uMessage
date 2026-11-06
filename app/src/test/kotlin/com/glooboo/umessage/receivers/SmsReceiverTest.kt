package com.glooboo.umessage.receivers

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.data.PendingSmsEntity
import com.glooboo.umessage.spam.data.PendingSmsPolicy
import com.glooboo.umessage.spam.data.PendingSmsQueue
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.SmsPdus
import com.glooboo.umessage.testing.SpamTestWiring
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The incoming SMS path end to end: PDU intent -> pending queue -> filter -> inbox or Blocked, and the retry when the
 * save fails. Telephony is replaced by [FakeTelephonyProvider]; the spam database is in memory.
 */
@RunWith(AndroidJUnit4::class)
class SmsReceiverTest {

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
        SpamTestWiring.uninstall()
        SpamTestWiring.clearSpamDatabase()
    }

    private fun receive(sender: String, vararg bodies: String) {
        val pdus = bodies.map { SmsPdus.deliverPdu(sender, it) }.toTypedArray()
        SmsReceiver().handle(context, SmsPdus.deliverIntent(*pdus))
    }

    private fun inbox() = telephony.smsRows()
    private fun pendingRows() = spamDb.spamDao().getPending()
    private fun logReasons() = UMessage.log(context).entries().map { it.reason }

    @Test
    fun allowedSms_landsInInboxAndLeavesNoPendingRow() {
        receive("+15551234567", "Hello there")

        assertThat(inbox().map { it["address"] to it["body"] }).containsExactly("+15551234567" to "Hello there")
        assertThat(pendingRows()).isEmpty()
    }

    @Test
    fun multipartSms_isJoinedIntoOneMessage() {
        receive("+15551234567", "Part one, ", "part two")

        assertThat(inbox().map { it["body"] }).containsExactly("Part one, part two")
    }

    @Test
    fun filterThrows_messageStillLandsInInbox() {
        SpamTestWiring.installFilter { error("filter broke") }

        receive("+15551234567", "Your bank code 482913")

        assertThat(inbox().map { it["body"] }).containsExactly("Your bank code 482913")
        assertThat(pendingRows()).isEmpty()
    }

    @Test
    fun blankSender_isKeptUnderUnknownAndLogged() {
        receive("", "Your plan renews soon")

        val rows = inbox()
        assertThat(rows.map { it["address"] to it["body"] })
            .containsExactly(SmsReceiver.UNKNOWN_SENDER to "Your plan renews soon")
        assertThat(rows.single()["thread_id"] as Long).isGreaterThan(0L)
        assertThat(logReasons()).contains("NO_SENDER")
        assertThat(pendingRows()).isEmpty()
    }

    @Test
    fun saveFails_rowStaysQueuedAndARetryIsBooked() {
        telephony.failInserts = true

        receive("+15551234567", "Your code is 4821")

        assertThat(inbox()).isEmpty()
        val pending = pendingRows()
        assertThat(pending.map { it.body }).containsExactly("Your code is 4821")
        assertThat(logReasons().any { it.startsWith("SAVE_FAILED_") }).isTrue()

        val alarms = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).scheduledAlarms
        assertThat(alarms).hasSize(1)
        val retry = shadowOf(alarms.single().operation).savedIntent
        assertThat(retry.component?.className).isEqualTo(PendingSmsRetryReceiver::class.java.name)
        assertThat(retry.getIntExtra(PendingSmsRetryReceiver.EXTRA_ATTEMPT, -1)).isEqualTo(0)
    }

    @Test
    fun saveFails_thenRetryDeliversExactlyOnce() {
        telephony.failInserts = true
        receive("+15551234567", "Your code is 4821")
        val row = pendingRows().single()
        telephony.failInserts = false

        // The receiver released its lease, so the retry (a minute later) does not wait the 5-minute lease out.
        val retryAt = row.createdAt + PendingSmsRetry.FIRST_DELAY_MS
        assertThat(PendingSmsQueue.replay(context, retryAt)).isEqualTo(0)
        assertThat(PendingSmsQueue.replay(context, retryAt + 1_000)).isEqualTo(0)

        assertThat(inbox().map { it["body"] }).containsExactly("Your code is 4821")
        assertThat(pendingRows()).isEmpty()
    }

    @Test
    fun retryReceiver_booksTheNextAttemptWhileRowsStillFail() {
        telephony.failInserts = true
        spamDb.spamDao().insertPending(pendingRow(createdAt = System.currentTimeMillis() - PendingSmsPolicy.GRACE_MS))

        PendingSmsRetry.run(context, attempt = 2)

        val alarms = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).scheduledAlarms
        assertThat(shadowOf(alarms.single().operation).savedIntent.getIntExtra(PendingSmsRetryReceiver.EXTRA_ATTEMPT, -1))
            .isEqualTo(3)
    }

    @Test
    fun retryReceiver_stopsBookingAfterTheLastAttempt() {
        telephony.failInserts = true
        spamDb.spamDao().insertPending(pendingRow(createdAt = System.currentTimeMillis() - PendingSmsPolicy.GRACE_MS))

        PendingSmsRetry.run(context, attempt = PendingSmsRetry.MAX_ATTEMPTS - 1)

        assertThat(shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).scheduledAlarms).isEmpty()
        assertThat(pendingRows()).hasSize(1)
    }

    @Test
    fun retryDelays_doubleFromOneMinute() {
        assertThat((0 until PendingSmsRetry.MAX_ATTEMPTS).map { PendingSmsRetry.delayFor(it) / 60_000 })
            .containsExactly(1L, 2L, 4L, 8L, 16L, 32L).inOrder()
    }

    @Test
    fun expiredPendingRow_skipsTheFilterAndIsDelivered() {
        SpamTestWiring.installFilter { FilterDecision.Block(DecisionReason.BLOCK_RULE) }
        val now = System.currentTimeMillis()
        spamDb.spamDao().insertPending(pendingRow(createdAt = now - PendingSmsPolicy.MAX_AGE_MS - 1))

        assertThat(PendingSmsQueue.replay(context, now)).isEqualTo(0)

        assertThat(inbox().map { it["body"] }).containsExactly("left behind")
        assertThat(pendingRows()).isEmpty()
    }

    @Test
    fun pendingRowAlreadyInInbox_isNotInsertedAgain() {
        val now = System.currentTimeMillis()
        val row = pendingRow(createdAt = now - PendingSmsPolicy.GRACE_MS)
        telephony.addSms(row.address, row.body, row.date, threadId = 1)
        spamDb.spamDao().insertPending(row)

        PendingSmsQueue.replay(context, now)

        assertThat(inbox()).hasSize(1)
        assertThat(pendingRows()).isEmpty()
    }

    private fun pendingRow(createdAt: Long) = PendingSmsEntity(
        address = "+15551234567",
        subject = "",
        body = "left behind",
        date = createdAt,
        subscriptionId = 1,
        status = -1,
        createdAt = createdAt,
    )
}
