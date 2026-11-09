package com.glooboo.umessage.ui.thread

/** What a copy chip under a received bubble copies (TH-06). */
enum class CopyKind { Code, Password, Username }

data class CopyToken(val kind: CopyKind, val value: String)

private const val LABELS =
    "code|otp|passcode|pin|password|passwd|pass|username|user name|user|login|کد|رمز عبور|رمز|گذرواژه|پسورد|نام کاربری"

/**
 * "label: value", the label being one of [LABELS]; the colon or equals sign keeps ordinary prose out. L1: the label
 * must start a word, so "Barcode: 123456" is no code and "Compass: N45" no password.
 */
private val labelled = Regex("(?<![\\p{L}\\p{N}])($LABELS)\\s*[:：=]\\s*([^\\s:：]{3,40})", RegexOption.IGNORE_CASE)

private val codeWords = Regex(
    "(?<![\\p{L}\\p{N}])(code|otp|passcode|pin\\b|verification|one-time|کد|رمز|تایید|تأیید)",
    RegexOption.IGNORE_CASE,
)
private val digits = Regex("(?<![\\p{Nd}\\w])\\p{Nd}{4,8}(?![\\p{Nd}\\w])")

private const val MIN_BODY_LENGTH = 4
private const val MIN_VALUE_LENGTH = 3

private fun kindOf(label: String): CopyKind {
    val l = label.lowercase()
    val password = l.startsWith("pass") && l != "passcode" || l == "پسورد" || l.startsWith("رمز عبور") || l == "گذرواژه"
    return when {
        password -> CopyKind.Password
        l.startsWith("user") || l == "login" || l == "نام کاربری" -> CopyKind.Username
        else -> CopyKind.Code
    }
}

/** L1: "Login: https://…" would otherwise copy "https" as the username. */
private fun startsAddress(body: String, valueEnd: Int): Boolean = body.startsWith("://", valueEnd)

/**
 * Credentials and one-time codes in a message body: labelled values ("password: x7Kq2") first, else a 4-8 digit
 * number when the text talks about a code. All of them, in order, without repeats.
 */
fun detectCopyTokens(body: String): List<CopyToken> {
    if (body.length < MIN_BODY_LENGTH) return emptyList()
    val found = LinkedHashMap<String, CopyToken>()
    labelled.findAll(body).forEach { match ->
        val valueGroup = match.groups[2] ?: return@forEach
        if (startsAddress(body, valueGroup.range.last + 1)) return@forEach
        val value = valueGroup.value.trimEnd('.', ',', ';', '،', '؛', ')', '"')
        if (value.length >= MIN_VALUE_LENGTH && value !in found) {
            found[value] = CopyToken(kindOf(match.groupValues[1]), value)
        }
    }
    if (found.isEmpty() && codeWords.containsMatchIn(body)) {
        digits.find(body)?.let { found[it.value] = CopyToken(CopyKind.Code, it.value) }
    }
    return found.values.toList()
}
