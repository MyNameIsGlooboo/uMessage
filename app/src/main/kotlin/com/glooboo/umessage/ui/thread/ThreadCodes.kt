package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.helpers.OtpCodes

/** What a copy chip under a received bubble copies (TH-06). */
enum class CopyKind { Code, Password, Username }

data class CopyToken(val kind: CopyKind, val value: String)

/**
 * Credentials and one-time codes in a message body, for the chips under a bubble: labelled values ("password: x7Kq2")
 * first, else the code next to a keyword. All of them, in order, without repeats. The rule is [OtpCodes]' (the same as
 * the notification, the list and the filters); values come back with Latin digits.
 */
fun detectCopyTokens(body: String): List<CopyToken> =
    OtpCodes.tokens(body).map { CopyToken(CopyKind.valueOf(it.kind.name), it.value) }
