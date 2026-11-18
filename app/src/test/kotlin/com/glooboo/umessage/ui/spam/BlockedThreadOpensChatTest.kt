package com.glooboo.umessage.ui.spam

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
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
        threadIdOf: suspend (Context, String) -> Long = { _, _ -> threadId },
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
                    threadIdOf = threadIdOf,
                )
            }
        }
    }

    private fun routeRestorable(
        repo: FakeSpamRepository,
        address: String,
        threadId: Long,
        onBack: () -> Unit,
    ): StateRestorationTester {
        val vm = BlockedViewModel(app, repo, RestoredLog(prefs = null), ignoreFilter = true)
        return StateRestorationTester(compose).also { tester ->
            tester.setContent {
                UMessageTheme {
                    BlockedThreadRoute(address, onBack, {}, viewModel = vm, threadIdOf = { _, _ -> threadId })
                }
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
        // Back from the chat returns to the Blocked tab, where the user was triaging, not to the conversation list.
        val list = intents.single { it.component?.className == MainActivity::class.java.name }
        assertThat(list.getStringExtra(MainTabLinks.EXTRA_OPEN_TAB)).isEqualTo(MainTabLinks.TAB_BLOCKED)
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
        // The chat waits for the answer to "Always allow this name?"; "Not now" changes nothing.
        awaitText(app.getString(R.string.nameallow_title))
        assertThat(closed).isEqualTo(0)
        compose.onNodeWithText(app.getString(R.string.nameallow_not_now)).performClick()
        awaitClosed { closed > 0 }

        val chat = started().single { it.component?.className == ThreadActivity::class.java.name }
        assertThat(chat.getStringExtra(THREAD_NOTICE)).isEqualTo(app.getString(R.string.nsf_snack_moved))
        assertThat(runBlocking { repo.rules.first() }).isEmpty()
    }

    @Test
    fun alwaysAllowingTheNameAddsOneSenderNameRuleAndTheChatOpens() {
        val repo = FakeSpamRepository(initialBlocked = listOf(message(1, NAME)), initialRules = emptyList())
        var closed = 0
        route(repo, NAME, threadId = 7L) { closed++ }
        compose.onNodeWithText(app.getString(R.string.ux8c_not_spam), useUnmergedTree = true).performClick()
        awaitText(app.getString(R.string.nameallow_title))
        compose.onNodeWithText(app.getString(R.string.nameallow_allow)).performClick()
        // The rule is stored in the background; its Undo snackbar is on this page, and the chat waits for it.
        val undo = app.getString(R.string.spam_undo)
        awaitText(undo)
        assertThat(runBlocking { repo.rules.first() }).hasSize(1)
        assertThat(closed).isEqualTo(0)
        compose.onNodeWithText(undo).assertExists()
        // Keeping the rule (the snackbar runs out) lets the chat open.
        compose.mainClock.advanceTimeBy(SNACKBAR_MS)
        awaitClosed { closed > 0 }

        val chat = started().single { it.component?.className == ThreadActivity::class.java.name }
        assertThat(chat.getLongExtra(THREAD_ID, 0L)).isEqualTo(7L)
        val rule = runBlocking { repo.rules.first() }.single()
        assertThat(rule.kind).isEqualTo(ListKind.ALLOW)
        assertThat(rule.type).isEqualTo(RuleType.SENDER_ID)
        assertThat(rule.pattern).isEqualTo(NAME)
    }

    @Test
    fun aSlowThreadLookupDoesNotCloseThePageBeforeTheChatOpens() {
        val repo = FakeSpamRepository(initialBlocked = listOf(message(1, NAME)), initialRules = emptyList())
        val gate = CompletableDeferred<Long>()
        var closed = 0
        route(repo, NAME, threadId = 0L, threadIdOf = { _, _ -> gate.await() }) { closed++ }
        compose.onNodeWithText(app.getString(R.string.ux8c_not_spam), useUnmergedTree = true).performClick()
        awaitText(app.getString(R.string.nameallow_title))
        compose.onNodeWithText(app.getString(R.string.nameallow_allow)).performClick()
        val undo = app.getString(R.string.spam_undo)
        awaitText(undo)
        // The snackbar ends unanswered while the lookup is still waiting: the page must stay put meanwhile.
        compose.mainClock.advanceTimeBy(SNACKBAR_MS)
        repeat(IDLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(IDLE_STEP_MS)
            Thread.sleep(IDLE_STEP_MS)
        }
        assertThat(closed).isEqualTo(0)

        gate.complete(7L)
        awaitClosed { closed > 0 }
        val chat = started().single { it.component?.className == ThreadActivity::class.java.name }
        assertThat(chat.getLongExtra(THREAD_ID, 0L)).isEqualTo(7L)
        assertThat(closed).isEqualTo(1)
    }

    @Test
    fun undoingTheNameRuleOnTheBlockedPageIsPossibleAndThenTheChatOpens() {
        val repo = FakeSpamRepository(initialBlocked = listOf(message(1, NAME)), initialRules = emptyList())
        var closed = 0
        route(repo, NAME, threadId = 7L) { closed++ }
        compose.onNodeWithText(app.getString(R.string.ux8c_not_spam), useUnmergedTree = true).performClick()
        awaitText(app.getString(R.string.nameallow_title))
        compose.onNodeWithText(app.getString(R.string.nameallow_allow)).performClick()
        val undo = app.getString(R.string.spam_undo)
        awaitText(undo)
        compose.onNodeWithText(undo).performClick()
        awaitClosed { closed > 0 }
        assertThat(runBlocking { repo.rules.first() }).isEmpty()
    }

    @Test
    fun rotatingWhileTheQuestionIsOpenStillOpensTheChatAfterTheAnswer() {
        val repo = FakeSpamRepository(initialBlocked = listOf(message(1, NAME)), initialRules = emptyList())
        var closed = 0
        val tester = routeRestorable(repo, NAME, threadId = 7L) { closed++ }
        compose.onNodeWithText(app.getString(R.string.ux8c_not_spam), useUnmergedTree = true).performClick()
        awaitText(app.getString(R.string.nameallow_title))
        tester.emulateSavedInstanceStateRestore()
        awaitText(app.getString(R.string.nameallow_title))
        compose.onNodeWithText(app.getString(R.string.nameallow_not_now)).performClick()
        awaitClosed { closed > 0 }

        assertThat(started().count { it.component?.className == ThreadActivity::class.java.name }).isEqualTo(1)
    }

    private fun awaitText(text: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            check(System.currentTimeMillis() < deadline) { "\"$text\" did not show" }
            Thread.sleep(10)
        }
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

        /** A little longer than a Long snackbar. */
        const val SNACKBAR_MS = 11_000L

        /** How long the slow lookup is given to wrongly close the page: this many rounds of this many ms. */
        const val IDLE_ROUNDS = 20
        const val IDLE_STEP_MS = 50L

        /** What the snackbar text shows for [SENDER]: the number as the phone displays it. */
        val SENDER_SHOWN: String get() = com.glooboo.umessage.ui.components.isolateNumbers(
            com.glooboo.umessage.ui.components.displayNumber(
                ApplicationProvider.getApplicationContext<Application>(), SENDER,
            ),
        )
    }
}
