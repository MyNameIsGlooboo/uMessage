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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The notes repository over an in-memory database: rules, search, restore merge and the backup round trip. */
@RunWith(AndroidJUnit4::class)
class MessageNotesTest {

    private lateinit var db: MessagesDatabase
    private var now = 1_000L
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
            db.MessageNotesDao(),
            clock = { now },
            messagesAtDate = { date -> db.MessagesDao().getMessagesAtDate(date) },
        )
    }

    @After
    fun tearDown() = db.close()

    private fun message(
        id: Long,
        body: String = "Paid 12.50 at the shop",
        thread: Long = 7,
        date: Int = 1_700_000_000,
        from: String = "+15550001234",
        mms: Boolean = false,
    ) = Message(
        id = id, body = body, type = Telephony.Sms.MESSAGE_TYPE_INBOX, status = 0, participants = ArrayList(),
        date = date, read = true, threadId = thread, isMMS = mms, attachment = null, senderPhoneNumber = from,
        senderName = "", senderPhotoUri = "", subscriptionId = 0,
    )

    // ---- rules ----

    @Test
    fun aNoteIsSavedTrimmedAndFoundAgain() = runBlocking<Unit> {
        val saved = notes.setNote(message(1), "  rent for March \n")

        assertThat(saved?.text).isEqualTo("rent for March")
        assertThat(notes.noteFor(message(1))?.text).isEqualTo("rent for March")
        assertThat(notes.noteFor(message(2, body = "other"))).isNull()
    }

    @Test
    fun aBlankNoteDeletesTheExistingOne() = runBlocking<Unit> {
        notes.setNote(message(1), "rent")

        assertThat(notes.setNote(message(1), "   \n ")).isNull()

        assertThat(notes.noteFor(message(1))).isNull()
        assertThat(db.MessageNotesDao().getAll()).isEmpty()
    }

    @Test
    fun theNoteIsCutToFiveHundredCharacters() = runBlocking<Unit> {
        val saved = notes.setNote(message(1), "x".repeat(900))

        assertThat(saved?.text).hasLength(MessageNotes.MAX_LENGTH)
        assertThat(notes.noteFor(message(1))?.text).hasLength(500)
    }

    @Test
    fun savingAgainKeepsCreatedAtAndMovesUpdatedAt() = runBlocking<Unit> {
        now = 1_000
        notes.setNote(message(1), "first")
        now = 5_000
        val second = notes.setNote(message(1), "second")

        assertThat(second?.createdAt).isEqualTo(1_000)
        assertThat(second?.updatedAt).isEqualTo(5_000)
        assertThat(db.MessageNotesDao().getAll()).hasSize(1)
        assertThat(notes.noteFor(message(1))?.text).isEqualTo("second")
    }

    @Test
    fun theNoteFollowsTheMessageUnderAnotherProviderId() = runBlocking<Unit> {
        notes.setNote(message(1), "rent")

        assertThat(notes.noteFor(message(4242))?.text).isEqualTo("rent")
    }

    @Test
    fun aScheduledMessageGetsNoNote() = runBlocking<Unit> {
        val scheduled = message(1).also { it.isScheduled = true }

        assertThat(notes.setNote(scheduled, "later")).isNull()
        assertThat(db.MessageNotesDao().getAll()).isEmpty()
    }

    @Test
    fun deleteNoteRemovesOnlyThatNote() = runBlocking<Unit> {
        notes.setNote(message(1), "a")
        notes.setNote(message(2, body = "second"), "b")

        assertThat(notes.deleteNote(message(1))).isTrue()
        assertThat(notes.deleteNote(message(1))).isFalse()

        assertThat(db.MessageNotesDao().getAll().map { it.text }).containsExactly("b")
    }

    // ---- threads ----

    @Test
    fun notesForAThreadAreMappedByKeyAndOtherThreadsAreLeftOut() = runBlocking<Unit> {
        notes.setNote(message(1, thread = 7), "in seven")
        notes.setNote(message(2, body = "two", thread = 8), "in eight")

        val seven = notes.notesForThread(7)

        assertThat(seven.keys).containsExactly(MessageNoteKey.of(message(1)))
        assertThat(seven[MessageNoteKey.of(message(1))]?.text).isEqualTo("in seven")
    }

    @Test
    fun threadsWithNotesListsEachThreadOnce() = runBlocking<Unit> {
        notes.setNote(message(1, thread = 7), "a")
        notes.setNote(message(2, body = "two", thread = 7), "b")
        notes.setNote(message(3, body = "three", thread = 9), "c")

        assertThat(notes.threadsWithNotes()).containsExactly(7L, 9L)
    }

    // ---- delete cleanup ----

    @Test
    fun deletingAMessageForGoodRemovesItsNoteOnly() = runBlocking<Unit> {
        notes.setNote(message(1), "a")
        notes.setNote(message(2, body = "two"), "b")

        notes.onMessageDeleted(MessageNoteKey.of(message(1)))

        assertThat(db.MessageNotesDao().getAll().map { it.text }).containsExactly("b")
    }

    @Test
    fun deletingAThreadRemovesItsNotes() = runBlocking<Unit> {
        notes.setNote(message(1, thread = 7), "a")
        notes.setNote(message(2, body = "two", thread = 8), "b")

        notes.onThreadDeleted(7)

        assertThat(db.MessageNotesDao().getAll().map { it.text }).containsExactly("b")
        assertThat(notes.threadsWithNotes()).containsExactly(8L)
    }

    @Test
    fun deletingThreadZeroNeverTouchesNotesWhoseChatIsNotKnownYet() = runBlocking<Unit> {
        notes.restore(listOf(MessageNote("k1", 0, 1, "restored", 1, 1)))

        notes.onThreadDeleted(0)

        assertThat(db.MessageNotesDao().getAll()).hasSize(1)
    }

    // ---- search ----

    @Test
    fun searchFindsPlainTextCaseInsensitivelyAndEscapesWildcards() = runBlocking<Unit> {
        notes.setNote(message(1), "Rent for March")
        notes.setNote(message(2, body = "two"), "discount 50% off")
        notes.setNote(message(3, body = "three"), "discount 500 off")

        assertThat(notes.search("rent").map { it.text }).containsExactly("Rent for March")
        assertThat(notes.search("50%").map { it.text }).containsExactly("discount 50% off")
        assertThat(notes.search("")).isEmpty()
    }

    @Test
    fun searchFoldsPersianAndArabicLettersAndDigits() = runBlocking<Unit> {
        notes.setNote(message(1), "قسط ماه ۱۲ علی")
        notes.setNote(message(2, body = "two"), "unrelated")

        assertThat(notes.search("علي").map { it.text }).containsExactly("قسط ماه ۱۲ علی")
        assertThat(notes.search("12").map { it.text }).containsExactly("قسط ماه ۱۲ علی")
        assertThat(notes.search("١٢").map { it.text }).containsExactly("قسط ماه ۱۲ علی")
    }

    // ---- backup and restore ----

    @Test
    fun exportWipeRestoreReattachesTheNoteByKey() = runBlocking<Unit> {
        now = 2_000
        notes.setNote(message(1), "rent")
        notes.setNote(message(2, body = "mms", mms = true), "photo")
        val entry = NotesBackup(notes.exportAll().map { NoteBackup.of(it) })
        val file = json.encodeToString(NotesBackup.serializer(), entry)

        db.MessageNotesDao().getAll().forEach { db.MessageNotesDao().delete(it.key) }
        assertThat(notes.noteFor(message(1))).isNull()

        val backup = json.decodeFromString(NotesBackup.serializer(), file)
        val taken = notes.restore(backup.notes.map { it.toNote() })

        assertThat(taken).isEqualTo(2)
        // The messages come back under new provider ids; the notes still find them.
        assertThat(notes.noteFor(message(900))?.text).isEqualTo("rent")
        assertThat(notes.noteFor(message(901, body = "mms", mms = true))?.text).isEqualTo("photo")
        assertThat(notes.noteFor(message(900))?.createdAt).isEqualTo(2_000)
    }

    @Test
    fun aRestoredNoteFindsItsChatOnceTheMessageIsCached() = runBlocking<Unit> {
        notes.setNote(message(1, thread = 7), "rent")
        val saved = notes.exportAll()
        db.MessageNotesDao().deleteThread(7)

        notes.restore(saved.map { it.copy(threadId = 99) })
        assertThat(notes.exportAll().single().threadId).isEqualTo(0L)
        assertThat(notes.threadsWithNotes()).isEmpty()

        // The restored message reaches the cache under a new id, in a thread of this phone.
        db.MessagesDao().insertOrUpdate(message(500, thread = 31))
        assertThat(notes.threadsWithNotes()).containsExactly(31L)
        assertThat(notes.notesForThread(31).values.single().text).isEqualTo("rent")
    }

    @Test
    fun anOlderBackupNoteNeverReplacesANewerNoteOnThePhone() = runBlocking<Unit> {
        now = 5_000
        notes.setNote(message(1), "newer edit")
        val old = MessageNote(MessageNoteKey.of(message(1)), 0, 1_700_000_000, "older edit", 100, 3_000)

        val taken = notes.restore(listOf(old))

        assertThat(taken).isEqualTo(0)
        assertThat(notes.noteFor(message(1))?.text).isEqualTo("newer edit")
    }

    @Test
    fun aNewerBackupNoteReplacesTheOneOnThePhoneAndKeepsTheEarlierCreatedAt() = runBlocking<Unit> {
        now = 1_000
        notes.setNote(message(1), "old edit")
        val newer = MessageNote(MessageNoteKey.of(message(1)), 0, 1_700_000_000, "newer edit", 4_000, 9_000)

        val taken = notes.restore(listOf(newer))

        assertThat(taken).isEqualTo(1)
        val kept = notes.noteFor(message(1))
        assertThat(kept?.text).isEqualTo("newer edit")
        assertThat(kept?.createdAt).isEqualTo(1_000)
        assertThat(kept?.threadId).isEqualTo(7L)
    }

    @Test
    fun restoreSkipsBlankNotesAndCutsLongOnes() = runBlocking<Unit> {
        val taken = notes.restore(
            listOf(
                MessageNote("k1", 0, 1, "  ", 1, 1),
                MessageNote("k2", 0, 1, "y".repeat(800), 1, 1),
            )
        )

        assertThat(taken).isEqualTo(1)
        assertThat(db.MessageNotesDao().get("k2")?.text).hasLength(500)
    }

    @Test
    fun anOlderBackupWithoutNotesStillDecodesAsMessages() {
        val entries = json.decodeFromString(ListSerializer(MessagesBackup.serializer()), SMS_ENTRY.let { "[$it]" })

        assertThat(entries).hasSize(1)
        assertThat(entries.single()).isNotInstanceOf(NotesBackup::class.java)
    }

    @Test
    fun theNotesEntryIsReadFromAFileThatHoldsMessagesAndNotes() {
        val notesEntry = """{"notes":[{"key":"v1|s|r|1|5550001234|abc","date":1,"note":"rent",""" +
            """"created_at":1,"updated_at":2}],"backupType":"umessage_notes"}"""

        val entries = json.decodeFromString(
            ListSerializer(MessagesBackup.serializer()), "[$SMS_ENTRY,$notesEntry]",
        )

        val last = entries.last() as NotesBackup
        assertThat(last.notes.single().note).isEqualTo("rent")
        assertThat(last.notes.single().toNote().threadId).isEqualTo(0L)
    }

    private companion object {
        const val SMS_ENTRY = """{"subscriptionId":1,"address":"+15550001234","body":"hi","date":1,"dateSent":1,""" +
            """"locked":0,"protocol":null,"read":1,"status":-1,"type":1,"serviceCenter":null,"backupType":"sms"}"""
    }
}
