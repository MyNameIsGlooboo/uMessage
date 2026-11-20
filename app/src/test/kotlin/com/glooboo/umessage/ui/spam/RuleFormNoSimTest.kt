package com.glooboo.umessage.ui.spam

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.AllowGroupMember
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Provider truth, step G: the rule form has no SIM (every spam rule covers both SIMs, 7.2), the test sheet answers
 * about the sender, automatic rules sort after the user's own and lose to a block, and the switch draws the card.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2000dp")
class RuleFormNoSimTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val res = app.resources
    private val scope = CoroutineScope(Job() + Dispatchers.Main)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun aSavedRuleCoversBothSims() {
        val repo = FakeSpamRepository(initialBlocked = emptyList(), initialRules = emptyList())
        val vm = RulesViewModel(app, repo)
        scope.launch { vm.state.collect { } }
        awaitUntil { !vm.state.value.loading }
        vm.selectTab(ListKind.BLOCK)
        vm.openForm()
        vm.setType(RuleType.NUMBER)
        vm.setPattern("+15550001234")
        idleMain()
        vm.submitForm()
        awaitUntil { vm.state.value.form == null }
        assertThat(runBlocking { repo.rules.first() }.single().subscriptionId).isNull()
    }

    @Test
    fun editingAnOldOneSimRuleSavesItForBothSims() {
        val old = SpamRule(5, ListKind.BLOCK, RuleType.NUMBER, "+15550001234", subscriptionId = 2)
        val repo = FakeSpamRepository(initialBlocked = emptyList(), initialRules = listOf(old))
        val vm = RulesViewModel(app, repo)
        scope.launch { vm.state.collect { } }
        awaitUntil { !vm.state.value.loading }
        vm.openEdit(old)
        idleMain()
        // Nothing else changed, but the SIM goes: the edit is not "unchanged".
        assertThat(vm.state.value.form!!.unchanged).isFalse()
        vm.submitForm()
        awaitUntil { vm.state.value.form == null }
        assertThat(runBlocking { repo.rules.first() }.single().subscriptionId).isNull()
    }

    @Test
    fun moreOptionsHasANoteButNoSimChoice() {
        val form = RuleForm(kind = ListKind.BLOCK, type = RuleType.NUMBER)
        compose.setContent { UMessageTheme { AddRuleSheet(form, RulesActions()) } }
        compose.onNodeWithText(res.getString(R.string.ux8d_more_options)).performClick()
        compose.onNodeWithText(res.getString(R.string.spam_sheet_note_label)).assertExists()
        compose.onNodeWithText(res.getString(R.string.spam_sheet_sim_label)).assertDoesNotExist()
        compose.onNodeWithText(res.getString(R.string.spam_sim_both)).assertDoesNotExist()
    }

    // region Test sheet answers (4.5)

    private val keyword = SpamRule(10, ListKind.ALLOW, RuleType.KEYWORD, "meeting code")
    private val blockWord = SpamRule(11, ListKind.BLOCK, RuleType.KEYWORD, "lottery")
    private val blockNumber = SpamRule(12, ListKind.BLOCK, RuleType.NUMBER, "+15550001234")
    private val context = TestContext(
        rules = listOf(keyword, blockWord, blockNumber),
        members = listOf(AllowGroupMember(AllowGroup.WROTE, "+15550001234", "+1 555 000 1234")),
        settings = SpamSettings(),
    )

    private fun message(body: String = "hello", address: String = "+15550001234") =
        IncomingMessage(address = address, body = body, subscriptionId = -1)

    @Test
    fun aWordAllowsOrBlocksTheSenderAndSaysSo() {
        val allowed = TestVerdict.of(FilterDecision.Allow(DecisionReason.ALLOW_RULE, 10), message(), context)
        assertThat(allowed.kind).isEqualTo(TestVerdict.Kind.ALLOWED_WORD)
        assertThat(verdictSentence(res, allowed)).isEqualTo(res.getString(R.string.pt_verdict_allowed_word))

        val blocked = TestVerdict.of(FilterDecision.Block(DecisionReason.BLOCK_RULE, 11), message(), context)
        assertThat(blocked.kind).isEqualTo(TestVerdict.Kind.BLOCKED_WORD)
        assertThat(blocked.held).isTrue()
        assertThat(verdictSentence(res, blocked)).isEqualTo(res.getString(R.string.pt_verdict_blocked_word))

        val byNumber = TestVerdict.of(FilterDecision.Block(DecisionReason.BLOCK_RULE, 12), message(), context)
        assertThat(byNumber.kind).isEqualTo(TestVerdict.Kind.HELD_BY_RULE)
    }

    @Test
    fun aGroupMemberIsNamedWithItsGroup() {
        val verdict = TestVerdict.of(FilterDecision.Allow(DecisionReason.GROUP_MEMBER), message(), context)
        assertThat(verdict).isEqualTo(TestVerdict(TestVerdict.Kind.GROUP, AllowGroup.WROTE))
        assertThat(verdictSentence(res, verdict)).isEqualTo(
            res.getString(R.string.pt_verdict_group, res.getString(R.string.pt_group_wrote_title)),
        )
    }

    @Test
    fun aMemberOfAGroupThatIsOffIsNotNamed() {
        val off = context.copy(settings = SpamSettings(wroteGroupOn = false))
        assertThat(memberGroupOf(off.members, off.settings, "+15550001234")).isNull()
        assertThat(memberGroupOf(context.members, context.settings, "5550001234")).isEqualTo(AllowGroup.WROTE)
        assertThat(memberGroupOf(context.members, context.settings, "OtherShop")).isNull()
    }

    @Test
    fun codesUnknownSendersAndTheSwitchKeepTheirAnswers() {
        val code = TestVerdict.of(FilterDecision.Allow(DecisionReason.OTP_EXEMPTION), message(), context)
        assertThat(verdictSentence(res, code)).isEqualTo(res.getString(R.string.w3b_verdict_allowed_code))
        val off = TestVerdict.of(FilterDecision.Allow(DecisionReason.MODE_OFF), message(), context)
        assertThat(off.kind).isEqualTo(TestVerdict.Kind.ALLOWED_MODE_OFF)
        val link = TestVerdict.of(
            FilterDecision.Block(DecisionReason.UNKNOWN_SENDER), message("see https://example.com"), context,
        )
        assertThat(link.kind).isEqualTo(TestVerdict.Kind.HELD_UNKNOWN_WITH_LINK)
    }

    // endregion

    // region Order, conflicts and the card

    private val auto = SpamRule(
        20, ListKind.ALLOW, RuleType.NUMBER, "+15550001234", note = RuleNotes.ALLOWED_FROM_KEYWORD, createdAt = 9_000,
    )
    private val own = SpamRule(21, ListKind.ALLOW, RuleType.SENDER_ID, "AcmeBank", createdAt = 1_000)

    @Test
    fun rulesTheFilterMadeComeAfterTheUsersOwn() {
        assertThat(RulesOrder.DEFAULT.apply(listOf(auto, own)).map { it.id }).containsExactly(21L, 20L).inOrder()
        assertThat(RulesOrder.NEWEST.apply(listOf(own, auto)).map { it.id }).containsExactly(21L, 20L).inOrder()
    }

    @Test
    fun anAutomaticAllowDoesNotOverrideABlock() {
        val block = SpamRule(30, ListKind.BLOCK, RuleType.NUMBER, "+15550001234")
        assertThat(RuleConflicts.overriddenBy(listOf(auto, block), block)).isNull()
        val mine = auto.copy(id = 31, note = "")
        assertThat(RuleConflicts.overriddenBy(listOf(mine, block), block)?.id).isEqualTo(31L)
    }

    @Test
    fun theCardFollowsTheSwitchAndTheCodeGroup() {
        assertThat(SpamSettings(filterOn = true).shownMode()).isEqualTo(UnknownSenderMode.SMART)
        assertThat(SpamSettings(filterOn = true, codeGroupOn = false).shownMode()).isEqualTo(UnknownSenderMode.STRICT)
        assertThat(SpamSettings(filterOn = false, mode = UnknownSenderMode.STRICT).shownMode())
            .isEqualTo(UnknownSenderMode.OFF)
        // The old Fossify switch is already read into the filter switch.
        assertThat(ContactsOffPolicy.cardMode(true, UnknownSenderMode.OFF, legacyBlockUnknown = true))
            .isEqualTo(UnknownSenderMode.OFF)
    }

    @Test
    fun withTheFlagOffTheModeIsDrawnAsIn0414() {
        com.glooboo.umessage.helpers.ProviderTruthFlag.enabled = false
        assertThat(SpamSettings(mode = UnknownSenderMode.STRICT, filterOn = false).shownMode())
            .isEqualTo(UnknownSenderMode.STRICT)
        assertThat(RulesOrder.DEFAULT.apply(listOf(own, auto)).map { it.id }).containsExactly(20L, 21L).inOrder()
    }

    // endregion

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            idleMain()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "condition not met" }
            Thread.sleep(10)
        }
    }
}
