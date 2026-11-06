package com.glooboo.umessage.helpers

import android.content.Intent
import android.net.Uri
import com.google.android.mms.ContentType
import java.net.URLDecoder

// Base on https://cs.android.com/android/platform/superproject/main/+/main:packages/apps/Messaging/src/com/android/messaging/ui/conversation/LaunchConversationActivity.java
object SmsIntentParser {
    private const val SCHEME_SMS = "sms"
    private const val SCHEME_SMSTO = "smsto"
    private const val SCHEME_MMS = "mms"
    private const val SCHEME_MMSTO = "mmsto"
    private val SMS_MMS_SCHEMES = setOf(SCHEME_SMS, SCHEME_SMSTO, SCHEME_MMS, SCHEME_MMSTO)

    private const val MAX_RECIPIENT_LENGTH = 100
    private const val SMS_BODY = "sms_body"
    private const val ADDRESS = "address"

    fun parse(intent: Intent): Pair<String, String>? {
        val action = intent.action
        if (action != Intent.ACTION_SENDTO && action != Intent.ACTION_VIEW) {
            // Unsupported intent action
            return null
        }

        val recipients = parseRecipients(intent)
        val body = extractBodyFromIntent(intent)
        return body.orEmpty() to recipients
    }

    private fun parseRecipients(intent: Intent): String {
        val uriRecipients = parseRecipientsFromUri(intent.data)
        val extraAddress = intent.getStringExtra(ADDRESS)
        val extraEmail = intent.getStringExtra(Intent.EXTRA_EMAIL)

        val recipients = when {
            !extraAddress.isNullOrEmpty() -> arrayOf(extraAddress)
            !extraEmail.isNullOrEmpty() -> arrayOf(extraEmail)
            else -> uriRecipients.orEmpty()
        }

        return recipients
            .map { sanitizeRecipient(it) }
            .filter { it.length < MAX_RECIPIENT_LENGTH && isAddressable(it) }
            .joinToString(";")
    }

    /**
     * Drops control and format characters and unpaired surrogates, then trims. A link such as `sms:%00` or
     * `sms:%zz` otherwise decodes to a NUL that shows as "?" in titles and breaks accessibility dumps.
     */
    internal fun sanitizeRecipient(raw: String): String = raw.stripControlCharacters(keepFormat = false)

    private val PHONE_NUMBER = Regex("""\+?[0-9 ()\-.#*]+""")
    private val EMAIL = Regex("""[^@\s]+@[^@\s]+\.[^@\s]+""")
    private val SENDER_ID = Regex("""[\p{L}\p{N}][\p{L}\p{N} ._\-]*[\p{L}\p{N}]""")

    /**
     * Something a message can go to: a phone number (at least one digit), an e-mail address, or an alphanumeric
     * sender id of two or more characters. Leftovers of a broken link (`sms:%zz` becomes "z", or a replacement
     * character) are not, and must not open a thread.
     */
    internal fun isAddressable(recipient: String): Boolean = when {
        recipient.matches(PHONE_NUMBER) -> recipient.any { it.isDigit() }
        recipient.matches(EMAIL) -> true
        else -> recipient.matches(SENDER_ID)
    }

    /**
     * [Uri.getSchemeSpecificPart] is already percent-decoded (a malformed escape such as `%zz` becomes a replacement
     * character instead of throwing), so callers must not decode the recipients a second time.
     */
    private fun parseRecipientsFromUri(uri: Uri?): Array<String>? {
        if (uri == null || uri.scheme !in SMS_MMS_SCHEMES) return null
        val schemeSpecificPart = uri.schemeSpecificPart.split("?").firstOrNull() ?: return null
        return schemeSpecificPart.replace(';', ',').split(",").toTypedArray()
    }

    private fun extractBodyFromIntent(intent: Intent): String? {
        val uriBody = extractBodyFromUri(intent.data)
        val smsBody = intent.getStringExtra(SMS_BODY)
        val extraText = if (ContentType.TEXT_PLAIN == intent.type) {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else {
            // Invalid URL, probably
            null
        }

        return smsBody ?: uriBody ?: extraText
    }

    /**
     * Reads `body=` from the still-encoded query and decodes it exactly once. A malformed escape (`100%`, `%zz`)
     * keeps the raw text instead of crashing: any app or web page can open uMessage with such a link.
     */
    private fun extractBodyFromUri(uri: Uri?): String? {
        if (uri == null) return null
        val query = uri.encodedQuery ?: return null
        val bodyParam = query.split("&").firstOrNull { it.startsWith("body=") } ?: return null
        val raw = bodyParam.removePrefix("body=")
        return try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (_: IllegalArgumentException) {
            raw
        }
    }
}
