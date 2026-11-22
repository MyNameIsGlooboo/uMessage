package com.glooboo.umessage.ui.spam

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.RuleMatcher
import com.glooboo.umessage.testing.ProcessReturn
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Provider truth, step H (section 5): "Test this rule" counts the Spam part from the texts of the chats in Spam
 * ([RulePreviewSource.spam]), not from the old held rows. With the flag off it still counts the held rows.
 */
@RunWith(AndroidJUnit4::class)
class RulesPreviewSpamPartTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private lateinit var vm: RulesViewModel

    /** One Spam-chat text and nothing in the inbox. */
    private object Source : RulePreviewSource {
        override fun matcher() = RuleMatcher(NumberNormalizer { "US" })
        override fun inbox(limit: Int): List<DryRunMessage> = emptyList()
        override fun spam(limit: Int): List<DryRunMessage> {
            // H-19: the app comes back locked while the scan runs, after the senders were found visible.
            if (lockDuringScan) visible = false
            return listOf(DryRunMessage("+15550001234", "Win a prize now", 0))
        }

        @Volatile
        var visible = true

        @Volatile
        var lockDuringScan = false

        override fun blockedSendersVisible() = visible
    }

    private fun held(id: Long) = BlockedMessage(
        id = id, address = "+15550001235", body = "Another prize", subscriptionId = 0, isMms = false,
        receivedAt = id, reason = DecisionReason.UNKNOWN_SENDER,
    )

    @After
    fun tearDown() {
        Source.visible = true
        Source.lockDuringScan = false
        scope.cancel()
    }

    private fun previewOf(pattern: String): RuleDryRunResult {
        val repo = FakeSpamRepository(initialBlocked = listOf(held(1), held(2)), initialRules = emptyList())
        vm = RulesViewModel(app, repo, previewSource = Source)
        scope.launch { vm.state.collect { } }
        awaitUntil { !vm.state.value.loading }
        vm.selectTab(ListKind.BLOCK)
        vm.openForm()
        vm.setType(RuleType.KEYWORD)
        vm.setPattern(pattern)
        idleMain()
        vm.previewRule()
        awaitUntil { vm.state.value.preview is RulePreviewState.Done }
        return (vm.state.value.preview as RulePreviewState.Done).result
    }

    @Test
    fun theSpamPartReadsTheSpamChats() {
        val result = previewOf("prize")

        assertThat(result.blocked.scanned).isEqualTo(1)
        assertThat(result.blocked.matched).isEqualTo(1)
        assertThat(result.blocked.senders).containsExactly("+15550001234")
    }

    @Test
    fun withTheFlagOffTheSpamPartReadsTheHeldRows() {
        ProviderTruthFlag.enabled = false

        val result = previewOf("prize")

        assertThat(result.blocked.matched).isEqualTo(2)
        assertThat(result.blocked.senders).containsExactly("+15550001235")
    }

    @Test
    fun theSpamSendersGoWhenTheAppComesBackLocked() {
        // H-16: the preview ran while Spam was open; the lock locks again while the app is away.
        assertThat(previewOf("prize").blocked.senders).isNotEmpty()

        Source.visible = false
        vm.onAppReturned()
        awaitUntil { (vm.state.value.preview as? RulePreviewState.Done)?.result?.blocked?.senders?.isEmpty() == true }

        val shown = (vm.state.value.preview as RulePreviewState.Done).result
        assertThat(shown.blocked.senders).isEmpty()
        assertThat(shown.blocked.matched).isEqualTo(1)
    }

    @Test
    fun aPreviewThatFinishesAfterALockedReturnShowsNoSpamSenders() {
        // H-19: the return came while the preview was Loading, so only its finish can drop the senders.
        Source.lockDuringScan = true

        val result = previewOf("prize")

        assertThat(result.blocked.senders).isEmpty()
        assertThat(result.blocked.matched).isEqualTo(1)
    }

    @Test
    fun theAppReturnWatchOfTheModelDropsTheSpamSenders() {
        // H-21: the model's watch is on the process lifecycle, so a real return to the front runs onAppReturned.
        assertThat(previewOf("prize").blocked.senders).isNotEmpty()

        Source.visible = false
        ProcessReturn.comeBack()
        awaitUntil { (vm.state.value.preview as? RulePreviewState.Done)?.result?.blocked?.senders?.isEmpty() == true }

        assertThat((vm.state.value.preview as RulePreviewState.Done).result.blocked.matched).isEqualTo(1)
    }

    @Test
    fun hidingTheSpamSendersKeepsTheCountsAndTheInboxExamples() {
        val done = RulePreviewState.Done(
            RuleDryRunResult(DryRunPart(5, 2, listOf("+15550001235")), DryRunPart(3, 1, listOf("+15550001234"))),
        )

        val hidden = done.hidingSpamSenders(visible = false) as RulePreviewState.Done

        assertThat(hidden.result.inbox.senders).containsExactly("+15550001235")
        assertThat(hidden.result.blocked).isEqualTo(DryRunPart(3, 1, emptyList()))
        assertThat(done.hidingSpamSenders(visible = true)).isSameInstanceAs(done)
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            idleMain()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "condition not met; state=${vm.state.value}" }
            Thread.sleep(10)
        }
    }
}
