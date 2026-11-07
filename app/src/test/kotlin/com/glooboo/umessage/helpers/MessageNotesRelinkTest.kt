package com.glooboo.umessage.helpers

import android.content.Context
import android.provider.Telephony
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageNote
import com.glooboo.umessage.models.MessagesBackup
import com.glooboo.umessage.models.NoteBackup
import com.glooboo.umessage.models.NotesBackup
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Notes find their messages again after a restore: by key, by the message without its address, and by moved chats. */
@RunWith(AndroidJUnit4::class)
class MessageNotesRelinkTest {
    private lateinit var db: MessagesDatabase
    private lateinit var notes: MessageNotes
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).allowMainThreadQueries().build()
        notes = MessageNotes(
            db.MessageNotesDao(), messagesAtDate = { date -> db.MessagesDao().getMessagesAtDate(date) },
        )
    }

    @After
    fun tearDown() = db.close()

    private fun contact(number: String) = SimpleContact(
        rawId = 1, contactId = 1, name = number, photoUri = "",
        phoneNumbers = arrayListOf(PhoneNumber(number, 0, "", number)),
        birthdays = ArrayList(), anniversaries = ArrayList(),
    )

    /** A sent group MMS whose first person is [first]. */
    private fun sentGroup(id: Long, thread: Long, first: String, second: String) = Message(
        id = id, body = "see you at 8", type = Telephony.Sms.MESSAGE_TYPE_SENT, status = 0,
        participants = arrayListOf(contact(first), contact(second)), date = 1_700_000_000, read = true,
        threadId = thread, isMMS = true, attachment = null, senderPhoneNumber = "", senderName = "",
        senderPhotoUri = "", subscriptionId = 0,
    )

    @Test
    fun aNoteOfASentGroupMmsSurvivesAChangedOrderOfThePeople() = runBlocking<Unit> {
        val before = sentGroup(1, 7, "+15550002", "+15550003")
        notes.setNote(before, "agreed")
        val backup = NotesBackup(notes.exportAll().map { NoteBackup.of(it) })
        db.MessageNotesDao().getAll().forEach { db.MessageNotesDao().delete(it.key) }

        // The restored message lives in a new chat and its people come in the other order.
        val after = sentGroup(500, 70, "+15550003", "+15550002")
        db.MessagesDao().insertOrUpdate(after)
        val text = json.encodeToString(MessagesBackup.serializer(), backup)
        val read = json.decodeFromString(MessagesBackup.serializer(), text) as NotesBackup
        notes.restore(read.notes.map { it.toNote() })

        assertThat(MessageNoteKey.of(after)).isNotEqualTo(MessageNoteKey.of(before))
        assertThat(notes.notesForThread(70).values.map { it.text }).containsExactly("agreed")
        assertThat(notes.noteFor(after)?.text).isEqualTo("agreed")
        assertThat(db.MessageNotesDao().getAll()).hasSize(1)
    }

    @Test
    fun aNoteWhoseChatIdChangedFollowsItsMessage() = runBlocking<Unit> {
        val message = sentGroup(1, 7, "+15550002", "+15550003")
        notes.setNote(message, "agreed")
        // The cache is rebuilt under another chat id; the old chat 7 has no message any more.
        db.MessagesDao().insertOrUpdate(message.copy(id = 9, threadId = 77))

        assertThat(notes.notesForThread(77).values.map { it.text }).containsExactly("agreed")
        assertThat(notes.threadsWithNotes()).containsExactly(77L)
    }

    @Test
    fun aNoteWhoseMessageIsNotCachedYetStaysUntouched() = runBlocking<Unit> {
        notes.restore(listOf(MessageNote("v1|s|r|1700000000|5550001|abc", 0, 1_700_000_000, "later", 1, 1)))

        assertThat(notes.relink()).isEqualTo(0)

        assertThat(db.MessageNotesDao().getAll().map { it.text }).containsExactly("later")
    }

    @Test
    fun sameExceptAddressIgnoresOnlyTheAddress() {
        val a = "v1|m|s|1700000000|5550002|1f"
        assertThat(MessageNoteKey.sameExceptAddress(a, "v1|m|s|1700000000|5550003|1f")).isTrue()
        assertThat(MessageNoteKey.sameExceptAddress(a, "v1|m|r|1700000000|5550003|1f")).isFalse()
        assertThat(MessageNoteKey.sameExceptAddress(a, "v1|m|s|1700000001|5550002|1f")).isFalse()
        assertThat(MessageNoteKey.sameExceptAddress(a, "v1|m|s|1700000000|5550002|2f")).isFalse()
        assertThat(MessageNoteKey.sameExceptAddress(a, "garbage")).isFalse()
    }
}
