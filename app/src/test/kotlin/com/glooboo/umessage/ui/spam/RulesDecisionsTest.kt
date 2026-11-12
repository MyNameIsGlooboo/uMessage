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

/** Rules screen decisions: website rules (spam-22), hit counts (spam-23), newest-first order (main-23), spam-15. */
@RunWith(AndroidJUnit4::class)
class RulesDecisionsTest {

    private class Repo(initial: List<SpamRule>) : SpamRepository {
        val rulesState = MutableStateFlow(initial)
        private var nextId = 100L

        override val blocked: Flow<List<BlockedMessage>> = flowOf(emptyList())
        override val rules: Flow<List<SpamRule>> = rulesState
        override val settings: Flow<SpamSettings> = flowOf(SpamSettings())
        override val stats: Flow<SpamStats> = flowOf(SpamStats(0, 0, 0))

        override suspend fun addRule(rule: SpamRule): Long {
            val id = nextId++
            rulesState.update { it + rule.copy(id = id) }
            return id
        }

        override suspend fun updateRule(rule: SpamRule): Long {
            rulesState.update { list -> list.map { if (it.id == rule.id) rule else it } }
            return rule.id
        }

        override suspend fun removeRule(id: Long) = rulesState.update { list -> list.filterNot { it.id == id } }
        override suspend fun updateSettings(transform: (SpamSettings) -> SpamSettings) = Unit
        override suspend fun storeBlocked(message: BlockedMessage): Long = 0
        override suspend fun markNotSpam(blockedId: Long) = com.glooboo.umessage.spam.RestoreOutcome.NOT_FOUND
        override suspend fun blockSender(blockedId: Long) = Unit
        override suspend fun deleteBlocked(ids: List<Long>) = Unit
        override suspend fun purgeExpired() = Unit
    }

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Job() + Dispatchers.Main)
    private lateinit var vm: RulesViewModel

    @After
    fun tearDown() = scope.cancel()

    private fun start(rules: List<SpamRule> = emptyList(), store: RulesOrderStore = MemoryRulesOrderStore()): Repo {
        val repo = Repo(rules)
        vm = RulesViewModel(app, repo, orderStore = store)
        scope.launch { vm.state.collect { } }
        awaitUntil { !vm.state.value.loading }
        return repo
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

    private fun domain(value: String) = RuleValidator.validate(ListKind.BLOCK, RuleType.DOMAIN, value)

    // region spam-22

    @Test
    fun domainIsCleanedTheSameWayTheEngineCleansIt() {
        assertThat(domain("example.com")).isEqualTo(RuleValidation.Valid("example.com"))
        assertThat(domain("  HTTPS://Example.COM/shop?x=1  ")).isEqualTo(RuleValidation.Valid("example.com"))
        assertThat(domain("*.example.com")).isEqualTo(RuleValidation.Valid("example.com"))
        assertThat(domain("example[.]com")).isEqualTo(RuleValidation.Valid("example.com"))
        assertThat(domain("www.example.com:8080/")).isEqualTo(RuleValidation.Valid("www.example.com"))
        assertThat(domain("sub.example.co.uk.")).isEqualTo(RuleValidation.Valid("sub.example.co.uk"))
    }

    @Test
    fun domainWithoutADotOrWithJunkIsRefused() {
        listOf(
            "example", "localhost", "exa mple.com", "example.c", "-example.com", "exa_mple.com", "exam!ple.com",
            "a..com",
        )
            .forEach { assertThat(domain(it)).isEqualTo(RuleValidation.Invalid(RuleError.DOMAIN)) }
        assertThat(domain("")).isEqualTo(RuleValidation.Invalid(RuleError.EMPTY))
        assertThat(domain("a".repeat(64) + ".com")).isEqualTo(RuleValidation.Invalid(RuleError.DOMAIN))
    }

    @Test
    fun allowingADomainAsksForConfirmationLikeAnAllowedWord() {
        assertThat(RuleValidator.warnings(ListKind.ALLOW, RuleType.DOMAIN, "example.com", emptyList(), null))
            .containsExactly(RuleWarning.ALLOW_SKIPS_BLOCKS)
        assertThat(RuleValidator.warnings(ListKind.BLOCK, RuleType.DOMAIN, "example.com", emptyList(), null)).isEmpty()
    }

    @Test
    fun domainFormSavesTheCleanedNameAndSpotsDuplicates() {
        val repo = start(listOf(SpamRule(1, ListKind.BLOCK, RuleType.DOMAIN, "example.com")))
        vm.selectTab(ListKind.BLOCK)
        vm.openForm()
        vm.setType(RuleType.DOMAIN)
        vm.setPattern("https://Shop.Example.org/a")
        idleMain()
        assertThat(vm.state.value.form!!.canSubmit).isTrue()
        vm.submitForm()
        awaitUntil { vm.state.value.form == null }
        assertThat(repo.rulesState.value.map { it.pattern }).containsExactly("example.com", "shop.example.org")

        vm.openForm()
        vm.setType(RuleType.DOMAIN)
        vm.setPattern("http://EXAMPLE.com")
        idleMain()
        assertThat(vm.state.value.form!!.visibleError?.error).isEqualTo(RuleError.DUPLICATE)
    }

    @Test
    fun domainRulesAreFoundBySearchAndTypeFilter() {
        val rule = SpamRule(1, ListKind.BLOCK, RuleType.DOMAIN, "example.com")
        assertThat(RuleSearch.matches(rule, "example")).isTrue()
        assertThat(RuleSearch.matches(rule, "example", RuleType.DOMAIN)).isTrue()
        assertThat(RuleSearch.matches(rule, "example", RuleType.KEYWORD)).isFalse()
        assertThat(RulesSearchPolicy.chipTypes(listOf(rule, rule.copy(id = 2, type = RuleType.NUMBER)), null))
            .containsExactly(RuleType.NUMBER, RuleType.DOMAIN)
    }

    @Test
    fun domainRulesSurviveTheRulesFileRoundTrip() {
        val text = RuleTextCodec.encode(listOf(SpamRule(1, ListKind.BLOCK, RuleType.DOMAIN, "example.com")))
        val parsed = RuleTextCodec.decode(text)
        assertThat(parsed.invalidLines).isEqualTo(0)
        assertThat(parsed.rules.single().type).isEqualTo(RuleType.DOMAIN)
    }

    // endregion

    // region spam-23

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test
    fun hitAgeUsesTheLargestWholeUnit() {
        assertThat(RuleHits.age(0, now)).isNull()
        assertThat(RuleHits.age(now - 30_000, now)).isEqualTo(RuleHitAge(RuleHitUnit.NOW, 0))
        assertThat(RuleHits.age(now + 5_000, now)).isEqualTo(RuleHitAge(RuleHitUnit.NOW, 0))
        assertThat(RuleHits.age(now - 5 * 60_000, now)).isEqualTo(RuleHitAge(RuleHitUnit.MINUTES, 5))
        assertThat(RuleHits.age(now - 3 * 3_600_000, now)).isEqualTo(RuleHitAge(RuleHitUnit.HOURS, 3))
        assertThat(RuleHits.age(now - 3 * day, now)).isEqualTo(RuleHitAge(RuleHitUnit.DAYS, 3))
        assertThat(RuleHits.age(now - 29 * day, now)).isEqualTo(RuleHitAge(RuleHitUnit.DAYS, 29))
        assertThat(RuleHits.age(now - 65 * day, now)).isEqualTo(RuleHitAge(RuleHitUnit.MONTHS, 2))
        assertThat(RuleHits.age(now - 400 * day, now)).isEqualTo(RuleHitAge(RuleHitUnit.YEARS, 1))
    }

    @Test
    fun hitTextIsEmptyForNoHitsAndNamesCountAndAge() {
        val res = app.resources
        val none = SpamRule(1, ListKind.BLOCK, RuleType.KEYWORD, "win")
        assertThat(RuleHits.text(res, none, now)).isNull()
        // A last-hit time without a count says nothing either.
        assertThat(RuleHits.text(res, none.copy(lastHit = now - day), now)).isNull()

        val many = none.copy(hitCount = 12, lastHit = now - 3 * day)
        assertThat(RuleHits.text(res, many, now)).isEqualTo("Blocked 12 messages, last 3 days ago")
        assertThat(RuleHits.text(res, many.copy(hitCount = 1, lastHit = now - 3_600_000), now))
            .isEqualTo("Blocked 1 message, last 1 hour ago")
        // Hits counted before the time was stored: the count alone.
        assertThat(RuleHits.text(res, many.copy(lastHit = 0), now)).isEqualTo("Blocked 12 messages")
    }

    // endregion

    // region main-23

    private val old = SpamRule(1, ListKind.BLOCK, RuleType.KEYWORD, "a", createdAt = 500)
    private val restoredLater = SpamRule(2, ListKind.BLOCK, RuleType.KEYWORD, "b", createdAt = 100)
    private val newest = SpamRule(3, ListKind.BLOCK, RuleType.KEYWORD, "c", createdAt = 300)

    @Test
    fun defaultOrderIsByAddedTimeAndNewestOrderByLastSaved() {
        val rules = listOf(old, restoredLater, newest)
        assertThat(RulesOrder.DEFAULT.apply(rules).map { it.id }).containsExactly(1L, 3L, 2L).inOrder()
        assertThat(RulesOrder.NEWEST.apply(rules).map { it.id }).containsExactly(3L, 2L, 1L).inOrder()
    }

    @Test
    fun orderChoiceReordersTheListsAndIsRemembered() {
        val store = MemoryRulesOrderStore()
        start(listOf(old, restoredLater, newest), store)
        assertThat(vm.state.value.order).isEqualTo(RulesOrder.DEFAULT)
        assertThat(vm.state.value.blocked.map { it.id }).containsExactly(1L, 3L, 2L).inOrder()

        vm.setOrder(RulesOrder.NEWEST)
        awaitUntil {
            vm.state.value.order == RulesOrder.NEWEST && vm.state.value.blocked.map { it.id } == listOf(3L, 2L, 1L)
        }
        assertThat(vm.state.value.blocked.map { it.id }).containsExactly(3L, 2L, 1L).inOrder()
        assertThat(store.load()).isEqualTo(RulesOrder.NEWEST)

        // A new screen starts with the remembered order.
        start(listOf(old, restoredLater, newest), store)
        awaitUntil { vm.state.value.blocked.map { it.id } == listOf(3L, 2L, 1L) }
        assertThat(vm.state.value.order).isEqualTo(RulesOrder.NEWEST)
        assertThat(vm.state.value.blocked.map { it.id }).containsExactly(3L, 2L, 1L).inOrder()
    }

    // endregion

    // region spam-15

    @Test
    fun onlyBlockedWordsAndPatternsBlockTheSender() {
        assertThat(RuleValidator.blocksSender(ListKind.BLOCK, RuleType.KEYWORD)).isTrue()
        assertThat(RuleValidator.blocksSender(ListKind.BLOCK, RuleType.REGEX)).isTrue()
        assertThat(RuleValidator.blocksSender(ListKind.ALLOW, RuleType.KEYWORD)).isFalse()
        assertThat(RuleValidator.blocksSender(ListKind.BLOCK, RuleType.NUMBER)).isFalse()
        assertThat(RuleValidator.blocksSender(ListKind.BLOCK, RuleType.DOMAIN)).isFalse()
    }

    @Test
    fun theAutomaticKeywordNoteStillMapsToItsCodeAndNowSaysTheSenderIsBlocked() {
        assertThat(RuleNotes.codeOf("Blocked from a keyword")).isEqualTo(RuleNotes.BLOCKED_FROM_KEYWORD)
        val text = RuleNotes.display(app.resources, RuleNotes.BLOCKED_FROM_KEYWORD)
        assertThat(text).contains("whole sender")
    }

    // endregion
}
