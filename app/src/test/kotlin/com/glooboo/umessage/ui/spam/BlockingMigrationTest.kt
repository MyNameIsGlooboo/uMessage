package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.UnknownSenderMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class BlockingMigrationTest {

    private class Store(words: Set<String> = emptySet(), var switchOn: Boolean = false) : BlockingMigrationStore {
        var words: Set<String> = words
        override fun keywords() = words
        override fun removeKeywords(words: Collection<String>) {
            this.words = this.words - words.toSet()
        }

        override fun blockUnknownSwitch() = switchOn
        override fun clearBlockUnknownSwitch() {
            switchOn = false
        }
    }

    /** A rule store and a mode in memory. [failAdds] makes every add throw; [failMode] makes the mode update throw. */
    private class Env(
        var mode: UnknownSenderMode = UnknownSenderMode.SMART,
        val rules: MutableList<SpamRule> = mutableListOf(),
        var failAdds: Boolean = false,
        var failMode: Boolean = false,
    ) {
        suspend fun run(store: Store) = BlockingMigration.run(
            store = store,
            existingRules = { rules.toList() },
            addRule = { rule ->
                if (failAdds) error("disk full")
                val duplicate = rules.any { it.kind == rule.kind && it.type == rule.type && it.pattern == rule.pattern }
                if (!duplicate) rules += rule
            },
            updateMode = { change ->
                if (failMode) error("prefs")
                mode = change(mode)
            },
        )
    }

    @Test
    fun theSwitchMapsOnlyOffToStrict() {
        assertThat(BlockingMigration.modeAfterSwitch(UnknownSenderMode.OFF, true)).isEqualTo(UnknownSenderMode.STRICT)
        assertThat(BlockingMigration.modeAfterSwitch(UnknownSenderMode.OFF, false)).isEqualTo(UnknownSenderMode.OFF)
        assertThat(BlockingMigration.modeAfterSwitch(UnknownSenderMode.SMART, true)).isEqualTo(UnknownSenderMode.SMART)
        assertThat(BlockingMigration.modeAfterSwitch(UnknownSenderMode.STRICT, true))
            .isEqualTo(UnknownSenderMode.STRICT)
        assertThat(BlockingMigration.modeAfterSwitch(UnknownSenderMode.STRICT, false))
            .isEqualTo(UnknownSenderMode.STRICT)
    }

    @Test
    fun offWithTheSwitchOnBecomesStrictAndTheSwitchIsCleared() = runBlocking {
        val env = Env(mode = UnknownSenderMode.OFF)
        val store = Store(switchOn = true)
        val result = env.run(store)
        assertThat(env.mode).isEqualTo(UnknownSenderMode.STRICT)
        assertThat(store.switchOn).isFalse()
        assertThat(result.modeChanged).isTrue()
    }

    @Test
    fun smartOrStrictKeepTheirModeAndTheSwitchIsJustCleared() = runBlocking {
        for (mode in listOf(UnknownSenderMode.SMART, UnknownSenderMode.STRICT)) {
            val env = Env(mode = mode)
            val store = Store(switchOn = true)
            val result = env.run(store)
            assertThat(env.mode).isEqualTo(mode)
            assertThat(store.switchOn).isFalse()
            assertThat(result.modeChanged).isFalse()
        }
    }

    @Test
    fun offWithTheSwitchOffStaysOff() = runBlocking {
        val env = Env(mode = UnknownSenderMode.OFF)
        env.run(Store(switchOn = false))
        assertThat(env.mode).isEqualTo(UnknownSenderMode.OFF)
    }

    @Test
    fun aFailedModeUpdateKeepsTheSwitchSoTheFilterStillHonoursIt() = runBlocking {
        val env = Env(mode = UnknownSenderMode.OFF, failMode = true)
        val store = Store(switchOn = true)
        val result = env.run(store)
        assertThat(store.switchOn).isTrue()
        assertThat(result.modeChanged).isFalse()
    }

    @Test
    fun everyBlockedWordBecomesAKeywordBlockRuleAndLeavesTheOldList() = runBlocking {
        val env = Env()
        val store = Store(words = setOf("prize", "free money"))
        val result = env.run(store)
        assertThat(env.rules.map { it.pattern }).containsExactly("prize", "free money")
        assertThat(env.rules.all { it.kind == ListKind.BLOCK && it.type == RuleType.KEYWORD }).isTrue()
        assertThat(env.rules.all { it.note == RuleNotes.OLD_WORD_LIST }).isTrue()
        assertThat(store.words).isEmpty()
        assertThat(result.wordsMoved).isEqualTo(2)
        assertThat(result.wordsKept).isEqualTo(0)
    }

    @Test
    fun aWordThatIsAlreadyARuleIsNotAddedTwice() = runBlocking {
        val existing = SpamRule(kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "prize")
        val env = Env(rules = mutableListOf(existing))
        val store = Store(words = setOf("prize"))
        env.run(store)
        assertThat(env.rules).hasSize(1)
        assertThat(store.words).isEmpty()
    }

    @Test
    fun aWordThatCannotBeARuleStaysOnTheOldList() = runBlocking {
        val env = Env()
        // One letter and several lines are refused as rules; they keep filtering from the old list.
        val store = Store(words = setOf("x", "two\nlines", "prize"))
        val result = env.run(store)
        assertThat(env.rules.map { it.pattern }).containsExactly("prize")
        assertThat(store.words).containsExactly("x", "two\nlines")
        assertThat(result.wordsMoved).isEqualTo(1)
        assertThat(result.wordsKept).isEqualTo(2)
    }

    @Test
    fun blankWordsAreDroppedAndNeverBecomeRules() = runBlocking {
        val env = Env()
        val store = Store(words = setOf("  ", "", "prize"))
        val result = env.run(store)
        assertThat(env.rules.map { it.pattern }).containsExactly("prize")
        assertThat(store.words).isEmpty()
        assertThat(result.wordsMoved).isEqualTo(1)
        assertThat(result.wordsKept).isEqualTo(0)
    }

    @Test
    fun aQuotedWordStaysOnTheOldListBecauseAsARuleItWouldMeanWholeWord() = runBlocking {
        val env = Env()
        val store = Store(words = setOf("\"win\"", "prize"))
        val result = env.run(store)
        assertThat(env.rules.map { it.pattern }).containsExactly("prize")
        assertThat(store.words).containsExactly("\"win\"")
        assertThat(result.wordsKept).isEqualTo(1)
    }

    @Test
    fun aFailedAddLeavesTheWordWhereItWas() = runBlocking {
        val env = Env(failAdds = true)
        val store = Store(words = setOf("prize"))
        val result = env.run(store)
        assertThat(store.words).containsExactly("prize")
        assertThat(result.wordsMoved).isEqualTo(0)
        assertThat(result.wordsKept).isEqualTo(1)
    }

    @Test
    fun runningAgainChangesNothing() = runBlocking {
        val env = Env(mode = UnknownSenderMode.OFF)
        val store = Store(words = setOf("prize"), switchOn = true)
        env.run(store)
        val rulesAfterFirst = env.rules.toList()
        val second = env.run(store)
        assertThat(env.rules).isEqualTo(rulesAfterFirst)
        assertThat(env.mode).isEqualTo(UnknownSenderMode.STRICT)
        assertThat(second).isEqualTo(BlockingMigration.Result())
    }

    @Test
    fun anOlderBackupsWordsRestoredLaterAreMovedAgainWithoutDuplicates() = runBlocking {
        val env = Env()
        env.run(Store(words = setOf("prize")))
        // A restore puts the same word back on the old list; the next run drops it into the existing rule.
        val store = Store(words = setOf("prize", "winner"))
        env.run(store)
        assertThat(env.rules.map { it.pattern }).containsExactly("prize", "winner")
        assertThat(store.words).isEmpty()
    }
}
