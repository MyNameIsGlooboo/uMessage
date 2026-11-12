package com.glooboo.umessage.ui.spam

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import com.glooboo.umessage.spam.KeywordMatch
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RegexGuard
import com.glooboo.umessage.spam.RegexProblem
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.TextNormalizer
import com.glooboo.umessage.spam.engine.DomainMatch

enum class RuleError {
    EMPTY, NUMBER, PREFIX, SENDER_ID, KEYWORD_SHORT, TOO_LONG, MULTILINE, REGEX_INVALID, REGEX_MATCHES_EMPTY, DUPLICATE,

    /** A repeat inside a repeat, e.g. `(a+)+`: can freeze the SMS receiver, so the filter would never use it. */
    REGEX_UNSAFE,

    /** Matches every ordinary message (e.g. `.`), so it would block or allow everything. */
    REGEX_MATCHES_ALL,

    /** An allowed keyword skips every block, so very short ones are refused. */
    ALLOW_KEYWORD_SHORT,

    /** Not a website name like `example.com`. */
    DOMAIN,
}

/** Things worth a second look before saving. Each one asks the user to confirm. */
enum class RuleWarning {
    /** An ALLOW keyword or regex: any message containing it skips every block, from any sender. */
    ALLOW_SKIPS_BLOCKS,

    /** An ALLOW keyword that is a common or short word, which spam can easily contain. */
    COMMON_WORD,

    /** Matches a lot of ordinary text (short keyword, wide regex). */
    BROAD,

    /** A BLOCK rule for something already on the Allowed list: allowed rules win, so it would never apply. */
    ALLOWED_CONFLICT,
}

sealed interface RuleValidation {
    /** [pattern] is the cleaned value that should be stored. */
    data class Valid(val pattern: String) : RuleValidation
    data class Invalid(val error: RuleError, val detail: String? = null) : RuleValidation
}

/** The regex engine's own words for why [pattern] does not compile, or null. */
private fun syntaxDetail(pattern: String): String? = try {
    Pattern.compile(pattern, RegexGuard.FLAGS)
    null
} catch (e: PatternSyntaxException) {
    e.description
} catch (_: Throwable) {
    null
}

/** The quotes of a whole-word keyword ([KeywordMatch]) are not part of its length. */
private fun validateKeyword(kind: ListKind, trimmed: String): RuleValidation {
    val word = KeywordMatch.bare(trimmed)
    return when {
        word.length < 2 -> RuleValidation.Invalid(RuleError.KEYWORD_SHORT)
        kind == ListKind.ALLOW && word.length < RuleValidator.MIN_ALLOW_KEYWORD ->
            RuleValidation.Invalid(RuleError.ALLOW_KEYWORD_SHORT)
        else -> RuleValidation.Valid(trimmed)
    }
}

/** Pure validation for the add-rule form and for imports. No Android dependencies. */
@Suppress("TooManyFunctions") // One small check per rule type; splitting them would scatter one decision.
object RuleValidator {
    const val MAX_PATTERN = 300

    /** Shortest keyword accepted on the Allowed list. */
    const val MIN_ALLOW_KEYWORD = 4

    /** Block keywords this short match inside many words ("win" in "window"). */
    private const val SHORT_BLOCK_KEYWORD = 3

    /** Single words this short count as common when allowed. */
    private const val SHORT_ALLOW_WORD = 5

    private val phoneChars = Regex("[\\s\\-().]")
    private val digitsOnly = Regex("\\+?\\d+")

    /** Longest sender name accepted. Carriers send 11 characters at most; some apps send names with spaces. */
    const val MAX_SENDER_ID = 32

    /** Characters allowed in a sender name besides letters (any script) and digits. */
    private const val SENDER_ID_PUNCTUATION = " ._&-"

