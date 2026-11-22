package com.glooboo.umessage.ui.main

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.testing.ProviderTruthFlagRule
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

/**
 * H-21: the search page reads whether the lock covers Spam again on each resume and when its hits change
 * ([rememberSpamLocked]); H-20: once it does not, a Spam section that went while it did is searched again
 * ([rememberSpamLockWatch]).
 */
@RunWith(AndroidJUnit4::class)
class SpamLockWatchTest {

    @get:Rule(order = 0)
    val flag = ProviderTruthFlagRule(enabled = true)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = forgetAppLockManager()

    @After
    fun tearDown() {
        app.baseConfig.isAppPasswordProtectionOn = false
        LockScope.set(app, LockScope.WholeApp)
        forgetAppLockManager()
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

    @Test
    fun withNothingLeftOutUnlockedSearchesNothingAgain() {
        val search = MainSearchState()
        var asked = 0
        search.onSpamUnlocked = { asked++ }

        compose.setContent { rememberSpamLockWatch(search) }
        compose.waitForIdle()

        assertThat(asked).isEqualTo(0)
    }
}
