package com.glooboo.umessage.ui.spam

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.SpamStats
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class RulesViewModelTest {

    /** Rules in memory. [failAdds] makes addRule throw; [normalize] mimics the repository's number clean-up. */
    private class Repo(initial: List<SpamRule>) : SpamRepository {
        val rulesState = MutableStateFlow(initial)
        var failAdds = false
        var normalize: (String) -> String = { it }
        private var nextId = 100L

        override val blocked: Flow<List<BlockedMessage>> = flowOf(emptyList())
        override val rules: Flow<List<SpamRule>> = rulesState
        override val settings: Flow<SpamSettings> = flowOf(SpamSettings())
        override val stats: Flow<SpamStats> = flowOf(SpamStats(0, 0, 0))

        override suspend fun addRule(rule: SpamRule): Long {
            if (failAdds) error("disk full")
            val pattern = normalize(rule.pattern)
            rulesState.value.firstOrNull {
                it.kind == rule.kind && it.type == rule.type && it.pattern == pattern && it.subscriptionId == rule.subscriptionId
            }?.let { return it.id }
            val id = nextId++
            rulesState.update { it + rule.copy(id = id, pattern = pattern) }
            return id
        }

        override suspend fun updateRule(rule: SpamRule): Long {
            if (failAdds) error("disk full")
            val pattern = normalize(rule.pattern)
            val current = rulesState.value.firstOrNull { it.id == rule.id } ?: throw NoSuchElementException()
            rulesState.value.firstOrNull {
                it.id != rule.id && it.kind == rule.kind && it.type == rule.type && it.pattern == pattern &&
                    it.subscriptionId == rule.subscriptionId
            }?.let { return it.id }
            rulesState.update { list ->
                list.map { if (it.id == rule.id) rule.copy(pattern = pattern, createdAt = current.createdAt) else it }
            }
            return rule.id
        }

        override suspend fun removeRule(id: Long) = rulesState.update { list -> list.filterNot { it.id == id } }

        override suspend fun restoreRules(rules: List<SpamRule>) = rulesState.update { it + rules }
        override suspend fun updateSettings(transform: (SpamSettings) -> SpamSettings) = Unit
        override suspend fun storeBlocked(message: BlockedMessage): Long = 0
        override suspend fun markNotSpam(blockedId: Long) = com.glooboo.umessage.spam.RestoreOutcome.NOT_FOUND
        override suspend fun blockSender(blockedId: Long) = Unit
        override suspend fun deleteBlocked(ids: List<Long>) = Unit
        override suspend fun purgeExpired() = Unit
    }

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private val events: MutableList<RulesEvent> = Collections.synchronizedList(mutableListOf())
    private lateinit var repo: Repo
    private lateinit var vm: RulesViewModel

    private fun start(rules: List<SpamRule> = emptyList()) {
        repo = Repo(rules)
        vm = RulesViewModel(app, repo)
        scope.launch { vm.state.collect { } }
        scope.launch { vm.eventFlow.collect { events += it } }
        awaitUntil { !vm.state.value.loading }
    }


    @After
    fun tearDown() = scope.cancel()

    private fun openForm(kind: ListKind, type: RuleType, pattern: String) {
        vm.selectTab(kind)
        vm.openForm()
        vm.setType(type)
        vm.setPattern(pattern)
        idleMain()
    }

    @Test
    fun unsafeRegexCannotBeSubmittedAndShowsAnError() {
        start()
        openForm(ListKind.BLOCK, RuleType.REGEX, "(a+)+")
        val form = vm.state.value.form!!
        assertThat(form.canSubmit).isFalse()
        assertThat(form.visibleError?.error).isEqualTo(RuleError.REGEX_UNSAFE)
        vm.submitForm()
        idleMain()
        assertThat(vm.state.value.form).isNotNull()
        assertThat(repo.rulesState.value).isEmpty()
    }

    @Test
    fun failedSaveKeepsTheSheetOpenWithTheTextAndSaysSo() {
        // SU-02: the sheet used to close and drop the rule.
        start()
        repo.failAdds = true
        openForm(ListKind.BLOCK, RuleType.PREFIX, "+44712")
        vm.submitForm()
        awaitUntil { vm.state.value.form?.saveFailed == true }
        val form = vm.state.value.form!!
        assertThat(form.pattern).isEqualTo("+44712")
        assertThat(form.saving).isFalse()

        repo.failAdds = false
        vm.submitForm()
        awaitUntil { vm.state.value.form == null }
        assertThat(repo.rulesState.value.map { it.pattern }).containsExactly("+44712")
    }

    @Test
    fun ruleTheRepositoryAlreadyHasAfterCleanUpShowsDuplicate() {
        // DL-52: 0712... and +44712... are the same rule once normalized; the sheet says so instead of closing.
        start(listOf(SpamRule(1, ListKind.BLOCK, RuleType.NUMBER, "+447121112222")))
        repo.normalize = { if (it.startsWith("0")) "+44" + it.drop(1) else it }
        openForm(ListKind.BLOCK, RuleType.NUMBER, "07121112222")
        vm.submitForm()
        awaitUntil { vm.state.value.form?.saving == false && vm.state.value.form?.visibleError != null }
        assertThat(vm.state.value.form!!.visibleError!!.error).isEqualTo(RuleError.DUPLICATE)
        assertThat(repo.rulesState.value).hasSize(1)
    }

    @Test
    fun allowKeywordAsksForConfirmationBeforeSaving() {
        start()
        openForm(ListKind.ALLOW, RuleType.KEYWORD, "bank")
        assertThat(vm.state.value.form!!.warnings).containsExactly(RuleWarning.ALLOW_SKIPS_BLOCKS, RuleWarning.COMMON_WORD)
        vm.submitForm()
        idleMain()
        assertThat(vm.state.value.form!!.confirming).isTrue()
        assertThat(repo.rulesState.value).isEmpty()

        vm.cancelConfirm()
        idleMain()
        assertThat(vm.state.value.form!!.confirming).isFalse()

        vm.submitForm()
        vm.confirmSubmit()
        awaitUntil { vm.state.value.form == null }
        assertThat(repo.rulesState.value.single().pattern).isEqualTo("bank")
    }

    @Test
    fun blockingAnAllowedNumberRemovesTheAllowRuleAfterConfirming() {
        start(listOf(SpamRule(1, ListKind.ALLOW, RuleType.NUMBER, "+447121112222")))
        openForm(ListKind.BLOCK, RuleType.NUMBER, "+447121112222")
        assertThat(vm.state.value.form!!.warnings).containsExactly(RuleWarning.ALLOWED_CONFLICT)
        vm.submitForm()
        vm.confirmSubmit()
        awaitUntil { vm.state.value.form == null }
        assertThat(repo.rulesState.value.map { it.kind }).containsExactly(ListKind.BLOCK)
    }

    // region SU-09: edit, search and bulk delete

    @Test
    fun editOpensTheSheetFilledInAndSavingKeepsTheId() {
        val rule = SpamRule(7, ListKind.BLOCK, RuleType.KEYWORD, "lotery", note = "typo", createdAt = 3L)
        start(listOf(rule))
        vm.openEdit(rule)
        idleMain()
        val form = vm.state.value.form!!
        assertThat(form.isEdit).isTrue()
        assertThat(form.pattern).isEqualTo("lotery")
        assertThat(form.note).isEqualTo("typo")
        // The rule being edited is not a duplicate of itself.
        assertThat(form.canSubmit).isTrue()

        vm.setPattern("lottery")
        vm.setNote("")
        vm.submitForm()
        awaitUntil { vm.state.value.form == null }
        val saved = repo.rulesState.value.single()
        assertThat(saved.id).isEqualTo(7)
        assertThat(saved.pattern).isEqualTo("lottery")
        assertThat(saved.note).isEmpty()
        assertThat(saved.createdAt).isEqualTo(3L)
    }

    @Test
    fun editUsesTheSameValidationAsAdd() {
        val rule = SpamRule(7, ListKind.BLOCK, RuleType.REGEX, "win\\d+")
        start(listOf(rule, SpamRule(8, ListKind.BLOCK, RuleType.REGEX, "prize\\d+")))
        vm.openEdit(rule)
        vm.setPattern("(a+)+")
        idleMain()
        assertThat(vm.state.value.form!!.visibleError?.error).isEqualTo(RuleError.REGEX_UNSAFE)
        vm.setPattern("prize\\d+")
        idleMain()
        assertThat(vm.state.value.form!!.visibleError?.error).isEqualTo(RuleError.DUPLICATE)
        vm.submitForm()
        idleMain()
        assertThat(repo.rulesState.value.map { it.pattern }).containsExactly("win\\d+", "prize\\d+")
    }

    @Test
    fun editThatTheRepositoryFindsIsADuplicateSaysSo() {
        // 0712... and +44712... are the same number once cleaned up.
        val a = SpamRule(1, ListKind.BLOCK, RuleType.NUMBER, "+447121112222")
        val b = SpamRule(2, ListKind.BLOCK, RuleType.NUMBER, "+447123334444")
        start(listOf(a, b))
        repo.normalize = { if (it.startsWith("0")) "+44" + it.drop(1) else it }
        vm.openEdit(b)
        vm.setPattern("07121112222")
        vm.submitForm()
        awaitUntil { vm.state.value.form?.saving == false && vm.state.value.form?.visibleError != null }
        assertThat(vm.state.value.form!!.visibleError!!.error).isEqualTo(RuleError.DUPLICATE)
        assertThat(repo.rulesState.value.single { it.id == 2L }.pattern).isEqualTo("+447123334444")
    }

    @Test
    fun unchangedEditJustClosesTheSheet() {
        val rule = SpamRule(7, ListKind.ALLOW, RuleType.KEYWORD, "delivery")
        start(listOf(rule))
        vm.openEdit(rule)
        vm.submitForm()
        idleMain()
        assertThat(vm.state.value.form).isNull()
        assertThat(vm.state.value.form?.confirming).isNull()
    }

    @Test
    fun searchMatchesValueAndNoteAndFoldsPersianLetters() {
        start(
            listOf(
                SpamRule(1, ListKind.BLOCK, RuleType.KEYWORD, "قرعه كشي"),
                SpamRule(2, ListKind.BLOCK, RuleType.NUMBER, "+447121234567", note = "Telemarketer"),
                SpamRule(3, ListKind.BLOCK, RuleType.SENDER_ID, "PROMO"),
            ),
        )
        vm.selectTab(ListKind.BLOCK)
        // Persian ک and ی find the Arabic forms ك and ي.
        vm.setQuery("کشی")
        idleMain()
        assertThat(vm.state.value.visible.map { it.id }).containsExactly(1L)
        vm.setQuery("telemark")
        idleMain()
        assertThat(vm.state.value.visible.map { it.id }).containsExactly(2L)
        vm.setQuery("0712 123")
        idleMain()
        assertThat(vm.state.value.visible.map { it.id }).containsExactly(2L)
        vm.setQuery("")
        vm.setTypeFilter(RuleType.SENDER_ID)
        idleMain()
        assertThat(vm.state.value.visible.map { it.id }).containsExactly(3L)
    }

    @Test
    fun bulkDeleteRemovesThePickedRulesAndUndoBringsThemBackWithTheirIds() {
        start(
            listOf(
                SpamRule(1, ListKind.BLOCK, RuleType.KEYWORD, "lottery"),
                SpamRule(2, ListKind.BLOCK, RuleType.KEYWORD, "prize"),
                SpamRule(3, ListKind.BLOCK, RuleType.KEYWORD, "winner"),
            ),
        )
        vm.selectTab(ListKind.BLOCK)
        vm.toggleSelected(1)
        vm.toggleSelected(3)
        idleMain()
        assertThat(vm.state.value.selecting).isTrue()
        vm.deleteSelected()
        awaitUntil { events.any { it is RulesEvent.Deleted } }
        assertThat(repo.rulesState.value.map { it.id }).containsExactly(2L)
        assertThat(vm.state.value.selected).isEmpty()

        val deleted = events.filterIsInstance<RulesEvent.Deleted>().single()
        assertThat(deleted.rules.map { it.id }).containsExactly(1L, 3L)
        vm.restore(deleted.rules)
        awaitUntil { repo.rulesState.value.size == 3 }
        assertThat(repo.rulesState.value.map { it.id }).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun selectAllPicksOnlyTheRulesTheSearchShowsAndSwitchingTabsClearsIt() {
        start(
            listOf(
                SpamRule(1, ListKind.BLOCK, RuleType.KEYWORD, "lottery"),
                SpamRule(2, ListKind.BLOCK, RuleType.KEYWORD, "prize"),
                SpamRule(3, ListKind.ALLOW, RuleType.KEYWORD, "delivery"),
            ),
        )
        vm.selectTab(ListKind.BLOCK)
        vm.setQuery("lot")
        idleMain()
        vm.selectAllVisible()
        idleMain()
        assertThat(vm.state.value.selected).containsExactly(1L)
        vm.selectTab(ListKind.ALLOW)
        idleMain()
        assertThat(vm.state.value.selected).isEmpty()
    }

    // endregion

    // region SU-33: the old block lists

    private class Legacy(var keywords: MutableList<String>, var numbers: MutableList<BlockedNumberItem>) : LegacyBlockStore {
        var dismissed = false
        val removedSystemIds = mutableListOf<Long>()
        override fun load() = LegacyLists(keywords.toList(), numbers.toList(), dismissed)
        override fun removeKeywords(keywords: Collection<String>) { this.keywords.removeAll(keywords.toSet()) }
        override fun removeSystemNumber(id: Long): Boolean {
            removedSystemIds += id
            return numbers.removeAll { it.id == id }
        }
        override fun setOfferDismissed(dismissed: Boolean) { this.dismissed = dismissed }
    }

    private lateinit var legacy: Legacy

    private fun startWithLegacy(rules: List<SpamRule> = emptyList(), keywords: List<String>, numbers: List<BlockedNumberItem>) {
        repo = Repo(rules)
        legacy = Legacy(keywords.toMutableList(), numbers.toMutableList())
        vm = RulesViewModel(app, repo, legacy)
        scope.launch { vm.state.collect { } }
        scope.launch { vm.eventFlow.collect { events += it } }
        awaitUntil { !vm.state.value.loading && !vm.state.value.legacy.offerDismissed }
    }

    @Test
    fun oldListsAreShownReadOnlyWithABannerUntilMoved() {
        startWithLegacy(keywords = listOf("lottery"), numbers = listOf(BlockedNumberItem(5, "+447120000000")))
        val state = vm.state.value
        assertThat(state.showLegacyBanner).isTrue()
        assertThat(state.visibleLegacyKeywords).containsExactly("lottery")
        assertThat(state.visibleLegacyNumbers.map { it.id }).containsExactly(5L)
        vm.setQuery("0712000")
        idleMain()
        assertThat(vm.state.value.visibleLegacyKeywords).isEmpty()
        assertThat(vm.state.value.visibleLegacyNumbers).hasSize(1)
    }

    @Test
    fun moveTurnsOldWordsIntoRulesAndLeavesTheSystemListAlone() {
        startWithLegacy(
            keywords = listOf("lottery", "x"),
            numbers = listOf(BlockedNumberItem(5, "+447120000000")),
        )
        vm.openMigration()
        idleMain()
        val plan = vm.state.value.migration!!.plan
        assertThat(plan.keywordRules.map { it.second.pattern }).containsExactly("lottery")
        assertThat(plan.keptKeywords).containsExactly("x")
        // Numbers are not moved unless ticked, and removing needs its own tick.
        assertThat(vm.state.value.migration!!.importNumbers).isFalse()
        vm.setRemoveFromSystem(true)
        idleMain()
        assertThat(vm.state.value.migration!!.removeFromSystem).isFalse()

        vm.confirmMigration()
        awaitUntil { events.any { it is RulesEvent.Migrated } }
        assertThat(repo.rulesState.value.map { it.type to it.pattern }).containsExactly(RuleType.KEYWORD to "lottery")
        assertThat(repo.rulesState.value.single().kind).isEqualTo(ListKind.BLOCK)
        // The one-letter word cannot be a rule, so it stays on the old list (and keeps filtering).
        assertThat(legacy.keywords).containsExactly("x")
        assertThat(legacy.numbers).hasSize(1)
        assertThat(legacy.removedSystemIds).isEmpty()
        assertThat(legacy.dismissed).isTrue()
        assertThat(events.filterIsInstance<RulesEvent.Migrated>().single())
            .isEqualTo(RulesEvent.Migrated(keywords = 1, numbers = 0, removedFromSystem = 0, kept = 1))
    }

    @Test
    fun theMoveFromTheAndroidListReadsTheListsAgainFirst() {
        // Bug 9: the Android block list page opens the dialog itself, right after adding numbers there.
        startWithLegacy(keywords = emptyList(), numbers = listOf(BlockedNumberItem(5, "+447120000000")))
        legacy.numbers += BlockedNumberItem(6, "+447130000000")
        vm.openMigrationFresh()
        awaitUntil { vm.state.value.migration != null }
        assertThat(vm.state.value.migration!!.plan.numberRules.map { it.first.id }).containsExactly(5L, 6L)
    }

    @Test
    fun movingNumbersRemovesThemFromTheSystemListOnlyWhenTickedAndOnlyOnceTheRuleIsStored() {
        startWithLegacy(
            keywords = emptyList(),
            numbers = listOf(BlockedNumberItem(5, "+447120000000"), BlockedNumberItem(6, "+44735*"), BlockedNumberItem(7, "+44*12*")),
        )
        vm.openMigration()
        vm.setMigrateNumbers(true)
        vm.setRemoveFromSystem(true)
        idleMain()
        val plan = vm.state.value.migration!!.plan
        assertThat(plan.numberRules.map { it.second.type to it.second.pattern })
            .containsExactly(RuleType.NUMBER to "+447120000000", RuleType.PREFIX to "+44735")
        assertThat(plan.keptNumbers.map { it.id }).containsExactly(7L)

        vm.confirmMigration()
        awaitUntil { events.any { it is RulesEvent.Migrated } }
        assertThat(repo.rulesState.value).hasSize(2)
        assertThat(legacy.removedSystemIds).containsExactly(5L, 6L)
        assertThat(legacy.numbers.map { it.id }).containsExactly(7L)
        assertThat(events.filterIsInstance<RulesEvent.Migrated>().single())
            .isEqualTo(RulesEvent.Migrated(keywords = 0, numbers = 2, removedFromSystem = 2, kept = 1))
    }

    @Test
    fun aFailedRuleKeepsItsOldEntry() {
        startWithLegacy(keywords = listOf("lottery"), numbers = listOf(BlockedNumberItem(5, "+447120000000")))
        repo.failAdds = true
        vm.openMigration()
        vm.setMigrateNumbers(true)
        vm.setRemoveFromSystem(true)
        vm.confirmMigration()
        awaitUntil { events.any { it is RulesEvent.Migrated } }
        assertThat(legacy.keywords).containsExactly("lottery")
        assertThat(legacy.removedSystemIds).isEmpty()
    }

    @Test
    fun notNowHidesTheBannerButKeepsTheOldEntriesVisible() {
        startWithLegacy(keywords = listOf("lottery"), numbers = emptyList())
        vm.dismissLegacyBanner()
        awaitUntil { legacy.dismissed }
        assertThat(vm.state.value.showLegacyBanner).isFalse()
        assertThat(vm.state.value.visibleLegacyKeywords).containsExactly("lottery")
    }

    // endregion

    // region Design review 2: notes stored as codes (bug 4), concrete examples (ID-53)

    @Test
    fun editingARuleWithAnAppNoteKeepsTheCodeWhenTheFieldIsLeftEmpty() {
        val rule = SpamRule(1, ListKind.BLOCK, RuleType.NUMBER, "+447121234567", note = "Blocked by you")
        start(listOf(rule))
        vm.openEdit(rule)
        idleMain()
        val form = vm.state.value.form!!
        // The old English words are not put in the field, where they would stay English for good.
        assertThat(form.note).isEmpty()
        assertThat(form.storedNote).isEqualTo("Blocked by you")
        assertThat(form.unchanged).isTrue()

        vm.setSim(2)
        vm.submitForm()
        awaitUntil { vm.state.value.form == null }
        assertThat(RuleNotes.codeOf(repo.rulesState.value.single().note)).isEqualTo(RuleNotes.BLOCKED_BY_YOU)
    }

    @Test
    fun typingANoteReplacesTheAppNote() {
        val rule = SpamRule(1, ListKind.BLOCK, RuleType.NUMBER, "+447121234567", note = RuleNotes.BLOCKED_BY_YOU)
        start(listOf(rule))
        vm.openEdit(rule)
        vm.setNote("Gym")
        idleMain()
        assertThat(vm.state.value.form!!.storedNote).isEqualTo("Gym")
    }

    @Test
    fun concreteExampleOpensTheSheetFilledInAndValid() {
        start()
        vm.openExample(ListKind.BLOCK, RuleType.KEYWORD, RuleExamples.CANCEL_WORD)
        idleMain()
        val form = vm.state.value.form!!
        assertThat(form.pattern).isEqualTo(RuleExamples.CANCEL_WORD)
        assertThat(form.canSubmit).isTrue()
        vm.openExample(ListKind.BLOCK, RuleType.PREFIX, RuleExamples.AD_PREFIX)
        idleMain()
        assertThat(vm.state.value.form!!.canSubmit).isTrue()
    }

    @Test
    fun migratedRulesGetNoteCodesNotWords() {
        startWithLegacy(keywords = listOf("lottery"), numbers = emptyList())
        vm.openMigration()
        idleMain()
        val rule = vm.state.value.migration!!.plan.keywordRules.single().second
        assertThat(rule.note).isEqualTo(RuleNotes.OLD_WORD_LIST)
    }

    @Test
    fun contactsPickedInTheSheetBecomeOneRuleEachAndExistingNumbersAreSkipped() {
        start(listOf(SpamRule(id = 1, kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = "+14155550101")))
        vm.selectTab(ListKind.ALLOW)
        vm.openForm()
        idleMain()
        vm.addFromContacts(
            listOf(
                PickedNumber("Anna", "+14155550101"),
                PickedNumber("Ben", "+14155550102"),
                PickedNumber("Cleo", "+14155550103"),
            ),
        )
        awaitUntil { events.any { it is RulesEvent.AddedFromContacts } }
        val allowed = repo.rulesState.value.filter { it.kind == ListKind.ALLOW }
        assertThat(allowed.map { it.pattern }).containsExactly("+14155550102", "+14155550103")
        assertThat(allowed.map { it.note }).containsExactly("Ben", "Cleo")
        assertThat(events.filterIsInstance<RulesEvent.AddedFromContacts>().single())
            .isEqualTo(RulesEvent.AddedFromContacts(added = 2, skipped = 1))
        assertThat(vm.state.value.form).isNull()
    }

    // endregion

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            idleMain()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "condition not met; state=${vm.state.value} events=$events" }
            Thread.sleep(10)
        }
    }
}