    /** Words found in lots of ordinary and spam messages. Allowing one lets most spam through. */
    private val commonWords = setOf(
        "bank", "code", "otp", "pin", "pass", "password", "login", "verify", "verification", "account", "card",
        "pay", "payment", "order", "delivery", "parcel", "package", "sms", "message", "info", "offer", "free", "gift",
        "hello", "hi", "thanks", "your", "you",
        "کد", "رمز", "بانک", "پیامک", "پیام", "سلام", "پرداخت", "کارت", "حساب", "تایید", "ارسال", "سفارش", "مرسوله",
    )

    /** Ordinary messages a rule should not match all of. */
    private val ordinaryMessages = listOf(
        "Hi, dinner at 8?",
        "ok",
        "Your code is 482913",
        "سلام، خوبی؟",
        "Call me when you land",
        "👍",
    )

    fun validate(kind: ListKind, type: RuleType, raw: String): RuleValidation {
        if (raw.contains('\n') || raw.contains('\r')) return RuleValidation.Invalid(RuleError.MULTILINE)
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return RuleValidation.Invalid(RuleError.EMPTY)
        if (trimmed.length > MAX_PATTERN) return RuleValidation.Invalid(RuleError.TOO_LONG)
        return when (type) {
            RuleType.NUMBER -> {
                // Persian and Arabic digits are read as ASCII digits, so "۰۵۵۵..." works like "0555...".
                val cleaned = TextNormalizer.fold(trimmed).replace(phoneChars, "")
                if (digitsOnly.matches(cleaned) && cleaned.count { it.isDigit() } in 3..15) {
                    RuleValidation.Valid(cleaned)
                } else {
                    RuleValidation.Invalid(RuleError.NUMBER)
                }
            }
            RuleType.PREFIX -> {
                val cleaned = TextNormalizer.fold(trimmed).replace(phoneChars, "")
                if (digitsOnly.matches(cleaned) && cleaned.count { it.isDigit() } in 1..15) {
                    RuleValidation.Valid(cleaned)
                } else {
                    RuleValidation.Invalid(RuleError.PREFIX)
                }
            }
            RuleType.SENDER_ID -> validateSenderId(trimmed)
            RuleType.KEYWORD -> validateKeyword(kind, trimmed)
            // Regex is not trimmed on purpose beyond the outer whitespace check above: inner spaces are meaningful.
            RuleType.REGEX -> validateRegex(trimmed)
            RuleType.DOMAIN -> validateDomain(trimmed)
        }
    }

    private const val MAX_DOMAIN = 253
    private const val MAX_DOMAIN_LABEL = 63
    private val domainName =
        Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*\\.[a-z]{2,24}")

    /**
     * A website: cleaned exactly as the filter cleans a DOMAIN rule ([DomainMatch.clean]: scheme, path, port, a leading
     * "*." or "." and a trailing dot go), then it must be dotted ASCII names ending in letters. What is stored is the
     * cleaned name, so "https://Example.com/shop" becomes "example.com".
     */
    private fun validateDomain(value: String): RuleValidation {
        val cleaned = DomainMatch.clean(value)
        val ok = cleaned.length <= MAX_DOMAIN && domainName.matches(cleaned) &&
            cleaned.split('.').all { it.length <= MAX_DOMAIN_LABEL }
        return if (ok) RuleValidation.Valid(cleaned) else RuleValidation.Invalid(RuleError.DOMAIN)
    }

    /**
     * spam-15: a block rule on words in the message (a keyword or a pattern) also blocks the message's sender, so the
     * form says so. Allow rules and the other types do not do that.
     */
    fun blocksSender(kind: ListKind, type: RuleType): Boolean =
        kind == ListKind.BLOCK && (type == RuleType.KEYWORD || type == RuleType.REGEX)

    /**
     * A sender name: letters of any script, digits, spaces, dots, dashes, underscores and "&", with at least one
     * letter. Names with spaces ("Bank Melli") and Persian names are real senders. The first character that does
     * not fit is returned as the error detail, so the form can name it.
     */
    private fun validateSenderId(value: String): RuleValidation {
        if (value.length > MAX_SENDER_ID) return RuleValidation.Invalid(RuleError.TOO_LONG)
        val bad = value.firstOrNull { !it.isLetterOrDigit() && it !in SENDER_ID_PUNCTUATION }
        if (bad != null) return RuleValidation.Invalid(RuleError.SENDER_ID, bad.toString())
        if (value.none { it.isLetter() }) return RuleValidation.Invalid(RuleError.SENDER_ID)
        return RuleValidation.Valid(value)
    }

