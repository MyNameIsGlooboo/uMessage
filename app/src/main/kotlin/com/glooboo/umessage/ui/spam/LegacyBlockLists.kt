package com.glooboo.umessage.ui.spam

import android.content.Context
import androidx.compose.runtime.Immutable
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule

/**
 * The two block lists uMessage inherited from Fossify (SU-33), as the Rules screen shows them:
 * - [keywords]: the old blocked-words list. The filter still reads it until its words are moved into uMessage
 *   keyword rules; after that the list is empty and retired.
 * - [numbers]: the phone's system block list (BlockedNumberContract). Android discards texts from these numbers before
 *   uMessage sees them, and the list also blocks calls, so it is never changed without the user's explicit OK.
 */
@Immutable
data class LegacyLists(
    val keywords: List<String> = emptyList(),
    val numbers: List<BlockedNumberItem> = emptyList(),
    /** The user answered "Not now" (or already moved the lists): the banner is not shown again. */
    val offerDismissed: Boolean = false,
) {
    val isEmpty: Boolean get() = keywords.isEmpty() && numbers.isEmpty()
}

/** Reads and changes the old lists. Blocking; call off the main thread. */
interface LegacyBlockStore {
    fun load(): LegacyLists
    fun removeKeywords(keywords: Collection<String>)

    /** Removes one number from the system block list. Only called after the user ticked "Remove from system list". */
    fun removeSystemNumber(id: Long): Boolean
    fun setOfferDismissed(dismissed: Boolean)
}

object NoLegacyBlockStore : LegacyBlockStore {
    override fun load() = LegacyLists(offerDismissed = true)
    override fun removeKeywords(keywords: Collection<String>) = Unit
    override fun removeSystemNumber(id: Long) = false
    override fun setOfferDismissed(dismissed: Boolean) = Unit
}

class AndroidLegacyBlockStore(context: Context) : LegacyBlockStore {
    private val app = context.applicationContext

    override fun load(): LegacyLists {
        val numbers = if (SystemBlockList.isAvailable(app)) SystemBlockList.load(app) else emptyList()
        return LegacyLists(
            keywords = app.config.blockedKeywords.sortedBy { it.lowercase() },
            numbers = numbers,
            offerDismissed = app.config.legacyBlockOfferDismissed,
        )
    }

    override fun removeKeywords(keywords: Collection<String>) {
        if (keywords.isEmpty()) return
        val gone = keywords.toSet()
        app.config.blockedKeywords = app.config.blockedKeywords.filterTo(HashSet()) { it !in gone }
    }

    override fun removeSystemNumber(id: Long): Boolean = SystemBlockList.remove(app, id)

    override fun setOfferDismissed(dismissed: Boolean) {
        app.config.legacyBlockOfferDismissed = dismissed
    }
}

/** What moving the old lists into uMessage rules would do. Each rule is paired with the entry it comes from. */
@Immutable
data class LegacyMigrationPlan(
    val keywordRules: List<Pair<String, SpamRule>> = emptyList(),
    /** Words that cannot be a uMessage rule (one letter, several lines, too long). They stay on the old list. */
    val keptKeywords: List<String> = emptyList(),
    val numberRules: List<Pair<BlockedNumberItem, SpamRule>> = emptyList(),
    /** System entries that are already uMessage block rules: nothing to add, but they can still be removed. */
    val coveredNumbers: List<BlockedNumberItem> = emptyList(),
    /** System entries no uMessage rule can express (a wildcard in the middle). They stay on the system list. */
    val keptNumbers: List<BlockedNumberItem> = emptyList(),
) {
    val hasKeywordWork: Boolean get() = keywordRules.isNotEmpty()
    val hasNumberWork: Boolean get() = numberRules.isNotEmpty() || coveredNumbers.isNotEmpty()
    val isEmpty: Boolean get() = !hasKeywordWork && !hasNumberWork

    /** System entries that a uMessage rule will cover once the plan has run. */
    val movableNumbers: List<BlockedNumberItem> get() = numberRules.map { it.first } + coveredNumbers
}

/** Pure planning of the one-time move of the old lists. No Android dependencies. */
object LegacyMigration {
    private val formatting = Regex("[\\s\\-().]")

    fun plan(
        lists: LegacyLists,
        existing: List<SpamRule>,
        keywordNote: String = "",
        numberNote: String = "",
    ): LegacyMigrationPlan {
        val keywordRules = mutableListOf<Pair<String, SpamRule>>()
        val keptKeywords = mutableListOf<String>()
        for (word in lists.keywords) {
            when (val result = RuleValidator.validate(ListKind.BLOCK, RuleType.KEYWORD, word)) {
                is RuleValidation.Valid ->
                    keywordRules += word to SpamRule(kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = result.pattern, note = keywordNote)
                is RuleValidation.Invalid -> keptKeywords += word
            }
        }

        val numberRules = mutableListOf<Pair<BlockedNumberItem, SpamRule>>()
        val covered = mutableListOf<BlockedNumberItem>()
        val keptNumbers = mutableListOf<BlockedNumberItem>()
        for (item in lists.numbers) {
            val rule = numberRule(item.number, numberNote)
            when {
                rule == null -> keptNumbers += item
                isCovered(rule, existing) -> covered += item
                else -> numberRules += item to rule
            }
        }
        return LegacyMigrationPlan(keywordRules, keptKeywords, numberRules, covered, keptNumbers)
    }

    /**
     * How many of [numbers] a move would add as new uMessage rules: numbers already matched by one of [existing], and
     * patterns no rule can express, are not counted.
     */
    fun movableCount(numbers: List<BlockedNumberItem>, existing: List<SpamRule>): Int =
        plan(LegacyLists(numbers = numbers), existing).numberRules.size

    /** "+1555*" (wildcard only at the end) becomes a prefix rule; a plain number a number rule; anything else null. */
    fun numberRule(number: String, note: String = ""): SpamRule? {
        val cleaned = BlockedNumberFormat.normalizeInput(number).replace(formatting, "")
        val type: RuleType
        val value: String
        if (BlockedNumberFormat.isPattern(cleaned)) {
            val head = cleaned.trimEnd('*')
            if ('*' in head || head.isEmpty()) return null
            type = RuleType.PREFIX
            value = head
        } else {
            type = RuleType.NUMBER
            value = cleaned
        }
        val valid = RuleValidator.validate(ListKind.BLOCK, type, value) as? RuleValidation.Valid ?: return null
        return SpamRule(kind = ListKind.BLOCK, type = type, pattern = valid.pattern, note = note)
    }

    private fun isCovered(rule: SpamRule, existing: List<SpamRule>): Boolean = existing.any { other ->
        other.kind == ListKind.BLOCK && other.subscriptionId == null && other.type == rule.type &&
            if (rule.type == RuleType.NUMBER) sameNumber(other.pattern, rule.pattern) else other.pattern == rule.pattern
    }

    /** [RuleValidator.sameNumber], plus equal digits for numbers too short for its tail match ("5559990", "30002"). */
    private fun sameNumber(a: String, b: String): Boolean {
        val digitsA = a.filter { it.isDigit() }
        return RuleValidator.sameNumber(a, b) || digitsA.isNotEmpty() && digitsA == b.filter { it.isDigit() }
    }
}
