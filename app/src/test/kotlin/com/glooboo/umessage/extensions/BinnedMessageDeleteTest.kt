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
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.RecycleBinMessage
import com.glooboo.umessage.testing.FakeThreadStore
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowRoleManager

/**
 * A permanent delete of a binned message checks the store row against the cached copy first: a row that is another
 * message under a reused id, or one dated after the user binned it, stays in the phone.
 */
@RunWith(AndroidJUnit4::class)
class BinnedMessageDeleteTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: FakeThreadStore

    @Before
    fun setUp() {
        store = FakeThreadStore.install()
        store.addThread(CHAT, FakeThreadStore.ADDRESS)
        val roles = shadowOf(context.getSystemService(RoleManager::class.java)) as ShadowRoleManager
        roles.addAvailableRole(RoleManager.ROLE_SMS)
        roles.addHeldRole(RoleManager.ROLE_SMS)
    }

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    private fun addSms(id: Long, body: String, dateMs: Long, type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX) {
        store.addSms(id, CHAT, body, dateMs, type = type)
    }

    private fun cacheThread() = offMainThread {
        context.messagesDB.insertOrIgnoreAll(context.getMessages(CHAT, includeScheduledMessages = false))
    }

    private fun overwriteCache() = offMainThread {
        context.getMessages(CHAT, includeScheduledMessages = false)
            .forEach { context.messagesDB.insertOrUpdate(it) }
    }

    private fun bin(id: Long, isMMS: Boolean = false, deletedTS: Long = System.currentTimeMillis()) = offMainThread {
        context.messagesDB.insertRecycleBinEntry(RecycleBinMessage(id, isMMS, deletedTS))
    }

    private fun deleteFromStore(id: Long) {
        context.contentResolver.delete(
            Telephony.Sms.CONTENT_URI, "${Telephony.Sms._ID} = ?", arrayOf(id.toString()),
        )
    }

    private fun storeBody(id: Long): String? = offMainThread {
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.BODY),
            "${Telephony.Sms._ID} = ?", arrayOf(id.toString()), null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    private fun binIds() = offMainThread { context.messagesDB.getAllRecycleBinEntries().map { it.id }.sorted() }

    private fun cachedBody(id: Long, isMMS: Boolean = false) =
        offMainThread { context.messagesDB.getMessage(id, isMMS)?.body }

    @Test
    fun aMatchingRowIsDeletedFromTheStore() {
        addSms(ID, "Same", OLD_DATE)
        cacheThread()
        bin(ID)

        val result = offMainThread { context.emptyMessagesRecycleBin() }

        assertThat(result).isEqualTo(DeleteResult.DELETED)
        assertThat(store.smsIds()).isEmpty()
        assertThat(binIds()).isEmpty()
        assertThat(cachedBody(ID)).isNull()
    }

    @Test
    fun aReusedIdWithANewerDateAndAnOverwrittenCacheIsLeftInTheStore() {
        val now = System.currentTimeMillis()
        addSms(ID, "Old", OLD_DATE)
        cacheThread()
        bin(ID, deletedTS = now - 2 * 60_000)
        deleteFromStore(ID)
        addSms(ID, "New", now)
        overwriteCache()

        val result = offMainThread { context.emptyMessagesRecycleBin() }

        assertThat(result).isEqualTo(DeleteResult.DELETED)
        assertThat(storeBody(ID)).isEqualTo("New")
        assertThat(binIds()).isEmpty()
        assertThat(cachedBody(ID)).isNull()
    }

    @Test
    fun aChangedBodyIsNotDeleted() {
        addSms(ID, "Old", OLD_DATE)
        cacheThread()
        bin(ID)
        deleteFromStore(ID)
        addSms(ID, "Changed", OLD_DATE)

        val result = offMainThread { context.emptyMessagesRecycleBin() }

        assertThat(result).isEqualTo(DeleteResult.DELETED)
        assertThat(storeBody(ID)).isEqualTo("Changed")
        assertThat(binIds()).isEmpty()
        assertThat(cachedBody(ID)).isNull()
    }

    @Test
    fun aRowGoneFromTheStoreStillDropsTheBinEntryAndCache() {
        addSms(ID, "Gone", OLD_DATE)
        cacheThread()
        bin(ID)
        deleteFromStore(ID)

        val result = offMainThread { context.emptyMessagesRecycleBin() }

        assertThat(result).isEqualTo(DeleteResult.DELETED)
        assertThat(store.smsIds()).isEmpty()
        assertThat(binIds()).isEmpty()
        assertThat(cachedBody(ID)).isNull()
    }

    @Test
    fun anUnreadableStoreKeepsTheBinEntryAndTheCache() {
        val message = Message(
            id = ID, body = "Offer", type = Telephony.Sms.MESSAGE_TYPE_INBOX, status = -1,
            participants = ArrayList(), date = (OLD_DATE / 1000).toInt(), read = true, threadId = CHAT,
            isMMS = false, attachment = null, senderPhoneNumber = FakeThreadStore.ADDRESS, senderName = "",
            senderPhotoUri = "", subscriptionId = -1,
        )
        offMainThread {
            context.messagesDB.insertOrUpdate(message)
            context.messagesDB.insertRecycleBinEntry(RecycleBinMessage(ID, false, System.currentTimeMillis()))
        }
        Robolectric.setupContentProvider(DeadStore::class.java, Telephony.Sms.CONTENT_URI.authority)

        val result = offMainThread { context.emptyMessagesRecycleBin() }

        assertThat(result).isEqualTo(DeleteResult.FAILED)
        assertThat(binIds()).containsExactly(ID)
        assertThat(cachedBody(ID)).isEqualTo("Offer")
    }

    @Test
    fun theBatchContinuesAfterAMismatch() {
        val now = System.currentTimeMillis()
        addSms(1, "Old first", OLD_DATE)
        addSms(2, "Second", OLD_DATE + 1_000)
        cacheThread()
        bin(1, deletedTS = now - 2 * 60_000)
        bin(2, deletedTS = now)
        deleteFromStore(1)
        addSms(1, "New first", now)
        overwriteCache()

        val result = offMainThread { context.emptyMessagesRecycleBin() }

        assertThat(result).isEqualTo(DeleteResult.DELETED)
        assertThat(store.smsIds()).containsExactly(1L)
        assertThat(storeBody(1)).isEqualTo("New first")
        assertThat(binIds()).isEmpty()
        assertThat(cachedBody(2)).isNull()
    }

    @Test
    fun anOutgoingKindChangeStillMatches() {
        addSms(ID, "Later", OLD_DATE, type = Telephony.Sms.MESSAGE_TYPE_OUTBOX)
        cacheThread()
        bin(ID)
        deleteFromStore(ID)
        addSms(ID, "Later", OLD_DATE, type = Telephony.Sms.MESSAGE_TYPE_SENT)

        val result = offMainThread { context.emptyMessagesRecycleBin() }

        assertThat(result).isEqualTo(DeleteResult.DELETED)
        assertThat(store.smsIds()).isEmpty()
        assertThat(binIds()).isEmpty()
        assertThat(cachedBody(ID)).isNull()
    }

    @Test
    fun aMatchingReceivedMmsIsDeleted() {
        store.addMms(ID, CHAT, "Picture advert", OLD_DATE / 1000)
        cacheThread()
        bin(ID, isMMS = true)

        val result = offMainThread { context.emptyMessagesRecycleBin() }

        assertThat(result).isEqualTo(DeleteResult.DELETED)
        assertThat(store.mmsIds()).isEmpty()
        assertThat(binIds()).isEmpty()
        assertThat(cachedBody(ID, isMMS = true)).isNull()
    }

    @Test
    fun aReceivedMmsMatchesTheThreadsSingleParticipant() {
        // The cache prefers the chat's one participant over FROM for a received MMS: the bin check accepts both.
        store.addMms(ID, CHAT, "Picture advert", OLD_DATE / 1000, address = OTHER)
        cacheThread()
        bin(ID, isMMS = true)

        val result = offMainThread { context.emptyMessagesRecycleBin() }

        assertThat(result).isEqualTo(DeleteResult.DELETED)
        assertThat(store.mmsIds()).isEmpty()
        assertThat(binIds()).isEmpty()
    }

    @Test
    fun aFailedMmsPartsReadKeepsTheBinEntry() {
        store.addMms(ID, CHAT, "Picture advert", OLD_DATE / 1000)
        cacheThread()
        bin(ID, isMMS = true)
        store.failPartsRead = true

        val result = offMainThread { context.emptyMessagesRecycleBin() }

        assertThat(result).isEqualTo(DeleteResult.FAILED)
        assertThat(store.mmsIds()).containsExactly(ID)
        assertThat(binIds()).containsExactly(ID)
        assertThat(cachedBody(ID, isMMS = true)).isEqualTo("Picture advert")
    }

    @Test
    fun aRowDatedAfterTheBinEntryIsNotDeletedEvenWithTheSameBody() {
        val now = System.currentTimeMillis()
        addSms(ID, "Same", OLD_DATE)
        cacheThread()
        bin(ID, deletedTS = now - 2 * 60_000)
        deleteFromStore(ID)
        addSms(ID, "Same", now)
        overwriteCache()

        val result = offMainThread { context.emptyMessagesRecycleBin() }

        assertThat(result).isEqualTo(DeleteResult.DELETED)
        assertThat(store.smsIds()).containsExactly(ID)
        assertThat(storeBody(ID)).isEqualTo("Same")
        assertThat(binIds()).isEmpty()
        assertThat(cachedBody(ID)).isNull()
    }

    @Test
    fun aStaleBinViewDeleteLeavesTheStoreRow() {
        addSms(ID, "Live", OLD_DATE)
        cacheThread()

        val result = offMainThread { context.deleteBinnedMessage(ID, false, true, fromBinView = true) }

        assertThat(result).isEqualTo(DeleteResult.DELETED)
        assertThat(store.smsIds()).containsExactly(ID)
        assertThat(cachedBody(ID)).isEqualTo("Live")
        assertThat(binIds()).isEmpty()
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

    private companion object {
        const val CHAT = 7L
        const val ID = 1L
        const val OLD_DATE = 1_700_000_000_000L
        const val OTHER = "+15550009999"
    }
}
