package com.glooboo.umessage.backup

import android.content.Context
import android.provider.Telephony
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.helpers.BackupJob
import com.glooboo.umessage.models.AppDataBackup
import com.glooboo.umessage.models.ImportSummary
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageAttachment
import com.glooboo.umessage.models.MessageReminder
import com.glooboo.umessage.models.ScheduledBackup
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M-D1: a backup that skips the hidden chats also leaves out their scheduled messages and reminders. M-SEC2: a
 * restore never arms scheduled sends silently; they wait until the user saw how many and to how many numbers, and
 * said yes. Every number is made up.
 */
@RunWith(AndroidJUnit4::class)
class ScheduledRestoreHoldTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: MessagesDatabase
    private val clock = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val booked = mutableListOf<Message>()
    private val held = mutableListOf<ScheduledBackup>()
    private var nextId = 3_000_000_001L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).allowMainThreadQueries().build()
        context.getSharedPreferences(HeldScheduledRestore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        db.close()
        BackupJob.acknowledge()
    }

    private fun contact(number: String) = SimpleContact(
        rawId = 1, contactId = 1, name = number, photoUri = "",
        phoneNumbers = arrayListOf(PhoneNumber(number, 0, "", number)),
        birthdays = ArrayList(), anniversaries = ArrayList(),
    )

    private fun scheduled(id: Long, thread: Long, body: String, number: String = "+15550001234") = Message(
        id = id, body = body, type = Telephony.Sms.MESSAGE_TYPE_QUEUED, status = 0,
        participants = arrayListOf(contact(number)), date = ((clock + day) / 1000).toInt(), read = true,
        threadId = thread, isMMS = false, attachment = MessageAttachment(id, body, ArrayList()),
        senderPhoneNumber = number, senderName = "", senderPhotoUri = "", subscriptionId = 0, isScheduled = true,
    )

    private fun part(hidden: Set<Long> = emptySet(), hold: ScheduledHold? = null) = ScheduledPart(
        scheduled = { db.MessagesDao().getAllScheduledMessages() },
        repeatOf = { db.ScheduledRepeatsDao().get(it) },
        reminders = db.MessageRemindersDao(),
        chats = ChatLookup({ setOf("+15550001234") }, { 70L }),
        booker = { message, _ -> booked += message },
        arm = {},
        now = { clock },
        newId = { nextId++ },
        hiddenChats = { hidden },
        hold = hold,
    )

    private fun request(threads: Set<Long>) = AppDataRequest(threads, sms = true, mms = true, wholePhone = true)

    @Test
    fun aBackupThatSkipsAHiddenChatLeavesOutItsScheduledMessagesAndReminders() = runBlocking<Unit> {
        db.MessagesDao().insertOrUpdate(scheduled(2_000_000_001, 7, "for a shown chat"))
        db.MessagesDao().insertOrUpdate(scheduled(2_000_000_002, 8, "for a hidden chat"))
        // Scheduled to new people: its thread id is its own id, not a chat of the list.
        db.MessagesDao().insertOrUpdate(scheduled(2_000_000_003, 2_000_000_003, "to someone new"))
        db.MessageRemindersDao().insert(MessageReminder(1, 5, false, 7, clock + day, "shown reminder"))
        db.MessageRemindersDao().insert(MessageReminder(2, 6, false, 8, clock + day, "hidden reminder"))

        val skipped = part(hidden = setOf(8)).collect(request(setOf(7)), AppDataBackup())
        val included = part(hidden = setOf(8)).collect(request(setOf(7, 8)), AppDataBackup())

        assertThat(skipped.scheduled.map { it.body }).containsExactly("for a shown chat", "to someone new")
        assertThat(skipped.reminders.map { it.body }).containsExactly("shown reminder")
        assertThat(included.scheduled).hasSize(3)
        assertThat(included.reminders).hasSize(2)
    }

    @Test
    fun aRestoreHoldsTheScheduledMessagesAndArmsNothing() = runBlocking<Unit> {
        val items = listOf(
            ScheduledBackup(listOf("+15550001234"), "happy birthday", clock + day),
            ScheduledBackup(listOf("+15550001235", "+15550001236"), "see you", clock + 2 * day),
        )
        val hold = object : ScheduledHold {
            override fun add(items: List<ScheduledBackup>) {
                held += items
            }
        }

        val taken = part(hold = hold).restore(AppDataBackup(scheduled = items))

        assertThat(taken).isEqualTo(0)
        assertThat(booked).isEmpty()
        assertThat(held).containsExactlyElementsIn(items)

        // The user said yes: both are armed, once.
        assertThat(part().arm(held)).isEqualTo(2)
        assertThat(booked.map { it.body }).containsExactly("happy birthday", "see you")
    }

    @Test
    fun withoutAHoldARestoreArmsAsBefore() = runBlocking<Unit> {
        val taken = part().restore(
            AppDataBackup(scheduled = listOf(ScheduledBackup(listOf("+15550001234"), "now armed", clock + day))),
        )

        assertThat(taken).isEqualTo(1)
        assertThat(booked.map { it.body }).containsExactly("now armed")
    }

    @Test
    fun theHoldCountsMessagesAndNumbersAndKeepsAMessageOnce() {
        val hold = HeldScheduledRestore.hold(context)
        val one = ScheduledBackup(listOf("+15550001234"), "a", clock + day)
        hold.add(listOf(one, ScheduledBackup(listOf("+15550001234", "+15550001235"), "b", clock + day)))
        hold.add(listOf(one))

        assertThat(HeldScheduledRestore.summary(context)).isEqualTo(HeldScheduledRestore.Summary(2, 2))

        HeldScheduledRestore.discard(context)
        assertThat(HeldScheduledRestore.summary(context)).isNull()
    }

    @Test
    fun aFinishedRestoreSaysThatScheduledMessagesWait() {
        HeldScheduledRestore.hold(context).add(listOf(ScheduledBackup(listOf("+15550001234"), "a", clock + day)))

        BackupJob.finish(context, BackupJob.State.ImportDone(ImportSummary()))

        val done = BackupJob.state.value as BackupJob.State.ImportDone
        assertThat(done.scheduledHeld).isEqualTo(HeldScheduledRestore.Summary(1, 1))
    }

    @Test
    fun aFinishedRestoreWithNothingHeldSaysNothing() {
        BackupJob.finish(context, BackupJob.State.ImportDone(ImportSummary()))

        assertThat((BackupJob.state.value as BackupJob.State.ImportDone).scheduledHeld).isNull()
    }
}
