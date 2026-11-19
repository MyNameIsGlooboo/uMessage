package com.glooboo.umessage.extensions

import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.helpers.messageNotes
import com.glooboo.umessage.messaging.holdDiedScheduledSend
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.joinAppBackgroundThreads
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowRoleManager
import org.robolectric.shadows.ShadowToast

/**
 * Step B in extensions/Context.kt: the honest delete (S14), forget-thread (S19), `seen` in insertNewSMS (3.4), the
 * hidden-thread rule without the block-list half (S3) and the M-P4 wiring of a scheduled send that died (K1-6).
 */
@RunWith(AndroidJUnit4::class)
class ProviderTruthContextTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A store that answers but never deletes, like the provider for an app that is not the default. */
    class KeepingStore : FakeSmsMmsProvider() {
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    /** Records what an insert writes, for the columns insertNewSMS sets. */
    class RecordingSmsStore : ContentProvider() {
        val inserted = mutableListOf<ContentValues>()
        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?,
        ): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserted += ContentValues(values)
            return ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, inserted.size.toLong())
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun getType(uri: Uri): String? = null
    }

    private lateinit var sms: FakeSmsMmsProvider

    private fun install(keeping: Boolean = false) {
        sms = store(keeping, Telephony.Sms.CONTENT_URI.authority!!)
        store(keeping, Telephony.Mms.CONTENT_URI.authority!!)
    }

    private fun store(keeping: Boolean, authority: String): FakeSmsMmsProvider = if (keeping) {
        Robolectric.setupContentProvider(KeepingStore::class.java, authority)
    } else {
        Robolectric.setupContentProvider(FakeSmsMmsProvider::class.java, authority)
    }

    private fun beDefaultSmsApp() {
        val roles = shadowOf(context.getSystemService(RoleManager::class.java)) as ShadowRoleManager
        roles.addAvailableRole(RoleManager.ROLE_SMS)
        roles.addHeldRole(RoleManager.ROLE_SMS)
    }

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    /** Thread 7 with one received SMS in the store, cached, with a note, a draft and a Spam row. */
    private fun seedChat(): Long = offMainThread {
        val uri = context.contentResolver.insert(
            Telephony.Sms.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Sms.THREAD_ID, THREAD)
                put(Telephony.Sms.ADDRESS, NUMBER)
                put(Telephony.Sms.BODY, "Parcel at the door")
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            },
        )
        val id = uri!!.lastPathSegment!!.toLong()
        val message = Message(
            id = id, body = "Parcel at the door", type = 1, status = -1, participants = ArrayList(), date = 100,
            read = true, threadId = THREAD, isMMS = false, attachment = null, senderPhoneNumber = NUMBER,
            senderName = "", senderPhotoUri = "", subscriptionId = -1,
        )
        context.messagesDB.insertOrUpdate(message)
        context.conversationsDB.insertOrUpdate(
            Conversation(THREAD, "Parcel at the door", 100, true, "Chat", "", false, NUMBER)
        )
        context.conversationsDB.insertSpamThread(SpamThread(THREAD, NUMBER, SpamCause.UNKNOWN, spamSince = 1L))
        runBlocking { context.messageNotes.setNote(message, "Neighbour has it") }
        context.saveSmsDraft("Thanks!", THREAD)
        id
    }

    private fun notes() = runBlocking { context.messageNotes.exportAll() }

    private fun latestToast(): String? {
        ShadowLooper.idleMainLooper()
        return ShadowToast.getTextOfLatestToast()
    }

    @Test
    fun notTheDefaultAppDeletesNothingAndSaysWhy() {
        install()
        val id = seedChat()

        val deleted = offMainThread { context.deleteConversation(THREAD) }

        // B-13: the result says why; the screen that asked shows it, the delete itself shows nothing.
        assertThat(deleted).isEqualTo(DeleteResult.NOT_DEFAULT)
        assertThat(deleted.messageRes).isEqualTo(R.string.pt_delete_not_default)
        assertThat(latestToast()).isNull()
        assertThat(sms.smsIds().map { it.first }).containsExactly(id)
        assertThat(notes()).hasSize(1)
        offMainThread {
            assertThat(context.conversationsDB.getConversationWithThreadId(THREAD)).isNotNull()
            assertThat(context.conversationsDB.getSpamThread(THREAD)).isNotNull()
            assertThat(context.getSmsDraft(THREAD)).isEqualTo("Thanks!")
        }
    }

    @Test
    fun aStoreThatKeptTheMessagesKeepsTheAppsDataToo() {
        install(keeping = true)
        beDefaultSmsApp()
        seedChat()

        val deleted = offMainThread { context.deleteConversation(THREAD) }

        assertThat(deleted).isEqualTo(DeleteResult.NOTHING_DELETED)
        assertThat(deleted.messageRes).isEqualTo(R.string.pt_delete_nothing)
        assertThat(notes()).hasSize(1)
        offMainThread { assertThat(context.conversationsDB.getConversationWithThreadId(THREAD)).isNotNull() }
    }

    @Test
    fun aRealDeleteForgetsEverythingOfTheChat() {
        install()
        beDefaultSmsApp()
        seedChat()

        val deleted = offMainThread { context.deleteConversation(THREAD) }

        assertThat(deleted).isEqualTo(DeleteResult.DELETED)
        assertThat(sms.smsIds()).isEmpty()
        assertThat(notes()).isEmpty()
        offMainThread {
            assertThat(context.conversationsDB.getConversationWithThreadId(THREAD)).isNull()
            assertThat(context.conversationsDB.getSpamThread(THREAD)).isNull()
            assertThat(context.messagesDB.getThreadMessages(THREAD)).isEmpty()
            assertThat(context.getSmsDraft(THREAD)).isEmpty()
        }
    }

    @Test
    fun withTheFlagOffDeleteWorksAsIn0414() {
        ProviderTruthFlag.enabled = false
        install()
        seedChat()

        offMainThread { context.deleteConversation(THREAD) }

        assertThat(notes()).isEmpty()
    }

    @Test
    fun deletingOneMessageIsHonestToo() {
        install(keeping = true)
        val id = seedChat()

        assertThat(offMainThread { context.deleteMessage(id, false) }).isEqualTo(DeleteResult.NOT_DEFAULT)

        beDefaultSmsApp()
        assertThat(offMainThread { context.deleteMessage(id, false) }).isEqualTo(DeleteResult.NOTHING_DELETED)
        // The role checked once by the caller of a batch: no second look.
        assertThat(offMainThread { context.deleteMessage(id, false, isDefault = false) })
            .isEqualTo(DeleteResult.NOT_DEFAULT)
        assertThat(latestToast()).isNull()
        assertThat(notes()).hasSize(1)
        offMainThread { assertThat(context.messagesDB.getMessage(id, false)).isNotNull() }
    }

    @Test
    fun aMessageAlreadyGoneFromTheStoreLeavesTheCacheToo() {
        install()
        beDefaultSmsApp()
        val id = seedChat()
        offMainThread { context.contentResolver.delete(Telephony.Sms.CONTENT_URI, "_id = ?", arrayOf("$id")) }

        assertThat(offMainThread { context.deleteMessage(id, false) }).isEqualTo(DeleteResult.DELETED)

        offMainThread { assertThat(context.messagesDB.getMessage(id, false)).isNull() }
        assertThat(notes()).isEmpty()
    }

    @Test
    fun aChatOfScheduledMessagesOnlyIsForgottenWithoutAskingForTheRole() {
        install()
        offMainThread {
            context.conversationsDB.insertOrUpdate(
                Conversation(SCHEDULED_ID, "Later", 100, true, "Sam", "", false, NUMBER, isScheduled = true)
            )
        }

        assertThat(offMainThread { context.deleteConversation(SCHEDULED_ID) }).isEqualTo(DeleteResult.DELETED)

        offMainThread { assertThat(context.conversationsDB.getConversationWithThreadId(SCHEDULED_ID)).isNull() }
    }

    private fun archived(id: Long, number: String) =
        Conversation(id, "Hi", id.toInt(), true, "Chat", "", false, number, isArchived = true)

    @Test
    fun emptyingArchivedStopsAtTheFirstChatNotDeletedAndKeepsSpamChats() {
        // B-1: the Archived list only; "emptied" only when every chat went.
        install()
        offMainThread {
            context.conversationsDB.insertOrUpdate(archived(21, OTHER_A))
            context.conversationsDB.insertOrUpdate(archived(22, OTHER_B))
            context.conversationsDB.insertSpamThread(SpamThread(22, OTHER_B, SpamCause.BLOCKED, spamSince = 1L))
        }

        context.removeAllArchivedConversations()
        joinAppBackgroundThreads()
        assertThat(latestToast()).isEqualTo(context.getString(R.string.pt_delete_not_default))
        offMainThread { assertThat(context.conversationsDB.getConversationWithThreadId(21)).isNotNull() }

        beDefaultSmsApp()
        context.removeAllArchivedConversations()
        joinAppBackgroundThreads()
        assertThat(latestToast()).isEqualTo(context.getString(R.string.archive_emptied_successfully))
        offMainThread {
            assertThat(context.conversationsDB.getConversationWithThreadId(21)).isNull()
            assertThat(context.conversationsDB.getConversationWithThreadId(22)).isNotNull()
        }
    }

    @Test
    fun aSpamTextIsWrittenReadAndSeen() {
        val store = Robolectric.setupContentProvider(RecordingSmsStore::class.java, Telephony.Sms.CONTENT_URI.authority)

        val id = offMainThread {
            context.insertNewSMS(NUMBER, "", "Win a prize", 1_000L, read = 1, threadId = THREAD, type = 1,
                subscriptionId = -1, seen = 1)
        }
        offMainThread {
            context.insertNewSMS(NUMBER, "", "Hello", 2_000L, read = 0, threadId = THREAD, type = 1,
                subscriptionId = -1)
        }

        assertThat(id).isEqualTo(1L)
        val (spam, plain) = store.inserted
        assertThat(spam.getAsInteger(Telephony.Sms.READ)).isEqualTo(1)
        assertThat(spam.getAsInteger(Telephony.Sms.SEEN)).isEqualTo(1)
        // Without a value the provider's own default stays.
        assertThat(plain.containsKey(Telephony.Sms.SEEN)).isFalse()
    }

    @Test
    fun aThreadWithoutRecipientsIsStillLeftOutButABlockedOneIsListed() {
        val blocked: (String) -> Boolean = { it == NUMBER }

        assertThat(isThreadHiddenByBlocks(emptyList(), hideBlocked = false, isBlocked = blocked)).isTrue()
        assertThat(isThreadHiddenByBlocks(listOf(NUMBER), hideBlocked = false, isBlocked = blocked)).isFalse()
        assertThat(isThreadHiddenByBlocks(listOf(NUMBER), isBlocked = blocked)).isTrue()
    }

    private fun scheduled(type: Int) = Message(
        id = SCHEDULED_ID, body = "See you at noon", type = type, status = Telephony.Sms.STATUS_NONE,
        participants = arrayListOf(
            SimpleContact(1, 1, "Sam", "", arrayListOf(PhoneNumber(NUMBER, 0, "", NUMBER)), ArrayList(), ArrayList())
        ),
        date = 1_000, read = false, threadId = THREAD, isMMS = false, attachment = null, senderPhoneNumber = "",
        senderName = "", senderPhotoUri = "", subscriptionId = -1, isScheduled = true,
    )

    private fun notifications() =
        shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications

    @Test
    fun aScheduledSendThatDiedIsHeldWithTheNotSentNotification() {
        // M-P4 wiring: the start-up re-arm finds it in the outbox with no send owning it.
        offMainThread { context.messagesDB.insertOrUpdate(scheduled(Telephony.Sms.MESSAGE_TYPE_OUTBOX)) }

        offMainThread { context.rescheduleAllScheduledMessages() }
        ShadowLooper.idleMainLooper()

        val type = offMainThread { context.messagesDB.getScheduledMessageById(SCHEDULED_ID)?.type }
        assertThat(type).isEqualTo(Telephony.Sms.MESSAGE_TYPE_FAILED)
        assertThat(notifications()).hasSize(1)
    }

    @Test
    fun aRowThatIsGoneGetsNoNotification() {
        // K1-6: the send finished and deleted the row before the hold: nothing may say "not sent".
        offMainThread { context.holdDiedScheduledSend(scheduled(Telephony.Sms.MESSAGE_TYPE_OUTBOX)) }
        ShadowLooper.idleMainLooper()

        assertThat(notifications()).isEmpty()
    }

    @Test
    fun aHeldOrQueuedRowGetsNoNotSentNotification() {
        // B-8: the user's Retry held it again, or a re-arm queued it: nothing died, nothing may say "not sent".
        listOf(Telephony.Sms.MESSAGE_TYPE_FAILED, Telephony.Sms.MESSAGE_TYPE_QUEUED).forEach { type ->
            offMainThread { context.messagesDB.insertOrUpdate(scheduled(type)) }
            offMainThread { context.holdDiedScheduledSend(scheduled(Telephony.Sms.MESSAGE_TYPE_OUTBOX)) }
            ShadowLooper.idleMainLooper()
            val stored = offMainThread { context.messagesDB.getScheduledMessageById(SCHEDULED_ID)?.type }
            assertThat(stored).isEqualTo(type)
        }

        assertThat(notifications()).isEmpty()
    }

    @Test
    fun theHoldOnlyTakesARowStillInTheOutbox() {
        offMainThread {
            val dao = context.messagesDB
            dao.insertOrUpdate(scheduled(Telephony.Sms.MESSAGE_TYPE_QUEUED))
            assertThat(dao.holdDiedScheduledRow(SCHEDULED_ID)).isEqualTo(0)
            dao.insertOrUpdate(scheduled(Telephony.Sms.MESSAGE_TYPE_OUTBOX))
            assertThat(dao.holdDiedScheduledRow(SCHEDULED_ID)).isEqualTo(1)
            assertThat(dao.holdDiedScheduledRow(SCHEDULED_ID)).isEqualTo(0)
            dao.deleteScheduledMessage(SCHEDULED_ID)
            assertThat(dao.holdDiedScheduledRow(SCHEDULED_ID)).isEqualTo(0)
        }
    }

    private companion object {
        const val THREAD = 7L
        const val NUMBER = "+15550001234"
        const val OTHER_A = "+15550001235"
        const val OTHER_B = "+15550001236"
        const val SCHEDULED_ID = 1_500_000_001L
    }
}
