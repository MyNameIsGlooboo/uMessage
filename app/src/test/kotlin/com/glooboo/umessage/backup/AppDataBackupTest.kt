package com.glooboo.umessage.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.helpers.StarredMessages
import com.glooboo.umessage.models.AppDataBackup
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.ConversationLabel
import com.glooboo.umessage.models.Label
import com.glooboo.umessage.models.MessagesBackup
import com.glooboo.umessage.models.StarredMessage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Stars, labels and archived chats: backup, wipe, restore, equal (the chats are found again by their numbers). */
@RunWith(AndroidJUnit4::class)
class AppDataBackupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private lateinit var old: MessagesDatabase
    private lateinit var fresh: MessagesDatabase

    // Chat 7 is "+15550001" and chat 8 is a group, on the old phone; the new phone numbers them 70 and 80.
    private val numbersOfOld = mapOf(7L to setOf("+15550001"), 8L to setOf("+15550002", "+15550003"))
    private val threadsOfNew = mapOf(setOf("+15550001") to 70L, setOf("+15550002", "+15550003") to 80L)

    @Before
    fun setUp() {
        old = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).allowMainThreadQueries().build()
        fresh = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).allowMainThreadQueries().build()
        context.getSharedPreferences("restored_archive", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        old.close()
        fresh.close()
    }

    private fun support(db: MessagesDatabase) = AppDataBackupSupport(
        stars = db.StarredMessagesDao(), labels = db.LabelsDao(), conversations = db.ConversationsDao(),
        pendingArchive = PendingArchive(context),
        addressesOf = { numbersOfOld[it].orEmpty() },
        threadFor = { threadsOfNew[it] },
    )

    private fun conversation(id: Long, archived: Boolean = false) = Conversation(
        threadId = id, snippet = "s", date = 1, read = true, title = "t", photoUri = "", isGroupConversation = false,
        phoneNumber = "n", isArchived = archived,
    )

    private fun star(id: Long, thread: Long, body: String) = StarredMessage(
        id = id, isMMS = false, threadId = thread, address = "+15550001", date = 1_700_000_000L,
        bodyHash = StarredMessages.fingerprint(body), starredTS = 5_000L, snapshotBody = body,
    )

    private fun seed() = runBlocking {
        old.ConversationsDao().insertOrUpdate(conversation(7, archived = true))
        old.ConversationsDao().insertOrUpdate(conversation(8))
        old.StarredMessagesDao().insert(star(11, 7, "rent is due"))
        val labels = old.LabelsDao()
        val work = labels.insertLabel(Label(name = "Work", color = 0xFF112233.toInt(), sortOrder = 1))
        labels.insertLabel(Label(name = "Unused", color = 5, sortOrder = 2))
        labels.addToConversation(ConversationLabel(8, work))
    }

    private fun throughTheFile(data: AppDataBackup): AppDataBackup {
        val text = json.encodeToString(MessagesBackup.serializer(), data)
        return json.decodeFromString(MessagesBackup.serializer(), text) as AppDataBackup
    }

    private suspend fun backupOfOld() =
        throughTheFile(support(old).collect(setOf(7L, 8L), sms = true, mms = true)!!)

    @Test
    fun starsLabelsAndArchivedChatsSurviveBackupWipeRestore() = runBlocking<Unit> {
        seed()
        val data = backupOfOld()

        val taken = support(fresh).restore(data)

        assertThat(taken).isEqualTo(1 + 2 + 1 + 1)
        val stars = fresh.StarredMessagesDao().getAll()
        assertThat(stars.map { it.snapshotBody }).containsExactly("rent is due")
        assertThat(stars.single().bodyHash).isEqualTo(StarredMessages.fingerprint("rent is due"))
        assertThat(stars.single().starredTS).isEqualTo(5_000L)
        assertThat(fresh.LabelsDao().getLabels().map { it.name to it.color })
            .containsExactly("Work" to 0xFF112233.toInt(), "Unused" to 5)
        val work = fresh.LabelsDao().getLabels().first { it.name == "Work" }
        assertThat(fresh.LabelsDao().getThreadIds(work.id)).containsExactly(80L)
    }

    @Test
    fun archivedChatWaitsForItsRowAndIsArchivedWhenTheListKnowsIt() = runBlocking<Unit> {
        seed()
        support(fresh).restore(backupOfOld())
        val pending = PendingArchive(context)
        assertThat(pending.applyTo(fresh.ConversationsDao())).isEqualTo(0)

        fresh.ConversationsDao().insertOrUpdate(conversation(70))

        assertThat(pending.applyTo(fresh.ConversationsDao())).isEqualTo(1)
        assertThat(fresh.ConversationsDao().getAllArchived().map { it.threadId }).containsExactly(70L)
        assertThat(pending.applyTo(fresh.ConversationsDao())).isEqualTo(0)
    }

    @Test
    fun restoringTwiceAddsNoStarOrLabelTheSecondTime() = runBlocking<Unit> {
        seed()
        val data = backupOfOld()
        support(fresh).restore(data)

        support(fresh).restore(data)

        assertThat(fresh.StarredMessagesDao().getAll()).hasSize(1)
        assertThat(fresh.LabelsDao().getLabels()).hasSize(2)
        assertThat(fresh.LabelsDao().getLinks()).hasSize(1)
    }

    @Test
    fun anExistingStarIsNotDuplicatedUnderANewId() = runBlocking<Unit> {
        seed()
        fresh.StarredMessagesDao().insert(star(900, 70, "rent is due"))

        support(fresh).restore(backupOfOld())

        assertThat(fresh.StarredMessagesDao().getAll().map { it.id }).containsExactly(900L)
    }

    @Test
    fun chatsOutsideTheExportAreLeftOutAndNothingMeansNoEntry() = runBlocking<Unit> {
        seed()

        val onlySeven = support(old).collect(setOf(7L), sms = true, mms = true)!!
        val none = support(fresh).collect(setOf(1L), sms = true, mms = true)

        assertThat(onlySeven.labelLinks).isEmpty()
        assertThat(onlySeven.archived).hasSize(1)
        assertThat(none).isNull()
    }

    @Test
    fun aFileWithUnknownFieldsStillReads() {
        val read = json.decodeFromString(
            MessagesBackup.serializer(), """{"backupType":"umessage_appdata","future":[1,2],"stars":[]}""",
        )

        assertThat((read as AppDataBackup).isEmpty).isTrue()
    }
}