    /** Same checks the repository and the filter use ([RegexGuard]), so a pattern the form accepts is also stored and used. */
    private fun validateRegex(pattern: String): RuleValidation {
        when (RegexGuard.validate(pattern)) {
            null -> Unit
            RegexProblem.EMPTY -> return RuleValidation.Invalid(RuleError.EMPTY)
            RegexProblem.TOO_LONG -> return RuleValidation.Invalid(RuleError.TOO_LONG)
            RegexProblem.SYNTAX -> return RuleValidation.Invalid(RuleError.REGEX_INVALID, syntaxDetail(pattern))
            RegexProblem.MATCHES_EMPTY -> return RuleValidation.Invalid(RuleError.REGEX_MATCHES_EMPTY)
            RegexProblem.NESTED_REPEAT -> return RuleValidation.Invalid(RuleError.REGEX_UNSAFE)
        }
        if (regexHits(pattern) == ordinaryMessages.size) return RuleValidation.Invalid(RuleError.REGEX_MATCHES_ALL)
        return RuleValidation.Valid(pattern)
    }

    /** How many [ordinaryMessages] the pattern matches; 0 when it cannot be compiled. */
    private fun regexHits(pattern: String): Int {
        val compiled = RegexGuard.compile(pattern) ?: return 0
        return ordinaryMessages.count { RegexGuard.search(compiled, it) == RegexGuard.Outcome.MATCH }
    }

    /**
     * Warnings for a rule that passed [validate]. [pattern] is the cleaned value; [existing] are the stored rules,
     * [subscriptionId] the SIM scope of the new rule.
     */
    fun warnings(kind: ListKind, type: RuleType, pattern: String, existing: List<SpamRule>, subscriptionId: Int?): List<RuleWarning> {
        val result = mutableListOf<RuleWarning>()
        val text = type == RuleType.KEYWORD || type == RuleType.REGEX || type == RuleType.DOMAIN
        if (kind == ListKind.ALLOW && text) result += RuleWarning.ALLOW_SKIPS_BLOCKS
        if (kind == ListKind.ALLOW && type == RuleType.KEYWORD && isCommonWord(KeywordMatch.bare(pattern))) {
            result += RuleWarning.COMMON_WORD
        }
        if (isBroad(kind, type, pattern)) result += RuleWarning.BROAD
        if (kind == ListKind.BLOCK && conflictingAllows(existing, type, pattern, subscriptionId).isNotEmpty()) {
            result += RuleWarning.ALLOWED_CONFLICT
        }
        return result
    }

    private fun isBroad(kind: ListKind, type: RuleType, pattern: String): Boolean = when (type) {
        // A whole-word keyword does not hit the middle of other words, so a short one is not "broad".
        RuleType.KEYWORD -> kind == ListKind.BLOCK && !KeywordMatch.isWholeWord(pattern) &&
            pattern.trim().length <= SHORT_BLOCK_KEYWORD
        RuleType.REGEX -> regexHits(pattern) * 2 >= ordinaryMessages.size
        else -> false
    }

    private fun isCommonWord(pattern: String): Boolean {
        val word = pattern.trim().lowercase()
        return word in commonWords || (word.none { it.isWhitespace() } && word.length <= SHORT_ALLOW_WORD)
    }

    /**
     * ALLOW rules that would always win over a new BLOCK rule of [type] / [pattern], so the block would never apply:
     * the same value, or (for numbers) an allowed prefix that covers it. Only allow rules that apply on every SIM the
     * block applies on count.
     */
    fun conflictingAllows(existing: List<SpamRule>, type: RuleType, pattern: String, subscriptionId: Int?): List<SpamRule> =
        existing.filter { allow ->
            allow.kind == ListKind.ALLOW &&
                (allow.subscriptionId == null || allow.subscriptionId == subscriptionId) &&
                covers(allow, type, pattern)
        }

