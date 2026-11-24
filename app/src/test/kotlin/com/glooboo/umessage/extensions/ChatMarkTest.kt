package com.glooboo.umessage.extensions

import android.app.role.RoleManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.SpamChanges
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.conversations.deleteChats
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowRoleManager

/**
 * J24: [ChatMark] against fake SMS and MMS providers (what it asks for, an empty thread, a provider that fails), and
 * [deleteConversation] with a mark: only the rows up to the mark go, a newer text stays with the chat.
 */
@RunWith(AndroidJUnit4::class)
class ChatMarkTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Records every query it gets, then answers like [FakeSmsMmsProvider]. */
    class RecordingStore : FakeSmsMmsProvider() {
        class Query(val uri: Uri, val projection: List<String>?, val selection: String?, val args: List<String>?)

        val queries = mutableListOf<Query>()

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            queries += Query(uri, projection?.toList(), selection, selectionArgs?.toList())
            return super.query(uri, projection, selection, selectionArgs, sortOrder)
        }
    }

    /** A store where a text lands during the delete itself: its id is above the mark, so it must survive. */
    class ArrivesOnDeleteStore : FakeSmsMmsProvider() {
        private var arrived = false

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            if (!arrived && selection?.contains("${Telephony.Sms._ID} <= ?") == true) {
                arrived = true
                selectionArgs?.firstOrNull()?.toLong()?.let { thread ->
                    insert(
                        uri,
                        ContentValues().apply {
                            put(Telephony.Sms.THREAD_ID, thread)
                            put(Telephony.Sms.ADDRESS, "+15550001234")
                            put(Telephony.Sms.BODY, "Code")
                            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                        },
                    )
                }
            }
            return super.delete(uri, selection, selectionArgs)
        }
    }

    /** A store that answers, then fails the whole-thread read that follows the delete. */
    class FailsAfterDeleteStore : FakeSmsMmsProvider() {
        private var deleted = false

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            super.delete(uri, selection, selectionArgs).also { deleted = true }

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            check(!(deleted && selection == "${Telephony.Sms.THREAD_ID} = ?")) { "read after delete" }
            return super.query(uri, projection, selection, selectionArgs, sortOrder)
        }
    }

    /** A store whose unread count cannot be read once the delete has run; the delete itself and its reads work. */
    class FailsUnreadAfterDeleteStore : FakeSmsMmsProvider() {
        private var deleted = false

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            super.delete(uri, selection, selectionArgs).also { deleted = true }

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            check(!(deleted && selection?.contains("${Telephony.Sms.READ} = 0") == true)) { "unread read failed" }
            return super.query(uri, projection, selection, selectionArgs, sortOrder)
        }
    }

    /** A provider that gives no cursor, like one that is not there. */
    class DeadStore : ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?,
        ): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun getType(uri: Uri): String? = null
    }

    private lateinit var sms: RecordingStore
    private lateinit var mms: RecordingStore

    private fun install() {
        sms = Robolectric.setupContentProvider(RecordingStore::class.java, Telephony.Sms.CONTENT_URI.authority)
        mms = Robolectric.setupContentProvider(RecordingStore::class.java, Telephony.Mms.CONTENT_URI.authority)
    }

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    private fun beDefaultSmsApp() {
        val roles = shadowOf(context.getSystemService(RoleManager::class.java)) as ShadowRoleManager
        roles.addAvailableRole(RoleManager.ROLE_SMS)
        roles.addHeldRole(RoleManager.ROLE_SMS)
    }

    private fun addSms(thread: Long, read: Int = 1): Long = offMainThread {
        context.contentResolver.insert(
            Telephony.Sms.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Sms.THREAD_ID, thread)
                put(Telephony.Sms.ADDRESS, NUMBER)
                put(Telephony.Sms.BODY, "Offer")
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                put(Telephony.Sms.READ, read)
            },
        )!!.lastPathSegment!!.toLong()
    }

    private fun addMms(thread: Long, read: Int = 1): Long = offMainThread {
        context.contentResolver.insert(
            Telephony.Mms.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Mms.THREAD_ID, thread)
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
                put(Telephony.Mms.READ, read)
            },
        )!!.lastPathSegment!!.toLong()
    }

    private fun cacheChat(thread: Long, inSpam: Boolean) = offMainThread {
        context.conversationsDB.insertOrUpdate(Conversation(thread, "Offer", 100, true, "Chat", "", false, NUMBER))
        if (inSpam) {
            context.conversationsDB.insertSpamThread(SpamThread(thread, NUMBER, SpamCause.UNKNOWN, spamSince = 1L))
        }
    }

    private fun mark(thread: Long) = offMainThread { ChatMark.read(context, thread) }

    @Test
    fun theMarkIsTheNewestIdOfEachProviderForTheThread() {
        install()
        addSms(THREAD)
        val newestSms = addSms(THREAD)
        addSms(OTHER)
        val newestMms = addMms(THREAD)
        addMms(OTHER)
        cacheChat(THREAD, inSpam = true)

        assertThat(mark(THREAD)).isEqualTo(ChatMark(newestSms, newestMms, inSpam = true))
    }

    @Test
    fun theMarkAsksForTheIdColumnOfThatThreadOnlyNewestFirst() {
        install()
        addSms(THREAD)

        mark(THREAD)

        val asked = sms.queries.last()
        assertThat(asked.projection).containsExactly(Telephony.Sms._ID)
        assertThat(asked.selection).isEqualTo("${Telephony.Sms.THREAD_ID} = ?")
        assertThat(asked.args).containsExactly(THREAD.toString())
        assertThat(mms.queries.last().args).containsExactly(THREAD.toString())
    }

    @Test
    fun anEmptyThreadIsMarkedZeroAndNotInSpam() {
        install()
        addSms(OTHER)

        assertThat(mark(THREAD)).isEqualTo(ChatMark(0L, 0L, inSpam = false))
    }

    @Test
    fun aStaleSpamRowIsNotInSpam() {
        install()
        addSms(THREAD)
        // A Spam row of another sender than the cached chat's: not a valid row (S9), so not "in Spam".
        offMainThread {
            context.conversationsDB.insertOrUpdate(
                Conversation(THREAD, "Offer", 100, true, "Chat", "", false, "Bank"),
            )
            context.conversationsDB.insertSpamThread(SpamThread(THREAD, NUMBER, SpamCause.UNKNOWN, spamSince = 1L))
        }

        assertThat(mark(THREAD)?.inSpam).isFalse()
    }

    @Test
    fun aProviderThatFailsGivesNoMarkWhicheverOneItIs() {
        Robolectric.setupContentProvider(FakeSmsMmsProvider::class.java, Telephony.Sms.CONTENT_URI.authority)
        Robolectric.setupContentProvider(DeadStore::class.java, Telephony.Mms.CONTENT_URI.authority)
        assertThat(mark(THREAD)).isNull()

        Robolectric.setupContentProvider(DeadStore::class.java, Telephony.Sms.CONTENT_URI.authority)
        Robolectric.setupContentProvider(FakeSmsMmsProvider::class.java, Telephony.Mms.CONTENT_URI.authority)
        assertThat(mark(THREAD)).isNull()
    }

    @Test
    fun aDeleteStopsAtTheMarkAndKeepsAnewerTextWithTheChat() {
        install()
        beDefaultSmsApp()
        val old = addSms(THREAD)
        val oldMms = addMms(THREAD)
        cacheChat(THREAD, inSpam = true)
        val confirmed = mark(THREAD)!!
        // A text and a picture message come in after the confirm.
        val newer = addSms(THREAD)
        addMms(THREAD)

        val result = offMainThread { context.deleteConversation(THREAD, isDefault = true, upTo = confirmed) }

        assertThat(result).isEqualTo(DeleteResult.NEWER_TEXT)
        assertThat(sms.smsIds().map { it.first }).containsExactly(newer)
        assertThat(old).isLessThan(newer)
        assertThat(oldMms).isEqualTo(confirmed.mmsId)
        // The chat keeps its own data: no forget step ran.
        offMainThread { assertThat(context.conversationsDB.getConversationWithThreadId(THREAD)).isNotNull() }
        assertThat(mark(THREAD)?.mmsId).isGreaterThan(confirmed.mmsId)
    }

    @Test
    fun theArchivedDeleteTakesItsMarkSoATextThatArrivesDuringItSurvives() {
        Robolectric.setupContentProvider(ArrivesOnDeleteStore::class.java, Telephony.Sms.CONTENT_URI.authority)
        Robolectric.setupContentProvider(FakeSmsMmsProvider::class.java, Telephony.Mms.CONTENT_URI.authority)
        beDefaultSmsApp()
        addSms(THREAD)
        addMms(THREAD)
        cacheChat(THREAD, inSpam = false)

        val (deleted, message) = offMainThread { deleteChats(context, listOf(THREAD)) }

        assertThat(deleted).isEmpty()
        assertThat(message).isNull()
        // The text that came in during the delete stays, and so does the chat with it.
        offMainThread {
            assertThat(context.conversationsDB.getConversationWithThreadId(THREAD)).isNotNull()
            val smsCount = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID),
                "${Telephony.Sms.THREAD_ID} = ?", arrayOf(THREAD.toString()), null,
            )?.use { it.count }
            assertThat(smsCount).isEqualTo(1)
        }
    }

    @Test
    fun aWholeThreadReadThatFailsAfterTheDeleteKeepsTheChat() {
        Robolectric.setupContentProvider(FailsAfterDeleteStore::class.java, Telephony.Sms.CONTENT_URI.authority)
        Robolectric.setupContentProvider(FailsAfterDeleteStore::class.java, Telephony.Mms.CONTENT_URI.authority)
        beDefaultSmsApp()
        addSms(THREAD)
        addMms(THREAD)
        cacheChat(THREAD, inSpam = true)
        val confirmed = mark(THREAD)!!

        val result = offMainThread { context.deleteConversation(THREAD, isDefault = true, upTo = confirmed) }

        // Not knowing whether a newer text is there must not forget the chat.
        assertThat(result).isEqualTo(DeleteResult.NOTHING_DELETED)
        offMainThread { assertThat(context.conversationsDB.getConversationWithThreadId(THREAD)).isNotNull() }
    }

    @Test
    fun aDeleteWithNothingNewerRemovesTheChatAsBefore() {
        install()
        beDefaultSmsApp()
        addSms(THREAD)
        addMms(THREAD)
        addSms(OTHER)
        cacheChat(THREAD, inSpam = true)
        val confirmed = mark(THREAD)!!

        val result = offMainThread { context.deleteConversation(THREAD, isDefault = true, upTo = confirmed) }

        assertThat(result).isEqualTo(DeleteResult.DELETED)
        assertThat(sms.smsIds()).hasSize(1)
        offMainThread { assertThat(context.conversationsDB.getConversationWithThreadId(THREAD)).isNull() }
    }

    @Test
    fun aDeleteThatKeepsANewerTextSetsTheSpamUnreadToTheStoreAgain() {
        install()
        beDefaultSmsApp()
        addSms(THREAD, read = 0)
        cacheChat(THREAD, inSpam = true)
        offMainThread { repeat(3) { context.conversationsDB.raiseSpamUnread(THREAD) } }
        val confirmed = mark(THREAD)!!
        // The kept text is unread in the store: uMessage's own count must read 1, not the 3 of the texts that went.
        addSms(THREAD, read = 0)
        val changes = SpamChanges.changes.value

        val result = offMainThread { context.deleteConversation(THREAD, isDefault = true, upTo = confirmed) }

        assertThat(result).isEqualTo(DeleteResult.NEWER_TEXT)
        offMainThread { assertThat(context.conversationsDB.getSpamThread(THREAD)!!.unread).isEqualTo(1) }
        assertThat(SpamChanges.changes.value).isEqualTo(changes + 1)
    }

    @Test
    fun theSpamUnreadIsTheNumberOfUnreadRowsLeftInSmsAndMms() {
        install()
        beDefaultSmsApp()
        addSms(THREAD, read = 0)
        addMms(THREAD, read = 0)
        cacheChat(THREAD, inSpam = true)
        offMainThread { repeat(4) { context.conversationsDB.raiseSpamUnread(THREAD) } }
        val confirmed = mark(THREAD)!!
        // A newer text that is read, and a newer picture message that is not: only one unread row is left.
        addSms(THREAD, read = 1)
        addMms(THREAD, read = 0)

        val result = offMainThread { context.deleteConversation(THREAD, isDefault = true, upTo = confirmed) }

        assertThat(result).isEqualTo(DeleteResult.NEWER_TEXT)
        offMainThread { assertThat(context.conversationsDB.getSpamThread(THREAD)!!.unread).isEqualTo(1) }
    }

    @Test
    fun aStoreWhoseUnreadCountCannotBeReadLeavesTheSpamUnreadAsItWas() {
        Robolectric.setupContentProvider(FailsUnreadAfterDeleteStore::class.java, Telephony.Sms.CONTENT_URI.authority)
        Robolectric.setupContentProvider(FailsUnreadAfterDeleteStore::class.java, Telephony.Mms.CONTENT_URI.authority)
        beDefaultSmsApp()
        addSms(THREAD, read = 0)
        cacheChat(THREAD, inSpam = true)
        offMainThread { repeat(2) { context.conversationsDB.raiseSpamUnread(THREAD) } }
        val confirmed = mark(THREAD)!!
        addSms(THREAD, read = 0)

        val result = offMainThread { context.deleteConversation(THREAD, isDefault = true, upTo = confirmed) }

        // Not knowing the store's count must not guess one: the count stays.
        assertThat(result).isEqualTo(DeleteResult.NEWER_TEXT)
        offMainThread { assertThat(context.conversationsDB.getSpamThread(THREAD)!!.unread).isEqualTo(2) }
    }

    private companion object {
        const val THREAD = 7L
        const val OTHER = 8L
        const val NUMBER = "+15550001234"
    }
}
