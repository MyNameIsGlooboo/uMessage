package com.glooboo.umessage.helpers

import com.glooboo.umessage.spam.TextNormalizer
import com.glooboo.umessage.spam.engine.OtpDetector

/** What a copy chip or a notification copies: a code, a password or a user name. */
enum class CodeKind { Code, Password, Username }

/** One value to copy. [value] has Latin digits whatever script the message was written in. */
data class CodeToken(val kind: CodeKind, val value: String)

/**
 * The one rule for "this message holds a verification code", and for which value it is. The spam filter, forwarding,
 * the notification ("Copy code", tap to copy, masking on the lock screen), the list chip, the chat chips, the Codes
 * filter, the weekly digest, the categories and the Spam tab all ask it, so they cannot disagree. Pure JVM.
 *
 * A message holds a code when either
 * - a 4-8 digit number sits next to an OTP keyword ([OtpDetector.nearKeyword]; the shape of the number, the keyword
 *   lists and the vetoes for advertisements, prices, years and phone numbers are the detector's, and
 *   [OtpCodeExtractor.locate] picks the number), or
 * - a labelled value says what it is ("password: x7Kq2", "code: 482913"). A user name is a value to copy, but no code.
 *
 * Everything is read through [TextNormalizer.fold], so every value comes back with Latin digits (Persian "۴۸۲۹۱۳"
 * is copied and masked as "482913", never as the Persian digits), also in a chat chip.
 */
object OtpCodes {
    private const val LABELS = "code|otp|passcode|pin|password|passwd|pass|username|user name|user|login|" +
        "کد|رمز عبور|رمز|گذرواژه|پسورد|نام کاربری"

    /**
     * "label: value", the label being one of [LABELS]; the colon or equals sign keeps ordinary prose out. The label
     * must start a word, so "Barcode: 123456" is no code and "Compass: N45" no password.
     */
    private val labelled =
        Regex("(?<![\\p{L}\\p{N}])($LABELS)\\s*[:：=]\\s*([^\\s:：]{3,40})", RegexOption.IGNORE_CASE)

    private val trailing = charArrayOf('.', ',', ';', '،', '؛', ')', '"').toSet()

    private const val MIN_BODY_LENGTH = 4
    private const val MIN_VALUE_LENGTH = 3

    /** Whether [body] holds a verification code (see the class comment). */
    fun looksLikeCode(body: String): Boolean {
        val text = TextNormalizer.fold(body)
        return OtpDetector.nearKeyword(text) || labelledValues(text).any { it.second.kind != CodeKind.Username }
    }

    /**
     * Every value worth a copy chip, in the order of the text and without repeats, with no maximum: each labelled value
     * and each digit code that has a keyword next to it ("Use 333333 to log in, or 444444 to reset" gives both).
     */
    fun tokens(body: String): List<CodeToken> {
        if (body.length < MIN_BODY_LENGTH) return emptyList()
        val text = TextNormalizer.fold(body)
        val found = LinkedHashMap<String, Pair<Int, CodeToken>>()
        for ((position, token) in labelledValues(text)) found.putIfAbsent(token.value, position to token)
        for ((position, value) in keywordCodes(text)) {
            val known = found[value]
            if (known == null) {
                found[value] = position to CodeToken(CodeKind.Code, value)
            } else if (position < known.first) {
                found[value] = position to known.second
            }
        }
        return found.values.sortedBy { it.first }.map { it.second }
    }

    /** The value a notification or the list offers: the first code, else the first password; null without one. */
    fun primary(body: String): CodeToken? {
        val all = tokens(body)
        return all.firstOrNull { it.kind == CodeKind.Code } ?: all.firstOrNull { it.kind == CodeKind.Password }
    }

    /** The code next to a keyword in [text] (already folded); group 1 of the match is the number. */
    internal fun locate(text: String): MatchResult? =
        if (OtpDetector.nearKeyword(text)) OtpCodeExtractor.locate(text) else null

    /** Where [value] stands in [text] (already folded), every repeat of it: what a mask has to cover. */
    internal fun spans(text: String, value: String): List<IntRange> =
        if (value.all { it.isDigit() }) {
            OtpCodeExtractor.codeShapes(text)
                .filter { OtpCodeExtractor.digitsOf(it.groupValues[1]) == value }
                .map { it.groups[1]!!.range }
                .toList()
        } else {
            Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(value) + "(?![\\p{L}\\p{N}])")
                .findAll(text).map { it.range }.toList()
        }

    /** Digit codes with a keyword next to them: position and value; the closest to a keyword when none is adjacent. */
    private fun keywordCodes(text: String): List<Pair<Int, String>> {
        if (!OtpDetector.nearKeyword(text)) return emptyList()
        val adjacent = OtpCodeExtractor.keywordBound(text)
            .filter { OtpDetector.keywordNextTo(text, it.groups[1]!!.range) }
            .map { it.groups[1]!!.range.first to OtpCodeExtractor.digitsOf(it.groupValues[1]) }
            .toList()
        if (adjacent.isNotEmpty()) return adjacent
        return listOfNotNull(locate(text))
            .map { it.groups[1]!!.range.first to OtpCodeExtractor.digitsOf(it.groupValues[1]) }
    }

    private fun labelledValues(text: String): List<Pair<Int, CodeToken>> {
        val matches = labelled.findAll(text).toList()
        if (matches.isEmpty()) return emptyList()
        val shapes = OtpCodeExtractor.codeShapes(text).associateBy { it.groups[1]!!.range.first }
        val advertisement = OtpDetector.isMarketing(text)
        return matches.mapNotNull { labelledValue(text, it, shapes, advertisement) }
    }

    /**
     * One labelled value. A value made of digits must have the shape of a code (4-8 digits, or two groups of three or
     * four), as everywhere else; "code: 1500 ریال" is no code. A link is no value ("Login: https://...") and a
     * code in an advertisement ("Promo code: SAVE20") is none either.
     */
    private fun labelledValue(
        text: String,
        match: MatchResult,
        shapes: Map<Int, MatchResult>,
        advertisement: Boolean,
    ): Pair<Int, CodeToken>? {
        val group = match.groups[2] ?: return null
        val kind = kindOf(match.groupValues[1])
        if (kind == CodeKind.Code && advertisement) return null
        if (text.startsWith("://", group.range.last + 1)) return null
        val shape = shapes[group.range.first]
        val value = shape?.let { OtpCodeExtractor.digitsOf(it.groupValues[1]) }
            ?: group.value.trimEnd { it in trailing }
        val valid = value.length >= MIN_VALUE_LENGTH && (shape != null || !value.all { it.isDigit() })
        return if (valid) group.range.first to CodeToken(kind, value) else null
    }

    private fun kindOf(label: String): CodeKind {
        val l = label.lowercase()
        val password = l.startsWith("pass") && l != "passcode" || l == "پسورد" || l == "گذرواژه" ||
            l.startsWith("رمز عبور")
        return when {
            password -> CodeKind.Password
            l.startsWith("user") || l == "login" || l == "نام کاربری" -> CodeKind.Username
            else -> CodeKind.Code
        }
    }
}
