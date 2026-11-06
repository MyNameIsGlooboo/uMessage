package com.glooboo.umessage.extensions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.models.RecycleBinMessage
import com.glooboo.umessage.testing.FakeThreadStore
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Reading a thread's messages, its preview and the recycle bin against a provider with SMS and MMS rows. */
@RunWith(AndroidJUnit4::class)
class ThreadListDataTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: FakeThreadStore

    @Before
    fun setUp() {
        store = FakeThreadStore.install()
        store.addThread(THREAD, FakeThreadStore.ADDRESS)
    }

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    // ---- DL-17: paging must not skip messages sent in the same second as the oldest one shown ----

    @Test
    fun olderPage_includesMessagesFromTheCutoffSecond() {
        store.addSms(1, THREAD, "Oldest", dateMs = 1_000_000)
        store.addSms(2, THREAD, "Hi", dateMs = 5_000_200)
        store.addSms(3, THREAD, "Are you there?", dateMs = 5_000_800)
        store.addSms(4, THREAD, "Newest", dateMs = 9_000_000)

        val loaded = context.getMessages(THREAD, includeScheduledMessages = false, limit = 2)
        assertThat(loaded.map { it.body }).containsExactly("Are you there?", "Newest").inOrder()

        val older = context.getOlderThreadMessages(THREAD, loaded.first().date, loaded)

        assertThat(older.map { it.body }).containsExactly("Oldest", "Hi").inOrder()
    }

    @Test
    fun olderPage_includesSmsAndMmsFromTheCutoffSecond() {
        store.addSms(1, THREAD, "Oldest", dateMs = 1_000_000)
        store.addSms(2, THREAD, "Hi", dateMs = 5_000_200)
        store.addSms(3, THREAD, "Are you there?", dateMs = 5_000_800)
        store.addMms(10, THREAD, "Photo caption", dateSec = 5_000, withPicture = true)
        store.addSms(4, THREAD, "Newest", dateMs = 9_000_000)

        val loaded = context.getMessages(THREAD, includeScheduledMessages = false, limit = 2)
        assertThat(loaded.map { it.key }).containsExactly(MessageKey(10, true), MessageKey(4, false)).inOrder()

        val older = context.getOlderThreadMessages(THREAD, loaded.first().date, loaded)

        assertThat(older.map { it.key })
            .containsExactly(MessageKey(1, false), MessageKey(2, false), MessageKey(3, false))
    }

    @Test
    fun olderPage_stepsPastASecondThatIsAlreadyFullyLoaded() {
        store.addSms(1, THREAD, "Oldest", dateMs = 1_000_000)
        store.addSms(2, THREAD, "A", dateMs = 5_000_100)
        store.addSms(3, THREAD, "B", dateMs = 5_000_200)

        // A page as large as the cutoff second: the same-second read finds only what is already on screen.
        val loaded = context.getMessages(THREAD, includeScheduledMessages = false, limit = 2)
        val older = context.getOlderThreadMessages(THREAD, loaded.first().date, loaded)

        assertThat(older.map { it.body }).containsExactly("Oldest")
    }

    @Test
    fun olderPage_isEmptyWhenHistoryIsExhausted() {
        store.addSms(1, THREAD, "Only", dateMs = 5_000_000)
        val loaded = context.getMessages(THREAD, includeScheduledMessages = false)

        assertThat(context.getOlderThreadMessages(THREAD, loaded.first().date, loaded)).isEmpty()
    }

    // ---- DL-16: the newest picture message wins over an older text ----

    @Test
    fun snippet_isTheNewerMmsOverAnOlderSms() {
        store.addSms(1, THREAD, "Where are you?", dateMs = 1_700_000_000_000)
        store.addMms(2, THREAD, "Here!", dateSec = 1_700_000_300, withPicture = true)

        assertThat(context.getThreadSnippet(THREAD)).isEqualTo("Here!")
    }

    @Test
    fun snippet_isTheNewerSmsOverAnOlderMms() {
        store.addMms(2, THREAD, "Here!", dateSec = 1_700_000_000, withPicture = true)
        store.addSms(1, THREAD, "On my way", dateMs = 1_700_000_300_000)

        assertThat(context.getThreadSnippet(THREAD)).isEqualTo("On my way")
    }

    // ---- DL-13: the recycle bin really empties itself ----

    @Test
    fun movingAnUncachedMessageToTheBin_cachesItSoTheBinCanShowAndRemoveIt(): Unit = offMainThread {
        store.addSms(57, THREAD, "Offer", dateMs = 1_700_000_000_000)
        assertThat(context.messagesDB.getMessage(57, false)).isNull()

        context.moveMessageToRecycleBin(57, false)

        assertThat(context.messagesDB.getAllRecycleBinMessages().map { it.key }).containsExactly(MessageKey(57, false))
        assertThat(context.messagesDB.getMessage(57, false)?.body).isEqualTo("Offer")
    }

    @Test
    fun cleanup_deletesOldBinnedMessagesFromThePhone(): Unit = offMainThread {
        store.addSms(57, THREAD, "Offer", dateMs = 1_700_000_000_000)
        store.addMms(57, THREAD, "Same id, other table", dateSec = 1_700_000_000)
        context.moveMessageToRecycleBin(57, false)
        backdateBinEntries(DAYS_40)

        context.deleteOldRecycleBinMessages(System.currentTimeMillis())

        assertThat(store.smsIds()).isEmpty()
        assertThat(store.mmsIds()).containsExactly(57L)
        assertThat(context.messagesDB.getAllRecycleBinEntries()).isEmpty()
        assertThat(context.messagesDB.getMessage(57, false)).isNull()
    }

    @Test
    fun cleanup_dropsOldEntriesWithoutACachedCopy_andKeepsTheirProviderRow(): Unit = offMainThread {
        store.addSms(58, THREAD, "Never cached", dateMs = 1_700_000_000_000)
        context.messagesDB.insertRecycleBinEntry(RecycleBinMessage(58, false, System.currentTimeMillis() - DAYS_40))

        context.deleteOldRecycleBinMessages(System.currentTimeMillis())

        assertThat(context.messagesDB.getAllRecycleBinEntries()).isEmpty()
        // Its type may be a migration guess, so the provider row is not touched; it never left the chat.
        assertThat(store.smsIds()).containsExactly(58L)
    }

    @Test
    fun cleanup_keepsRecentlyBinnedMessages(): Unit = offMainThread {
        store.addSms(57, THREAD, "Offer", dateMs = 1_700_000_000_000)
        context.moveMessageToRecycleBin(57, false)

        context.deleteOldRecycleBinMessages(System.currentTimeMillis())

        assertThat(store.smsIds()).containsExactly(57L)
        assertThat(context.messagesDB.getAllRecycleBinEntries().map { it.key }).containsExactly(MessageKey(57, false))
    }

    @Test
    fun emptyingTheBin_removesEntriesWithoutACachedCopyToo(): Unit = offMainThread {
        context.messagesDB.insertRecycleBinEntry(RecycleBinMessage(58, false, System.currentTimeMillis()))

        context.emptyMessagesRecycleBin()

        assertThat(context.messagesDB.getArchivedCount()).isEqualTo(0)
    }

    private fun backdateBinEntries(byMillis: Long) {
        context.messagesDB.getAllRecycleBinEntries().forEach {
            context.messagesDB.insertRecycleBinEntry(it.copy(deletedTS = it.deletedTS - byMillis))
        }
    }

    private companion object {
        const val THREAD = 7L
        const val DAYS_40 = 40L * 24 * 60 * 60 * 1000
    }
}
