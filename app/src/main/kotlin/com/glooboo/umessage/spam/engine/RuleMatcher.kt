package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.KeywordMatch
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RegexGuard
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.TextNormalizer
import java.util.regex.Pattern

/**
 * Whether one rule condition matches a message. Shared by the spam filter ([DefaultSpamFilter]) and the forward
 * rules ([ForwardPlanner]), so a number, prefix, sender id, keyword or pattern means the same thing in both.
 */
class RuleMatcher(
    private val normalizer: NumberNormalizer,
    /** Country of the SIM that received a message, used to read national-format senders. Null = default region. */
    private val regionForSubscription: (Int) -> String? = { null },
    /** The clock, in milliseconds; replaced in tests. */
    private val now: () -> Long = System::currentTimeMillis,
    /** The guarded regex search; replaced in tests. */
    private val search: (Pattern, String) -> RegexGuard.Outcome = RegexGuard::search,
) {

    /** Compiled REGEX rules, bounded so deleted or edited rules do not pile up. Null = rejected pattern. */
    private val regexCache = object : LinkedHashMap<String, Pattern?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pattern?>?) = size > REGEX_CACHE_SIZE
    }

    /** The receiving SIM's region first, then the default region, so rules match in either reading. */
    fun numberReaders(subscriptionId: Int): List<NumberNormalizer> {
        val region = runCatching { regionForSubscription(subscriptionId) }.getOrNull()?.trim()?.uppercase()
        if (region.isNullOrEmpty()) return listOf(normalizer)
        return listOf(normalizer.withRegion(region), normalizer)
    }

    fun matches(rule: SpamRule, message: IncomingMessage, numbers: List<NumberNormalizer> = numberReaders(message.subscriptionId)): Boolean {
        val pattern = rule.pattern
        if (pattern.isBlank()) return false
        return when (rule.type) {
            RuleType.NUMBER -> !normalizer.isSenderId(pattern) && numbers.any { it.sameAddress(pattern, message.address) }
            RuleType.SENDER_ID -> matchesSenderId(pattern, message.address)
            RuleType.PREFIX -> {
                val prefix = normalizer.cleanPrefix(pattern)
                prefix.isNotEmpty() && numbers.any { reader ->
                    reader.prefixCandidates(message.address).any { it.startsWith(prefix) }
                }
            }

            // Both read the text through TextNormalizer (Persian/Arabic letter forms, digits, invisible marks). A BLOCK
            // keyword also sees through spaced-out letters and leetspeak ("F R E E", "fr33"); an ALLOW keyword does not,
            // so a spammer cannot reach the inbox by spelling out an allowed word.
            // A keyword in double quotes only matches as a whole word ([KeywordMatch]).
            RuleType.KEYWORD -> KeywordMatch.matches(message.body, pattern, obfuscated = rule.kind == ListKind.BLOCK)

            // A link host in the body equals the domain or is a subdomain of it. An ALLOW rule ignores disguised dots
            // ("example[.]com"), so a spammer cannot dress a link up as an allowed domain.
            RuleType.DOMAIN -> DomainMatch.matches(message.body, pattern, disguised = rule.kind == ListKind.BLOCK)

            RuleType.REGEX -> regex(pattern)?.let { matchesRegex(pattern, it, TextNormalizer.fold(message.body)) } == true
        }
    }

    /** True when [a] and [b] are the same phone number in any reading of [subscriptionId]'s region. */
    fun sameNumber(a: String, b: String, subscriptionId: Int): Boolean =
        a.isNotBlank() && b.isNotBlank() && numberReaders(subscriptionId).any { it.sameAddress(a, b) }

    private fun matchesSenderId(pattern: String, address: String): Boolean {
        if (!normalizer.isSenderId(address)) return false
        val id = normalizer.normalize(address)
        val wanted = pattern.trim().uppercase()
        if (id == wanted) return true
        return !wanted.contains('-') && id.substringAfter('-', "") == wanted
    }

    /** Timeouts in a row per pattern, and until when a pattern that timed out too often is turned off. */
    private val timeouts = HashMap<String, Int>()
    private val disabledUntil = HashMap<String, Long>()

    /**
     * A pattern that runs out of time does not match that message. One pause (garbage collection, a slow phone) must
     * not kill a rule for good, so a pattern is turned off only after [MAX_TIMEOUTS] timeouts in a row, and only for
     * [DISABLED_MS]; any finished search starts the count again. Editing the rule gives a new pattern and a new try.
     */
    private fun matchesRegex(source: String, pattern: Pattern, body: String): Boolean {
        synchronized(timeouts) {
            val until = disabledUntil[source]
            if (until != null) {
                if (now() < until) return false
                disabledUntil.remove(source)
            }
        }
        val outcome = search(pattern, body)
        synchronized(timeouts) {
            if (outcome == RegexGuard.Outcome.TIMED_OUT) {
                val count = (timeouts[source] ?: 0) + 1
                if (count >= MAX_TIMEOUTS) {
                    timeouts.remove(source)
                    disabledUntil[source] = now() + DISABLED_MS
                } else {
                    timeouts[source] = count
                }
            } else {
                timeouts.remove(source)
            }
        }
        return outcome == RegexGuard.Outcome.MATCH
    }

    /** Patterns that fail [RegexGuard.validate] (bad syntax, nested repeats) are skipped rather than risk a hang. */
    private fun regex(pattern: String): Pattern? = synchronized(regexCache) {
        if (regexCache.containsKey(pattern)) return regexCache[pattern]
        // Folded like the body, so "ي" in a pattern matches "ی" in a message and "[۰-۹]" reads as "[0-9]".
        val compiled = RegexGuard.compile(TextNormalizer.fold(pattern))
        regexCache[pattern] = compiled
        compiled
    }

    private companion object {
        const val REGEX_CACHE_SIZE = 64
        const val MAX_TIMEOUTS = 3
        const val DISABLED_MS = 10 * 60_000L
    }
}
