package com.glooboo.umessage.ui.spam

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.activities.MainActivity
import com.glooboo.umessage.activities.ThreadActivity
import com.glooboo.umessage.helpers.MainTabLinks
import com.glooboo.umessage.helpers.THREAD_FOCUS_COMPOSER
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_NOTICE
import com.glooboo.umessage.helpers.THREAD_NUMBER
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** "Not spam" in a blocked chat moves the messages, allows the sender and opens the normal chat to reply in. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2000dp")
class BlockedThreadOpensChatTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val now = System.currentTimeMillis()

    private fun message(id: Long, address: String) =
        BlockedMessage(id, address, "text $id", 1, false, now - id * 1000, DecisionReason.UNKNOWN_SENDER)

    private fun route(
        repo: FakeSpamRepository,
        address: String,
        threadId: Long,
        onBack: () -> Unit,
    ) {
        val vm = BlockedViewModel(app, repo, RestoredLog(prefs = null), ignoreFilter = true)
        compose.setContent {
            UMessageTheme {
                BlockedThreadRoute(
                    address = address,
                    onBack = onBack,
                    onOpenRules = {},
                    viewModel = vm,
                    threadIdOf = { _, _ -> threadId },
                )
            }
        }
    }

    /**
     * The allow rules are written on a background dispatcher and resume on the main looper, which a paused test
     * looper only runs when told to; the view model tests do the same (idle, then a short real wait).
     */
    private fun awaitClosed(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            check(System.currentTimeMillis() < deadline) { "the blocked chat did not close" }
            Thread.sleep(10)
        }
    }

    private fun started() = generateSequence { shadowOf(app).nextStartedActivity }.toList()

    @Test
    fun notSpamOpensTheChatWithTheComposerFocusedAndClosesTheBlockedChat() {
        val repo = FakeSpamRepository(
            initialBlocked = listOf(message(1, SENDER), message(2, SENDER)),
            initialRules = emptyList(),
        )
        var closed = 0
        route(repo, SENDER, threadId = 42L) { closed++ }
        compose.onNodeWithText("text 1", useUnmergedTree = true).assertExists()

        compose.onNodeWithText(app.getString(R.string.ux8c_not_spam), useUnmergedTree = true).performClick()
        awaitClosed { closed > 0 }

        val intents = started()
        val chat = intents.single { it.component?.className == ThreadActivity::class.java.name }
        assertThat(chat.getLongExtra(THREAD_ID, 0L)).isEqualTo(42L)
        assertThat(chat.getBooleanExtra(THREAD_FOCUS_COMPOSER, false)).isTrue()
        assertThat(chat.getStringExtra(THREAD_NUMBER)).isEqualTo(SENDER)
        assertThat(chat.getStringExtra(THREAD_NOTICE))
            .isEqualTo(app.getString(R.string.nsf_snack_moved_allowed, SENDER_SHOWN))
        // Back from the chat returns to the conversation list, not to the Blocked tab.
        val list = intents.single { it.component?.className == MainActivity::class.java.name }
        assertThat(list.getStringExtra(MainTabLinks.EXTRA_OPEN_TAB)).isEqualTo(MainTabLinks.TAB_MESSAGES)
        // Robolectric hands started activities back newest first: the list was started before the chat.
        assertThat(intents.indexOf(chat)).isLessThan(intents.indexOf(list))
        assertThat(closed).isEqualTo(1)

        // The messages are in the inbox, and the sender is allowed.
        assertThat(runBlocking { repo.blocked.first() }).isEmpty()
        assertThat(runBlocking { repo.rules.first() }.map { it.kind }).containsExactly(ListKind.ALLOW)
    }

    @Test
    fun aSpoofableSenderIsMovedWithoutARuleAndTheChatStillOpens() {
        val repo = FakeSpamRepository(initialBlocked = listOf(message(1, NAME)), initialRules = emptyList())
        var closed = 0
        route(repo, NAME, threadId = 7L) { closed++ }
        compose.onNodeWithText(app.getString(R.string.ux8c_not_spam), useUnmergedTree = true).performClick()
        awaitClosed { closed > 0 }

        val chat = started().single { it.component?.className == ThreadActivity::class.java.name }
        assertThat(chat.getStringExtra(THREAD_NOTICE)).isEqualTo(app.getString(R.string.nsf_snack_moved))
        assertThat(runBlocking { repo.rules.first() }).isEmpty()
    }

    @Test
    fun theButtonWaitsAndTheChatOpensOnlyOnceWhileTheMoveRuns() {
        val repo = FakeSpamRepository(initialBlocked = listOf(message(1, SENDER)), initialRules = emptyList())
        var closed = 0
        route(repo, SENDER, threadId = 5L) { closed++ }
        val button = compose.onNodeWithText(app.getString(R.string.ux8c_not_spam), useUnmergedTree = true)
        button.assertIsEnabled()
        button.performClick()
        // A second tap before the screen is gone must not start anything again.
        runCatching { button.performClick() }
        awaitClosed { closed > 0 }
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(started().count { it.component?.className == ThreadActivity::class.java.name }).isEqualTo(1)
        assertThat(closed).isEqualTo(1)
    }

    private companion object {
        const val SENDER = "+447121234567"
        const val NAME = "BANK-X"

        /** What the snackbar text shows for [SENDER]: the number as the phone displays it. */
        val SENDER_SHOWN: String get() = com.glooboo.umessage.ui.components.isolateNumbers(
            com.glooboo.umessage.ui.components.displayNumber(
                ApplicationProvider.getApplicationContext<Application>(), SENDER,
            ),
        )
    }
}
