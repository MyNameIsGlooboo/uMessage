package com.glooboo.umessage.backup

import android.content.Context
import android.provider.Telephony
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.MutePrefs
import com.glooboo.umessage.helpers.SavedTexts
import com.glooboo.umessage.helpers.StarredMessages
import com.glooboo.umessage.helpers.composerPrefs
import com.glooboo.umessage.models.AppDataBackup
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Draft
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageAttachment
import com.glooboo.umessage.models.MessageReminder
import com.glooboo.umessage.models.MessagesBackup
import com.glooboo.umessage.models.RecycleBinMessage
import com.glooboo.umessage.models.RecycledBackup
import com.glooboo.umessage.models.ScheduledRepeat
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drafts, muted and hidden chats, saved texts, the recycle bin, scheduled messages and reminders: backup, wipe,
 * restore, equal; restoring twice adds nothing; an old file without them still restores.
 */
@RunWith(AndroidJUnit4::class)
class AppDataPartsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private lateinit var old: MessagesDatabase
    private lateinit var fresh: MessagesDatabase

    private val numbersOfOld = mapOf(7L to setOf("+15550001"), 8L to setOf("+15550002", "+15550003"))
    private val threadsOfNew = mapOf(setOf("+15550001") to 70L, setOf("+15550002", "+15550003") to 80L)
    private var clock = 1_700_000_000_000L
    private val forever = Long.MAX_VALUE
    private val day = 24L * 60 * 60 * 1000

    private lateinit var oldMutes: MutePrefs
    private lateinit var newMutes: MutePrefs
    private lateinit var oldHidden: HiddenChats
    private lateinit var newHidden: HiddenChats

    @Before
    fun setUp() {
        old = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).allowMainThreadQueries().build()
        fresh = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).allowMainThreadQueries().build()
        listOf("parts_old_mutes", "parts_new_mutes", "parts_old_hidden", "parts_new_hidden", "restored_recycled")
            .forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        oldMutes = MutePrefs(context.getSharedPreferences("parts_old_mutes", Context.MODE_PRIVATE)) { clock }
        newMutes = MutePrefs(context.getSharedPreferences("parts_new_mutes", Context.MODE_PRIVATE)) { clock }
        oldHidden = HiddenChats(context.getSharedPreferences("parts_old_hidden", Context.MODE_PRIVATE), { true })
        newHidden = HiddenChats(context.getSharedPreferences("parts_new_hidden", Context.MODE_PRIVATE), { true })
        context.composerPrefs.savedTextsJson = ""
    }

    @After
    fun tearDown() {
        old.close()
        fresh.close()
    }

    private fun contact(number: String) = SimpleContact(
        rawId = 1, contactId = 1, name = number, photoUri = "",
        phoneNumbers = arrayListOf(PhoneNumber(number, 0, "", number)),
        birthdays = ArrayList(), anniversaries = ArrayList(),
    )

    private fun chatState(db: MessagesDatabase, mutes: MutePrefs, hidden: HiddenChats) = ChatStatePart(
        drafts = db.DraftsDao(), mutes = mutes, hidden = hidden, savedTexts = SavedTexts(context.composerPrefs),
        addressesOf = { numbersOfOld[it].orEmpty() }, threadFor = { threadsOfNew[it] },
    )

    private fun request(wholePhone: Boolean) = AppDataRequest(setOf(7L, 8L), sms = true, mms = true, wholePhone)

    private fun throughTheFile(data: AppDataBackup): AppDataBackup {
        val text = json.encodeToString(MessagesBackup.serializer(), data)
        return json.decodeFromString(MessagesBackup.serializer(), text) as AppDataBackup
    }

    private suspend fun collect(part: AppDataPart, wholePhone: Boolean = true): AppDataBackup =
        throughTheFile(part.collect(request(wholePhone), AppDataBackup()))

    private fun received(id: Long, thread: Long, body: String) = Message(
        id = id, body = body, type = Telephony.Sms.MESSAGE_TYPE_INBOX, status = 0,
        participants = arrayListOf(contact("+15550001")), date = 1_650_000_000, read = true, threadId = thread,
        isMMS = false, attachment = null, senderPhoneNumber = "+15550001", senderName = "", senderPhotoUri = "",
        subscriptionId = 0,
    )

    // Drafts, mutes, hidden chats and saved texts

    @Test
    fun draftsMutesHiddenChatsAndSavedTextsSurviveBackupWipeRestore() = runBlocking<Unit> {
        old.DraftsDao().insertOrUpdate(Draft(7, "call mom back", 1_650_000_000_000L))
        oldMutes.muteUntil(7, forever)
        oldMutes.muteUntil(8, clock + 1) // ends before the restore below
        oldHidden.hide(8)
        SavedTexts(context.composerPrefs).add("Home", "12 Example Street")
        val data = collect(chatState(old, oldMutes, oldHidden))
        context.composerPrefs.savedTextsJson = ""
        clock += 10

        val taken = chatState(fresh, newMutes, newHidden).restore(data)

        assertThat(taken).isEqualTo(4)
        assertThat(fresh.DraftsDao().getAll().map { Triple(it.threadId, it.body, it.date) })
            .containsExactly(Triple(70L, "call mom back", 1_650_000_000_000L))
        assertThat(newMutes.mutedUntil(70)).isEqualTo(forever)
        assertThat(newMutes.isMuted(80)).isFalse()
        assertThat(newHidden.chosenIds()).containsExactly(80L)
        assertThat(SavedTexts(context.composerPrefs).all().map { it.title to it.text })
            .containsExactly("Home" to "12 Example Street")
    }

    @Test
    fun restoringTheChatSettingsTwiceAddsNothingAndKeepsALaterDraft() = runBlocking<Unit> {
        old.DraftsDao().insertOrUpdate(Draft(7, "from the backup", 1))
        oldMutes.muteUntil(7, forever)
        oldHidden.hide(7)
        SavedTexts(context.composerPrefs).add("Home", "12 Example Street")
        val data = collect(chatState(old, oldMutes, oldHidden))
        val part = chatState(fresh, newMutes, newHidden)
        part.restore(data)
        fresh.DraftsDao().insertOrUpdate(Draft(70, "typed since", 2))

        val again = part.restore(data)

        assertThat(again).isEqualTo(0)
        assertThat(fresh.DraftsDao().getDraftById(70)!!.body).isEqualTo("typed since")
        assertThat(SavedTexts(context.composerPrefs).all()).hasSize(1)
        assertThat(newHidden.chosenIds()).containsExactly(70L)
    }

    @Test
    fun aPartialExportLeavesOutSavedTexts() = runBlocking<Unit> {
        SavedTexts(context.composerPrefs).add("Home", "12 Example Street")

        val data = collect(chatState(old, oldMutes, oldHidden), wholePhone = false)

        assertThat(data.savedTexts).isEmpty()
    }

    @Test
    fun anOldFileWithoutTheNewListsStillRestores() = runBlocking<Unit> {
        val read = json.decodeFromString(
            MessagesBackup.serializer(), """{"backupType":"umessage_appdata","stars":[],"future":{"x":1}}""",
        ) as AppDataBackup

        assertThat(read.isEmpty).isTrue()
        assertThat(chatState(fresh, newMutes, newHidden).restore(read)).isEqualTo(0)
    }

    // The recycle bin

    private fun recycleBin(db: MessagesDatabase) = RecycleBinPart(db.MessagesDao(), PendingRecycled(context)) { clock }

    @Test
    fun aRecycledMessageGoesBackInTheBinOnceItIsCachedUnderItsNewId() = runBlocking<Unit> {
        old.MessagesDao().insertOrUpdate(received(11, 7, "old news"))
        old.MessagesDao().insertOrUpdate(received(12, 7, "keep me"))
        old.MessagesDao().insertRecycleBinEntry(RecycleBinMessage(11, false, 5))
        val data = collect(recycleBin(old))
        assertThat(data.recycled).hasSize(1)

        recycleBin(fresh).restore(data)
        assertThat(fresh.MessagesDao().getAllRecycleBinEntries()).isEmpty()

        // The restored messages reach the cache under other ids; the list sync and the cache import apply the names.
        fresh.MessagesDao().insertOrUpdate(received(901, 70, "old news"))
        fresh.MessagesDao().insertOrUpdate(received(902, 70, "keep me"))
        assertThat(PendingRecycled(context).applyTo(fresh.MessagesDao(), clock)).isEqualTo(1)

        val bin = fresh.MessagesDao().getAllRecycleBinEntries()
        assertThat(bin.map { it.key.id }).containsExactly(901L)
        assertThat(bin.single().deletedTS).isEqualTo(clock)
        assertThat(PendingRecycled(context).applyTo(fresh.MessagesDao(), clock)).isEqualTo(0)
    }

    @Test
    fun restoringTheRecycleBinTwiceDoesNotDuplicateOrRecycleMore() = runBlocking<Unit> {
        old.MessagesDao().insertOrUpdate(received(11, 7, "old news"))
        old.MessagesDao().insertRecycleBinEntry(RecycleBinMessage(11, false, 5))
        val data = collect(recycleBin(old))
        fresh.MessagesDao().insertOrUpdate(received(901, 70, "old news"))
        val part = recycleBin(fresh)
        part.restore(data)

        val again = part.restore(data)

        assertThat(again).isEqualTo(0)
        assertThat(fresh.MessagesDao().getAllRecycleBinEntries()).hasSize(1)
        assertThat(PendingRecycled(context).size()).isEqualTo(0)
    }

    @Test
    fun aRecycledNameWithNoMessageIsDroppedAfterNinetyDays() = runBlocking<Unit> {
        val name = RecycledBackup("+15550001", 1_650_000_000, StarredMessages.fingerprint("x"))
        recycleBin(fresh).restore(AppDataBackup(recycled = listOf(name)))
        assertThat(PendingRecycled(context).size()).isEqualTo(1)

        PendingRecycled(context).applyTo(fresh.MessagesDao(), clock + 91 * day)

        assertThat(PendingRecycled(context).size()).isEqualTo(0)
    }

    // Scheduled messages and reminders

    private val booked = mutableListOf<Message>()
    private val armed = mutableListOf<MessageReminder>()
    private var nextId = 3_000_000_000L

    private fun scheduledPart(db: MessagesDatabase) = ScheduledPart(
        scheduled = { db.MessagesDao().getAllScheduledMessages() },
        repeatOf = { db.ScheduledRepeatsDao().get(it) },
        reminders = db.MessageRemindersDao(),
        chats = ChatLookup({ numbersOfOld[it].orEmpty() }, { threadsOfNew[it] }),
        booker = { message, repeat ->
            db.MessagesDao().insertOrUpdate(message)
            repeat?.let { db.ScheduledRepeatsDao().insertOrUpdate(it.copy(messageId = message.id)) }
            booked += message
        },
        arm = { armed += it },
        now = { clock },
        newId = { nextId++ },
    )

    private fun scheduled(id: Long, atMillis: Long, body: String, type: Int = Telephony.Sms.MESSAGE_TYPE_QUEUED) =
        received(id, id, body).copy(
            type = type, date = (atMillis / 1000).toInt(), isScheduled = true,
            attachment = MessageAttachment(id, body, ArrayList()),
        )

    private fun withPicture(message: Message): Message {
        val picture = Attachment(null, message.id, "content://x", "image/jpeg", 1, 1, "a.jpg")
        return message.copy(attachment = MessageAttachment(message.id, "x", arrayListOf(picture)))
    }

    @Test
    fun scheduledMessagesAndRemindersSurviveBackupWipeRestore() = runBlocking<Unit> {
        old.MessagesDao().insertOrUpdate(scheduled(2_000_000_001, clock + day, "happy birthday"))
        old.MessagesDao().insertOrUpdate(scheduled(2_000_000_002, clock - day, "too late"))
        old.MessagesDao().insertOrUpdate(scheduled(2_000_000_003, clock - day, "every day"))
        old.ScheduledRepeatsDao().insertOrUpdate(ScheduledRepeat(2_000_000_003, "DAILY", "GREGORIAN", 1, 1))
        old.MessagesDao().insertOrUpdate(
            scheduled(2_000_000_004, clock + day, "failed one", Telephony.Sms.MESSAGE_TYPE_FAILED),
        )
        old.MessagesDao().insertOrUpdate(withPicture(scheduled(2_000_000_005, clock + day, "with picture")))
        old.MessageRemindersDao().insert(MessageReminder(1, 5, false, 7, clock + day, "bring keys"))
        old.MessageRemindersDao().insert(MessageReminder(2, 6, false, 7, clock - day, "passed"))
        val data = collect(scheduledPart(old))

        assertThat(data.scheduled.map { it.body }).containsExactly("happy birthday", "too late", "every day")
        assertThat(data.reminders.map { it.body }).containsExactly("bring keys")

        val taken = scheduledPart(fresh).restore(data)

        // "too late" has passed and does not repeat, so it is left out; "every day" moved to its next time.
        assertThat(taken).isEqualTo(3)
        assertThat(booked.map { it.body }).containsExactly("happy birthday", "every day")
        assertThat(booked.all { it.isScheduled && it.millis() > clock && it.threadId == it.id }).isTrue()
        val everyDay = booked.first { it.body == "every day" }
        assertThat(everyDay.millis()).isLessThan(clock + 3 * day)
        assertThat(fresh.ScheduledRepeatsDao().get(everyDay.id)!!.rule).isEqualTo("DAILY")
        assertThat(fresh.ScheduledRepeatsDao().get(booked.first { it.body == "happy birthday" }.id)).isNull()
        assertThat(fresh.MessageRemindersDao().getAll().map { it.threadId to it.snapshotBody })
            .containsExactly(70L to "bring keys")
        assertThat(armed.map { it.snapshotBody }).containsExactly("bring keys")
        assertThat(armed.single().id).isGreaterThan(0)
    }

    @Test
    fun restoringScheduledMessagesAndRemindersTwiceAddsNoCopy() = runBlocking<Unit> {
        old.MessagesDao().insertOrUpdate(scheduled(2_000_000_001, clock + day, "happy birthday"))
        old.MessageRemindersDao().insert(MessageReminder(1, 5, false, 7, clock + day, "bring keys"))
        val data = collect(scheduledPart(old))
        val part = scheduledPart(fresh)
        part.restore(data)

        val again = part.restore(data)

        assertThat(again).isEqualTo(0)
        assertThat(fresh.MessagesDao().getAllScheduledMessages()).hasSize(1)
        assertThat(fresh.MessageRemindersDao().getAll()).hasSize(1)
    }

    @Test
    fun aPartialExportLeavesOutScheduledMessagesAndReminders() = runBlocking<Unit> {
        old.MessagesDao().insertOrUpdate(scheduled(2_000_000_001, clock + day, "happy birthday"))

        val data = collect(scheduledPart(old), wholePhone = false)

        assertThat(data.scheduled).isEmpty()
        assertThat(data.reminders).isEmpty()
    }
}
