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
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
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

    private fun addSms(thread: Long): Long = offMainThread {
        context.contentResolver.insert(
            Telephony.Sms.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Sms.THREAD_ID, thread)
                put(Telephony.Sms.ADDRESS, NUMBER)
                put(Telephony.Sms.BODY, "Offer")
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            },
        )!!.lastPathSegment!!.toLong()
    }

    private fun addMms(thread: Long): Long = offMainThread {
        context.contentResolver.insert(
            Telephony.Mms.CONTENT_URI,
            ContentValues().apply {
                put(Telephony.Mms.THREAD_ID, thread)
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
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

    private companion object {
        const val THREAD = 7L
        const val OTHER = 8L
        const val NUMBER = "+15550001234"
    }
}
