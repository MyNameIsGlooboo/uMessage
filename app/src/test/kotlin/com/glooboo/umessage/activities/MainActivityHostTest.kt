package com.glooboo.umessage.activities

import android.Manifest
import android.app.Application
import android.app.role.RoleManager
import android.content.pm.ShortcutManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.helpers.NewConversationShortcut
import com.glooboo.umessage.helpers.SEARCHED_MESSAGE_ID
import com.glooboo.umessage.helpers.SEARCHED_MESSAGE_IS_MMS
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_TITLE
import com.glooboo.umessage.models.Events
import com.glooboo.umessage.models.SearchResult
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.onboarding.OnboardingActivity
import com.glooboo.umessage.ui.onboarding.OnboardingState
import com.google.common.truth.Truth.assertThat
import org.greenrobot.eventbus.EventBus
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowRoleManager

/** What the conversation list's host does around the list: onboarding, search, shortcuts and refresh events (MC-38). */
@RunWith(AndroidJUnit4::class)
class MainActivityHostTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val roles get() = shadowOf(app.getSystemService(RoleManager::class.java)) as ShadowRoleManager
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        OnboardingState.setCompleted(app)
        app.config.wasDbCleared = true
        roles.addAvailableRole(RoleManager.ROLE_SMS)
        roles.addHeldRole(RoleManager.ROLE_SMS)
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    @After
    fun tearDown() {
        scenario?.close()
        if (app.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)) {
            roles.removeHeldRole(RoleManager.ROLE_SMS)
        }
        resetSharedMessagesDatabase()
    }

    private fun refreshSubscribed() = EventBus.getDefault().hasSubscriberForEvent(Events.RefreshConversations::class.java)

    @Test
    fun `an unfinished onboarding opens it instead of the list`() {
        OnboardingState.setCompleted(app, false)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val started = shadowOf(app).nextStartedActivity
        assertThat(started.component?.className).isEqualTo(OnboardingActivity::class.java.name)
        assertThat(scenario!!.state).isEqualTo(Lifecycle.State.DESTROYED)
    }

    @Test
    fun `refresh events are received while the list is open, also after rotation, and not after it closes`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertThat(refreshSubscribed()).isTrue()

        scenario!!.recreate()
        assertThat(refreshSubscribed()).isTrue()

        scenario!!.close()
        scenario = null
        assertThat(refreshSubscribed()).isFalse()
    }

    @Test
    fun `a new icon colour updates the New conversation shortcut once`() {
        app.config.lastHandledShortcutColor = 1
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val manager = app.getSystemService(ShortcutManager::class.java)
        assertThat(manager.dynamicShortcuts.map { it.id }).contains(NewConversationShortcut.ID)
        assertThat(app.config.lastHandledShortcutColor).isEqualTo(app.config.appIconColor)
    }

    @Test
    fun `an open search keeps its query across rotation`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithContentDescription(app.getString(R.string.main_search_hint)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("ab")
        compose.onNodeWithContentDescription(app.getString(R.string.main_search_back)).assertIsDisplayed()

        scenario!!.recreate()
        compose.onNode(hasSetTextAction() and hasText("ab")).assertExists()
        compose.onNodeWithContentDescription(app.getString(R.string.main_search_back)).assertIsDisplayed()
    }

    @Test
    fun `one Back closes an open search`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithContentDescription(app.getString(R.string.main_search_hint)).performClick()
        compose.onNodeWithContentDescription(app.getString(R.string.main_search_back)).assertIsDisplayed()

        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithContentDescription(app.getString(R.string.main_search_back)).fetchSemanticsNodes())
            .isEmpty()
        scenario!!.onActivity { assertThat(it.isFinishing).isFalse() }
    }

    @Test
    fun `one Back closes a search with a query too`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithContentDescription(app.getString(R.string.main_search_hint)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("hello")

        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithContentDescription(app.getString(R.string.main_search_back)).fetchSemanticsNodes())
            .isEmpty()
        compose.onNodeWithContentDescription(app.getString(R.string.main_search_hint)).assertExists()
    }

    @Test
    fun `a search result opens its chat at the message`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        generateSequence { shadowOf(app).nextStartedActivity }.count()
        scenario!!.onActivity { activity ->
            MainRoutes(activity).openSearchResult(
                SearchResult(
                    messageId = 42, title = "Ali", snippet = "", date = "", threadId = 7, photoUri = "", isMMS = true,
                ),
            )
        }
        val started = shadowOf(app).nextStartedActivity
        assertThat(started.component?.className).isEqualTo(ThreadActivity::class.java.name)
        assertThat(started.getLongExtra(THREAD_ID, 0)).isEqualTo(7)
        assertThat(started.getStringExtra(THREAD_TITLE)).isEqualTo("Ali")
        assertThat(started.getLongExtra(SEARCHED_MESSAGE_ID, 0)).isEqualTo(42)
        assertThat(started.getBooleanExtra(SEARCHED_MESSAGE_IS_MMS, false)).isTrue()
    }
}
