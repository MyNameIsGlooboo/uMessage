package com.glooboo.umessage.spam

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.SortingWorld.Companion.SENDER
import com.glooboo.umessage.spam.SortingWorld.Companion.chat
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.data.SpamRuleEntity
import com.glooboo.umessage.spam.engine.SpamEngine
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [NewChatSorting] as the app wires it (B-6; NewChatSorterTest covers [NewChatSorter] on fakes): the app's own
 * stores, first-run state, sender judge and sort queue. After the first sort a blocked sender's new chat is cached
 * with its Spam row; before it, and with the flag off, the row is only cached. The number is made up.
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
        offMainThread {
            val rule = SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = SENDER, createdAt = 1)
            SpamDatabase.getInstance(context).spamDao().insertRule(SpamRuleEntity.from(rule))
        }
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
        joinAppBackgroundThreads()
        SpamEngine.resetContactChecker()
        listOf("repository", "filter", "settingsStore", "forwardPlanner").forEach {
            SpamEngine::class.java.getDeclaredField(it).apply { isAccessible = true }.set(SpamEngine, null)
        }
        ChatSorter::class.java.getDeclaredField("snapshots").apply { isAccessible = true }.set(null, null)
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

    @Test
    fun afterTheFirstSortABlockedSendersNewChatIsCachedInSpam() {
        sort(chat(1, SENDER))

        assertThat(cachedIds()).containsExactly(1L)
        assertThat(spamIds()).containsExactly(1L)
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

        // The queued sort waits for the first run, which sorts every chat.
        assertThat(cachedIds()).containsExactly(1L)
        assertThat(spamIds()).isEmpty()
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
}
