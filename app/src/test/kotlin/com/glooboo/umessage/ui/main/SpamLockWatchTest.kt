package com.glooboo.umessage.ui.main

import android.app.Application
import android.os.Looper
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.lock.LockScope
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.helpers.AppLockManager
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * H-21: the search page reads whether the lock covers Spam again on each resume and when its hits change
 * ([rememberSpamLocked]); H-20: once it does not, a Spam section that went while it did is searched again
 * ([rememberSpamLockWatch]).
 */
@RunWith(AndroidJUnit4::class)
class SpamLockWatchTest {

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = forgetAppLockManager()

    private val models = ViewModelStore()

    @After
    fun tearDown() {
        models.clear()
        app.baseConfig.isAppPasswordProtectionOn = false
        LockScope.set(app, LockScope.WholeApp)
        forgetAppLockManager()
        resetSharedMessagesDatabase()
    }

    /** The commons lock manager keeps the first test's Application; it is made again for this one. */
    private fun forgetAppLockManager() {
        AppLockManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    private fun lockSpam() {
        app.baseConfig.isAppPasswordProtectionOn = true
        LockScope.set(app, LockScope.ChosenParts)
        app.appLockManager.lock()
    }

    /** The page goes behind another screen (the lock screen) and comes back. */
    private fun pauseAndResume() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    @Test
    fun theLockIsReadAgainWhenTheKeyChangesAndOnResume() {
        val key = mutableIntStateOf(0)
        var locked: Boolean? = null
        compose.setContent { locked = rememberSpamLocked(key.intValue) }
        compose.waitForIdle()
        assertThat(locked).isFalse()

        lockSpam()
        compose.waitForIdle()
        // Not read again yet: nothing changed and the page did not resume.
        assertThat(locked).isFalse()
        key.intValue = 1
        compose.waitForIdle()
        assertThat(locked).isTrue()

        app.appLockManager.unlock()
        pauseAndResume()
        assertThat(locked).isFalse()
    }

    @Test
    fun aSpamSectionThatWentIsSearchedAgainOnceUnlocked() {
        val search = MainSearchState()
        var asked = 0
        search.onSpamUnlocked = { asked++ }
        lockSpam()
        search.hideSpamHits()
        compose.setContent { rememberSpamLockWatch(search) }
        compose.waitForIdle()
        assertThat(asked).isEqualTo(0)

        app.appLockManager.unlock()
        pauseAndResume()
        assertThat(asked).isEqualTo(1)
        assertThat(search.spamHidden).isFalse()

        // Nothing went since, so a later resume searches nothing again.
        pauseAndResume()
        assertThat(asked).isEqualTo(1)
    }

    /** One Spam chat with the text "pizza at nine". Fake number only. */
    private fun seedSpamChat() = offMainThread {
        app.conversationsDB.insertOrUpdate(
            Conversation(
                threadId = SPAM, snippet = "", date = 100, read = true, title = "Deals", photoUri = "",
                isGroupConversation = false, phoneNumber = SENDER,
            ),
        )
        app.messagesDB.insertOrUpdate(
            Message(
                id = 1, body = "pizza at nine", type = Telephony.Sms.MESSAGE_TYPE_INBOX, status = 0,
                participants = ArrayList(), date = 1, read = true, threadId = SPAM, isMMS = false, attachment = null,
                senderPhoneNumber = SENDER, senderName = "", senderPhotoUri = "", subscriptionId = 0,
            ),
        )
        app.conversationsDB.insertSpamThread(SpamThread(SPAM, SENDER, SpamCause.UNKNOWN, spamSince = 1L))
    }

    private fun searchModel(): MainSearchViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainSearchViewModel(app, SavedStateHandle()) as T
        }
        return ViewModelProvider(models, factory)[MainSearchViewModel::class.java]
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (true) {
            compose.waitForIdle()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "condition not met" }
            Thread.sleep(POLL_MS)
        }
    }

    @Test
    fun theSearchPageSearchesSpamAgainOnceUnlocked() {
        // J4-3: the real page with a real model; the query was typed while the lock covered Spam.
        seedSpamChat()
        lockSpam()
        val search = searchModel()
        search.state.active = true
        compose.setContent {
            SearchResultsPanel(
                search.state, emptyList(), ShortcutHits(), rememberLazyListState(), emptyList(), emptyList(),
                SearchPanelActions({}, {}, {}, {}, {}),
            )
        }
        search.onQueryChange("pizza")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SEARCH_DEBOUNCE_MS))
        awaitUntil { search.state.spamHidden }
        assertThat(search.state.blockedHits).isEmpty()

        app.appLockManager.unlock()
        pauseAndResume()
        awaitUntil { search.state.blockedHits.isNotEmpty() }

        assertThat(search.state.spamTargets.values.map { it.threadId }).containsExactly(SPAM)
    }

    @Test
    fun withNothingLeftOutUnlockedSearchesNothingAgain() {
        val search = MainSearchState()
        var asked = 0
        search.onSpamUnlocked = { asked++ }

        compose.setContent { rememberSpamLockWatch(search) }
        compose.waitForIdle()

        assertThat(asked).isEqualTo(0)
    }

    private companion object {
        const val SPAM = 61L
        const val SENDER = "+15550001234"
        const val WAIT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