    private fun covers(allow: SpamRule, type: RuleType, pattern: String): Boolean = when {
        allow.type == type && allow.pattern.equals(pattern, ignoreCase = true) -> true
        type == RuleType.NUMBER && allow.type == RuleType.NUMBER -> sameNumber(allow.pattern, pattern)
        (type == RuleType.NUMBER || type == RuleType.PREFIX) && allow.type == RuleType.PREFIX ->
            pattern.replace(phoneChars, "").startsWith(allow.pattern.replace(phoneChars, ""))
        else -> false
    }

    /** National and international forms of one number share their last digits ("0555..." and "+1555..."). */
    fun sameNumber(a: String, b: String): Boolean {
        val da = a.filter { it.isDigit() }
        val db = b.filter { it.isDigit() }
        return da.length >= NUMBER_TAIL && db.length >= NUMBER_TAIL && da.takeLast(NUMBER_TAIL) == db.takeLast(NUMBER_TAIL)
    }

    private const val NUMBER_TAIL = 9

    fun isDuplicate(existing: List<SpamRule>, kind: ListKind, type: RuleType, pattern: String, subscriptionId: Int?): Boolean =
        existing.any {
            it.kind == kind && it.type == type && it.subscriptionId == subscriptionId &&
                it.pattern.equals(pattern, ignoreCase = true)
        }
}

/** Result of trying a keyword or pattern on a sample message, before the rule is saved. */
enum class RuleTestResult { MATCH, NO_MATCH, UNUSABLE }

/** Pure "test it first" for keyword and pattern rules: the same matching the filter uses, on a typed sample. */
object RuleTest {
    /** Whether [type] can be tried on message text at all. */
    fun supports(type: RuleType): Boolean = type == RuleType.KEYWORD || type == RuleType.REGEX

    /**
     * [pattern] is the cleaned rule value. A block keyword also sees through spaced-out letters, an allow keyword does
     * not, exactly as in the rule matcher.
     */
    fun run(kind: ListKind, type: RuleType, pattern: String, sample: String): RuleTestResult {
        if (!supports(type) || pattern.isBlank()) return RuleTestResult.UNUSABLE
        return when (type) {
            RuleType.KEYWORD -> {
                val hit = KeywordMatch.matches(sample, pattern, obfuscated = kind == ListKind.BLOCK)
                if (hit) RuleTestResult.MATCH else RuleTestResult.NO_MATCH
            }
            RuleType.REGEX -> {
                val compiled = RegexGuard.compile(pattern) ?: return RuleTestResult.UNUSABLE
                when (RegexGuard.search(compiled, TextNormalizer.fold(sample))) {
                    RegexGuard.Outcome.MATCH -> RuleTestResult.MATCH
                    RegexGuard.Outcome.NO_MATCH -> RuleTestResult.NO_MATCH
                    RegexGuard.Outcome.TIMED_OUT -> RuleTestResult.UNUSABLE
                }
            }
            else -> RuleTestResult.UNUSABLE
        }
    }
}

/**
 * Search of the rule list (SU-09): a rule matches when its value or note contains the query. Both are read through
 * [TextNormalizer] (Persian and Arabic letter forms, digits, invisible marks) and case is ignored. For numbers the
 * query's spaces and dashes are ignored, so "0555 123" finds "+1555123...".
 */
object RuleSearch {
    private val phoneChars = Regex("[\\s\\-().]")

    /** A note the app wrote ([RuleNotes]) is a code, not words the user would search for, so only typed notes count. */
    fun matches(rule: SpamRule, query: String, type: RuleType? = null): Boolean {
        val values = if (RuleNotes.isAutomatic(rule.note)) listOf(rule.pattern) else listOf(rule.pattern, rule.note)
        return (type == null || rule.type == type) && matchesText(values, query, numeric = rule.type.isNumeric)
    }

