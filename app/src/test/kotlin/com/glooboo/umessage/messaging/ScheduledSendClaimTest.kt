package com.glooboo.umessage.messaging

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.clearExpiredScheduledMessages
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.extensions.rescheduleAllScheduledMessages
import com.glooboo.umessage.helpers.SCHEDULED_MESSAGE_ID
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageAttachment
import com.glooboo.umessage.receivers.ScheduledMessageReceiver
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowLooper

/**
 * F3: a scheduled message is sent once, however many senders reach it, and one that is overdue goes out late instead
 * of being dropped.
 */
@RunWith(AndroidJUnit4::class)
class ScheduledSendClaimTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarms get() =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).scheduledAlarms

    @Before
    fun setUp() {
        ShadowRecordingSmsManager.reset()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() {
        ScheduledSendClaims.release(ID)
        ShadowAlarmManager.reset()
        resetSharedMessagesDatabase()
    }

    private fun scheduled(
        type: Int = Telephony.Sms.MESSAGE_TYPE_QUEUED,
        dueInMillis: Long = 3_600_000L,
        attachment: MessageAttachment? = null,
    ) = Message(
        id = ID, body = "Happy birthday!", type = type, status = Telephony.Sms.STATUS_NONE,
        participants = arrayListOf(
            SimpleContact(1, 1, "Sara", "", arrayListOf(PhoneNumber("111", 0, "", "111")), ArrayList(), ArrayList())
        ),
        date = ((System.currentTimeMillis() + dueInMillis) / 1000).toInt(), read = false, threadId = ID,
        isMMS = false, attachment = attachment, senderPhoneNumber = "", senderName = "", senderPhotoUri = "",
        subscriptionId = -1, isScheduled = true
    )

    private fun store(message: Message) = offMainThread { context.messagesDB.insertOrUpdate(message) }

    private fun typeInDb(): Int? =
        offMainThread { context.messagesDB.getAllScheduledMessages().firstOrNull { it.id == ID }?.type }

    private fun fire() {
        offMainThread {
            ScheduledMessageReceiver().handleIntent(context, Intent().putExtra(SCHEDULED_MESSAGE_ID, ID))
        }
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun onlyOneOfTwoClaimsWins() {
        store(scheduled())

        val first = offMainThread { ScheduledSendClaims.claim(context, ID) }
        val second = offMainThread { ScheduledSendClaims.claim(context, ID) }

        assertThat(first).isTrue()
        assertThat(second).isFalse()
        assertThat(typeInDb()).isEqualTo(Telephony.Sms.MESSAGE_TYPE_OUTBOX)
    }

    @Test
    fun anAlarmForAMessageAlreadyBeingSentDoesNotSendItAgain() {
        FakeSmsMmsProvider.installSms()
        store(scheduled())
        offMainThread { ScheduledSendClaims.claim(context, ID) }

        fire()

        assertThat(ShadowRecordingSmsManager.textSends).isEmpty()
        assertThat(typeInDb()).isEqualTo(Telephony.Sms.MESSAGE_TYPE_OUTBOX)
    }

    @Test
    fun anAlarmThatFiresTwiceSendsTheMessageOnce() {
        FakeSmsMmsProvider.installSms()
        store(scheduled())

        fire()
        fire()

        assertThat(ShadowRecordingSmsManager.textSends).hasSize(1)
        assertThat(offMainThread { context.messagesDB.getAllScheduledMessages() }).isEmpty()
    }

    @Test
    fun reArmingWhileASendRunsDoesNotSetItsAlarmAgain() {
        store(scheduled(dueInMillis = -5_000L))
        offMainThread { ScheduledSendClaims.claim(context, ID) }

        offMainThread { context.rescheduleAllScheduledMessages() }

        assertThat(alarms).isEmpty()
        assertThat(typeInDb()).isEqualTo(Telephony.Sms.MESSAGE_TYPE_OUTBOX)
    }

    @Test
    fun aSendThatDiedHalfWayIsHeldNotSentAgain() {
        // In the outbox, but no running send owns it: the process died after the claim.
        store(scheduled(type = Telephony.Sms.MESSAGE_TYPE_OUTBOX))

        offMainThread { context.rescheduleAllScheduledMessages() }

        assertThat(alarms).isEmpty()
        assertThat(typeInDb()).isEqualTo(Telephony.Sms.MESSAGE_TYPE_FAILED)
    }

    @Test
    fun aQueuedMessageIsArmedOnReschedule() {
        store(scheduled())

        offMainThread { context.rescheduleAllScheduledMessages() }

        assertThat(alarms).hasSize(1)
    }

    @Test
    fun anOverdueMessageIsSetToGoOutLateNotDeleted() {
        store(scheduled(dueInMillis = -3_600_000L))

        offMainThread { context.clearExpiredScheduledMessages(ID) }

        assertThat(typeInDb()).isEqualTo(Telephony.Sms.MESSAGE_TYPE_QUEUED)
        assertThat(alarms).hasSize(1)
    }

    @Test
    fun anOverdueMessageIsSentOnceWhenItsAlarmFires() {
        FakeSmsMmsProvider.installSms()
        store(scheduled(dueInMillis = -3_600_000L))

        offMainThread { context.clearExpiredScheduledMessages(ID) }
        fire()
        fire()

        assertThat(ShadowRecordingSmsManager.textSends).hasSize(1)
    }

    @Test
    fun theUsersRetryMayTakeAHeldMessageButAnAlarmMayNot() {
        store(scheduled(type = Telephony.Sms.MESSAGE_TYPE_FAILED))

        val byAlarm = offMainThread { ScheduledSendClaims.claim(context, ID) }
        val byUser = offMainThread { ScheduledSendClaims.claim(context, ID, allowHeld = true) }

        assertThat(byAlarm).isFalse()
        assertThat(byUser).isTrue()
    }

    @Test
    fun whatToDoWithAScheduledRow() {
        val queued = Telephony.Sms.MESSAGE_TYPE_QUEUED
        val outbox = Telephony.Sms.MESSAGE_TYPE_OUTBOX
        val failed = Telephony.Sms.MESSAGE_TYPE_FAILED
        assertThat(scheduledActionFor(queued, inFlight = false)).isEqualTo(ScheduledAction.ARM)
        assertThat(scheduledActionFor(failed, inFlight = false)).isEqualTo(ScheduledAction.SKIP)
        assertThat(scheduledActionFor(outbox, inFlight = true)).isEqualTo(ScheduledAction.SKIP)
        assertThat(scheduledActionFor(outbox, inFlight = false)).isEqualTo(ScheduledAction.HOLD)
    }

    @Test
    fun anAttachmentThatWentOutIsRemovedFromTheScheduledRow() {
        // MS6: photo 1 was queued, photo 2 failed: a later Retry must send only photo 2.
        fun photo(n: Int) = Attachment(null, ID, "content://p/$n", "image/jpeg", 0, 0, "p$n.jpg")
        val message = scheduled(attachment = MessageAttachment(ID, "", arrayListOf(photo(1), photo(2))))
        store(message)

        offMainThread { context.scheduledAttachmentTracker(message)(photo(1)) }

        val left = offMainThread { context.messagesDB.getAllScheduledMessages().single() }
        assertThat(left.attachment?.attachments?.map { it.uriString }).containsExactly("content://p/2")
    }

    private companion object {
        const val ID = 1_234_567_890L
    }
}
