package com.glooboo.umessage.spam

import android.Manifest
import android.app.Application
import android.content.Context
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.SortingWorld.Companion.SENDER
import com.glooboo.umessage.spam.SortingWorld.Companion.chat
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.data.SpamRuleEntity
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.spam.engine.SystemContactCheckerTest.FakeContactsProvider
import com.glooboo.umessage.testing.FakeMessageStore
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.testing.joinAppBackgroundThreads
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [NewChatSorting] as the app wires it (B-6; NewChatSorterTest covers [NewChatSorter] on fakes): the app's own
 * stores, first-run state, history check, fresh sender judge, published count and sort queue. After the first sort a
 * blocked sender's new chat is cached with its Spam row, and an unknown number the user wrote to joins "People you
 * wrote to" and stays in Messages; before it the row is only cached and the queued sort runs once the gate opens; with
 * the flag off the row is only cached. More than [NewChatSorting.BATCH] rows are covered on fakes only. The numbers
 * are made up.
 */
@RunWith(AndroidJUnit4::class)
class NewChatSortingTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        forgetSingletons()
        writeState(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true))
        blockSender()
    }

    private fun blockSender() = offMainThread {
        val rule = SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = SENDER, createdAt = 1)
        SpamDatabase.getInstance(context).spamDao().insertRule(SpamRuleEntity.from(rule))
    }

    @After
    fun tearDown() {
        idleSorter()
        context.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        FirstRun.reload(context)
        forgetSingletons()
    }

    private fun writeState(state: FirstRunState) {
        FirstRunStore(context.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE)).write(state)
        FirstRun.reload(context)
    }

    /** The sorting thread ends what it was asked before the stores are read or reset. */
    private fun idleSorter() {
        val sorter = CountDownLatch(1)
        SpamSorting.execute(Runnable { sorter.countDown() })
        assertThat(sorter.await(30, TimeUnit.SECONDS)).isTrue()
    }

    /** The app's process-wide stores and caches, closed and forgotten, so this test reads its own files. */
    private fun forgetSingletons() {
        idleSorter()
        joinAppBackgroundThreads()
        SpamEngine.resetContactChecker()
        listOf("repository", "filter", "settingsStore", "forwardPlanner").forEach {
            SpamEngine::class.java.getDeclaredField(it).apply { isAccessible = true }.set(SpamEngine, null)
        }
        ChatSorter::class.java.getDeclaredField("snapshots").apply { isAccessible = true }.set(null, null)
        // The sort queue keeps its app and a "sort missed" mark; a later class must get neither.
        SpamSorting::class.java.getDeclaredField("queue").apply { isAccessible = true }.set(null, null)
        val instance = SpamDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (instance.get(null) as? SpamDatabase)?.let { runCatching { it.close() } }
        instance.set(null, null)
        resetSharedMessagesDatabase()
    }

    private fun sort(vararg rows: Conversation) = offMainThread {
        NewChatSorting.cacheAndSort(context, rows.toList())
    }

    private fun cachedIds() = offMainThread { context.conversationsDB.getAllRows().map { it.threadId }.toSet() }

    private fun spamIds() = offMainThread { context.conversationsDB.getSpamThreads().map { it.threadId }.toSet() }

    /** READ_CONTACTS granted and an address book that answers and holds nobody. */
    private fun readableContacts() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CONTACTS)
        FakeContactsProvider.numbers = emptyList()
        FakeContactsProvider.broken = false
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
    }

    @Test
    fun afterTheFirstSortABlockedSendersNewChatIsCachedInSpam() {
        sort(chat(1, SENDER))

        assertThat(cachedIds()).containsExactly(1L)
        assertThat(spamIds()).containsExactly(1L)
    }

    @Test
    fun theSortReadsAFreshJudgeNotOneKeptFromBefore() {
        // A judge kept from before the rule was written does not know it; only a fresh one puts the chat in Spam.
        offMainThread {
            val dao = SpamDatabase.getInstance(context).spamDao()
            dao.getRules().forEach { dao.deleteRule(it.id) }
            ChatSorter.snapshots(context).get()
        }
        blockSender()

        sort(chat(1, SENDER))

        assertThat(spamIds()).containsExactly(1L)
    }

    @Test
    fun aSortThatMovedAChatIsPublished() {
        val before = SpamSorting.changes.value

        sort(chat(1, SENDER))

        assertThat(SpamSorting.changes.value).isEqualTo(before + 1)
    }

    @Test
    fun anUnknownNumberTheUserWroteToJoinsWroteToAndStaysInMessages() {
        readableContacts()
        val store = FakeMessageStore.install()
        store.addSms(WROTE_TO, "Hi", date = 1_000, type = Telephony.Sms.MESSAGE_TYPE_SENT)

        sort(chat(4, WROTE_TO), chat(5, STRANGER))

        assertThat(cachedIds()).containsExactly(4L, 5L)
        // The sender with a sent text is checked first and joins the group, so its chat stays in Messages.
        val members = offMainThread { SpamDatabase.getInstance(context).spamDao().getMembers() }
        assertThat(members.map { it.group to it.address }).containsExactly(AllowGroup.WROTE to WROTE_TO)
        // The one without goes to Spam as an unknown sender.
        val spam = offMainThread { context.conversationsDB.getSpamThreads() }
        assertThat(spam.map { it.threadId to it.cause }).containsExactly(5L to SpamCause.UNKNOWN)
    }

    @Test
    fun aGroupAndAScheduledOnlyChatAreCachedAndStayInMessages() {
        sort(chat(2, SENDER, group = true), chat(3, SENDER, scheduled = true))

        assertThat(cachedIds()).containsExactly(2L, 3L)
        assertThat(spamIds()).isEmpty()
    }

    @Test
    fun beforeTheFirstSortTheRowIsOnlyCached() {
        writeState(FirstRunState(phase = FirstRunPhase.HISTORY, trigger = FirstRunTrigger.UPDATE))

        sort(chat(1, SENDER))
        idleSorter()

        // The queued sort waits for the first run.
        assertThat(cachedIds()).containsExactly(1L)
        assertThat(spamIds()).isEmpty()

        // The first run ends: the sort that waited runs now.
        writeState(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true))
        SpamSorting.releaseGate(context)
        idleSorter()

        assertThat(spamIds()).containsExactly(1L)
    }

    @Test
    fun withTheFlagOffTheRowIsOnlyCachedAsIn0414() {
        ProviderTruthFlag.enabled = false

        sort(chat(1, SENDER))
        idleSorter()

        assertThat(cachedIds()).containsExactly(1L)
        assertThat(spamIds()).isEmpty()
    }

    @Test
    fun noRowsDoNothing() {
        sort()

        assertThat(cachedIds()).isEmpty()
        assertThat(spamIds()).isEmpty()
    }

    private companion object {
        const val WROTE_TO = "+15550001235"
        const val STRANGER = "+15550001236"
    }
}