    /** Same test for a value of an old block list (no note). */
    fun matchesText(values: List<String>, query: String, numeric: Boolean): Boolean {
        val wanted = TextNormalizer.lower(query.trim())
        if (wanted.isEmpty()) return true
        if (values.any { TextNormalizer.lower(it).contains(wanted) }) return true
        if (!numeric) return false
        val digits = wanted.replace(phoneChars, "").trimStart('+').trimStart('0')
        if (digits.isEmpty() || digits.any { !it.isDigit() }) return false
        return values.any { value -> TextNormalizer.lower(value).replace(phoneChars, "").contains(digits) }
    }

    private val RuleType.isNumeric: Boolean get() = this == RuleType.NUMBER || this == RuleType.PREFIX
}

/**
 * Text format of the rules file. Current version, written by [encode]: a header line, then one rule per line with
 * tab-separated fields `kind, type, sim, note, pattern` (sim is empty for both SIMs; the pattern is last so it may
 * contain anything but a line break). Files from older versions, one `kind,type,pattern` per line, are still read.
 * Lines starting with `#` are comments.
 */
object RuleTextCodec {
    const val HEADER = "# uMessage rules v2"
    private const val FIELDS = "# kind\ttype\tsim\tnote\tpattern"

    data class Parsed(val rules: List<SpamRule>, val invalidLines: Int)

    fun encode(rules: List<SpamRule>): String = buildString {
        append(HEADER).append('\n')
        append(FIELDS).append('\n')
        rules.forEach {
            append(it.kind.name).append('\t')
            append(it.type.name).append('\t')
            append(it.subscriptionId?.toString().orEmpty()).append('\t')
            append(it.note.replace(Regex("[\\t\\r\\n]+"), " ").trim()).append('\t')
            append(it.pattern).append('\n')
        }
    }

    fun decode(text: String): Parsed {
        val rules = mutableListOf<SpamRule>()
        var invalid = 0
        text.lineSequence().map { it.trimEnd('\r') }.forEach { line ->
            if (line.isBlank() || line.trimStart().startsWith("#")) return@forEach
            val rule = if ('\t' in line) decodeV2(line) else decodeV1(line)
            if (rule == null) invalid++ else rules += rule
        }
        return Parsed(rules, invalid)
    }

    private fun decodeV2(line: String): SpamRule? {
        val parts = line.split('\t', limit = 5)
        if (parts.size != 5) return null
        val kind = kindOf(parts[0]) ?: return null
        val type = typeOf(parts[1]) ?: return null
        val sim = parts[2].trim().let { if (it.isEmpty()) null else it.toIntOrNull() ?: return null }
        return build(kind, type, parts[4], sim, parts[3].trim())
    }

    private fun decodeV1(line: String): SpamRule? {
        val parts = line.split(',', limit = 3)
        if (parts.size != 3) return null
        val kind = kindOf(parts[0]) ?: return null
        val type = typeOf(parts[1]) ?: return null
        return build(kind, type, parts[2], null, "")
    }

    private fun build(kind: ListKind, type: RuleType, pattern: String, sim: Int?, note: String): SpamRule? =
        when (val result = RuleValidator.validate(kind, type, pattern)) {
            is RuleValidation.Valid -> SpamRule(kind = kind, type = type, pattern = result.pattern, subscriptionId = sim, note = note.take(80))
            is RuleValidation.Invalid -> null
        }

    private fun kindOf(s: String) = s.trim().uppercase().let { v -> ListKind.entries.firstOrNull { it.name == v } }
    private fun typeOf(s: String) = s.trim().uppercase().let { v -> RuleType.entries.firstOrNull { it.name == v } }
}

/** Values of the concrete starter rules on the empty Rules tabs (ID-53). */
object RuleExamples {
    /** "لغو۱۱": Persian promotional texts end with this unsubscribe word, so it marks advertising well. */
    const val CANCEL_WORD = "لغو۱۱"

    /** Bulk advertising comes from short codes starting with 3000. */
    const val AD_PREFIX = "3000"
}
