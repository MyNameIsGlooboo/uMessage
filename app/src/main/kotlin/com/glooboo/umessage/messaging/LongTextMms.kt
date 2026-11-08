package com.glooboo.umessage.messaging

import android.telephony.SmsMessage

/**
 * Q9: when a long text becomes a picture message (MMS). By default never: a long text goes as one multipart SMS,
 * however long. The user can choose a number of SMS parts from which the text is sent as MMS instead.
 *
 * The part count is the platform's own: [SmsMessage.calculateLength] counts the parts the same way
 * `SmsManager.divideMessage` splits them, so Persian and Arabic text (Unicode, 70 characters in a single SMS and 67 in
 * each part of a longer one) reaches the threshold sooner than plain English text.
 */
object LongTextMms {
    /** The stored value for "never". */
    const val NEVER = 0

    /** The thresholds the setting offers after "never": send as MMS from this many SMS parts. */
    @Suppress("MagicNumber")
    val THRESHOLDS: List<Int> = listOf(2, 3, 4, 5, 6, 8, 10)

    /** Every choice of the setting, "never" first. */
    val CHOICES: List<Int> = listOf(NEVER) + THRESHOLDS

    /** [stored] if it is one of the [CHOICES], otherwise "never" (a damaged or foreign value must not turn MMS on). */
    fun sanitize(stored: Int): Int = if (stored in CHOICES) stored else NEVER

    /**
     * The setting, given what is stored. [stored] is the new value or null if it was never written; [legacyOn] is the
     * old on/off switch (off unless the user turned it on), which sent a text as MMS from 2 parts. Nobody gets MMS by
     * default: only an old explicit "on" survives, as the threshold 2.
     */
    fun resolve(stored: Int?, legacyOn: Boolean): Int = when {
        stored != null -> sanitize(stored)
        legacyOn -> THRESHOLDS.first()
        else -> NEVER
    }

    /** True when a text of [parts] SMS parts goes as MMS under [after] ([NEVER] or a threshold). */
    fun shouldSendAsMms(parts: Int, after: Int): Boolean = after != NEVER && parts >= after

    /** The number of SMS parts [text] needs, as the platform splits it. */
    fun smsParts(text: String): Int = SmsMessage.calculateLength(text, false).first().coerceAtLeast(1)
}
