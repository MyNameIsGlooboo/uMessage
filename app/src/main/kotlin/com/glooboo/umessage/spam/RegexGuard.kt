package com.glooboo.umessage.spam

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Why a REGEX rule pattern was rejected. */
enum class RegexProblem {
    EMPTY,
    TOO_LONG,

    /** Does not compile. */
    SYNTAX,

    /** Matches the empty string, so it would block every message. */
    MATCHES_EMPTY,

    /**
     * A repeated group that itself contains a repeat, e.g. `(a+)+` or `(\w+\s?)*`. Such patterns can take
     * exponential time on some messages and would freeze the SMS receiver.
     */
    NESTED_REPEAT,
}

/**
 * Safety checks for user REGEX rules. Pure JVM, usable from the UI (validation while typing) and from the
 * filter (rules that fail validation are skipped at match time, and bodies are length-capped).
 */
object RegexGuard {
    const val MAX_PATTERN_CHARS = 300

    /** Only this much of a message body is matched against REGEX rules. */
    const val MAX_BODY_CHARS = 2_000

    /** Case is ignored in every script (Cyrillic, Greek, ...), not only ASCII. */
    const val FLAGS = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE or Pattern.MULTILINE

    /** Null when [pattern] is safe to store and match. */
    fun validate(pattern: String): RegexProblem? = check(pattern).first

    /** The compiled pattern, or null when [validate] rejects it. */
    fun compile(pattern: String): Pattern? = check(pattern).second

    /**
     * Longest a single REGEX rule may spend on one message. Patterns such as `.*.*.*.*x` pass [validate] yet take
     * polynomial time, so a long body could otherwise hold up the SMS receiver for minutes.
     */
    const val MAX_MATCH_MILLIS = 50L

    /** Outcome of [search]. */
    enum class Outcome { MATCH, NO_MATCH, TIMED_OUT }

    /** Matches a length-capped [body]. Any failure inside the regex engine, a timeout included, counts as "no match". */
    fun find(pattern: Pattern, body: String): Boolean = search(pattern, body) == Outcome.MATCH

    /**
     * Matches a length-capped [body] within [timeoutMillis]. [Outcome.TIMED_OUT] means the pattern is too slow and
     * should not be tried again; any other failure inside the regex engine is [Outcome.NO_MATCH].
     */
    fun search(pattern: Pattern, body: String, timeoutMillis: Long = MAX_MATCH_MILLIS): Outcome = try {
        val capped = if (body.length <= MAX_BODY_CHARS) body else body.substring(0, MAX_BODY_CHARS)
        val input = DeadlineChars(capped, System.nanoTime() + timeoutMillis * 1_000_000L)
        if (pattern.matcher(input).find()) Outcome.MATCH else Outcome.NO_MATCH
    } catch (_: MatchTimeout) {
        Outcome.TIMED_OUT
    } catch (_: Throwable) {
        Outcome.NO_MATCH
    }

    private class MatchTimeout : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /**
     * The regex engine reads its input only through [charAt], also while backtracking, so checking the clock there
     * bounds the whole match. The clock is read every [CHECK_EVERY] reads to keep the overhead small.
     */
    private class DeadlineChars(
        private val text: String,
        private val deadline: Long,
        private val start: Int = 0,
        private val end: Int = text.length,
    ) : CharSequence {
        private var reads = 0

        override val length: Int get() = end - start

        override fun get(index: Int): Char {
            if (++reads >= CHECK_EVERY) {
                reads = 0
                if (System.nanoTime() - deadline > 0) throw MatchTimeout()
            }
            return text[start + index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            DeadlineChars(text, deadline, start + startIndex, start + endIndex)

        override fun toString(): String = text.substring(start, end)

        private companion object {
            const val CHECK_EVERY = 1_000
        }
    }

    private fun check(raw: String): Pair<RegexProblem?, Pattern?> {
        val pattern = raw.trim()
        if (pattern.isEmpty()) return RegexProblem.EMPTY to null
        if (pattern.length > MAX_PATTERN_CHARS) return RegexProblem.TOO_LONG to null
        val compiled = try {
            Pattern.compile(pattern, FLAGS)
        } catch (_: PatternSyntaxException) {
            return RegexProblem.SYNTAX to null
        } catch (_: Throwable) {
            return RegexProblem.SYNTAX to null
        }
        if (hasNestedRepeat(pattern)) return RegexProblem.NESTED_REPEAT to null
        val matchesEmpty = try {
            // Without MULTILINE: there `^` does not match an empty input, yet it matches every real message.
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher("").find()
        } catch (_: Throwable) {
            true
        }
        if (matchesEmpty) return RegexProblem.MATCHES_EMPTY to null
        return null to compiled
    }

    /** True when an unbounded repeat (`*`, `+`, `{n,}`) is applied to a group that already contains one. */
    internal fun hasNestedRepeat(p: String): Boolean {
        // One entry per open group (plus the top level): does it contain an unbounded repeat?
        val frames = ArrayList<Boolean>().apply { add(false) }
        var i = 0
        while (i < p.length) {
            val atomEnd = when (p[i]) {
                '\\' -> if (i + 1 < p.length && p[i + 1] == 'Q') {
                    val end = p.indexOf("\\E", i + 2)
                    if (end < 0) p.length else end + 2
                } else {
                    minOf(i + 2, p.length)
                }

                '[' -> classEnd(p, i)
                '(' -> {
                    frames.add(false)
                    i++
                    continue
                }

                ')' -> {
                    val inner = if (frames.size > 1) frames.removeAt(frames.lastIndex) else false
                    val unbounded = isUnboundedRepeatAt(p, i + 1)
                    if (inner && unbounded) return true
                    if (inner || unbounded) frames[frames.lastIndex] = true
                    i++
                    continue
                }

                else -> i + 1
            }
            if (isUnboundedRepeatAt(p, atomEnd)) frames[frames.lastIndex] = true
            i = atomEnd
        }
        return false
    }

    private fun isUnboundedRepeatAt(p: String, j: Int): Boolean {
        if (j >= p.length) return false
        return when (p[j]) {
            '*', '+' -> true
            '{' -> {
                val end = p.indexOf('}', j)
                end > j && p.substring(j + 1, end).let { it.endsWith(",") && it.dropLast(1).all(Char::isDigit) }
            }

            else -> false
        }
    }

    /** Index just after the character class starting at [start] (`[`), honouring escapes and nested classes. */
    private fun classEnd(p: String, start: Int): Int {
        var j = start + 1
        if (j < p.length && p[j] == '^') j++
        if (j < p.length && p[j] == ']') j++
        var depth = 1
        while (j < p.length) {
            when (p[j]) {
                '\\' -> j++
                '[' -> depth++
                ']' -> if (--depth == 0) return j + 1
            }
            j++
        }
        return p.length
    }
}
