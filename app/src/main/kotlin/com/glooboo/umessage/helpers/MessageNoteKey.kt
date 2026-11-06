package com.glooboo.umessage.helpers

import android.provider.Telephony
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.TextNormalizer

/**
 * The stable identity of a message for its note. The provider id is not stable (a restore or import hands out new
 * ones), so the key is built from what a restored copy of the message still has:
 *
 * `v1|<s or m>|<r or s>|<date in seconds>|<last ten letters/digits of the address>|<hash of the text>`
 *
 * - `s` / `m`: SMS or MMS.
 * - `r` / `s`: received or sent. Only the direction, not the exact type, because a sent message moves from outbox
 *   to sent (or failed) and must keep its note.
 * - the address is folded first (Persian and Arabic-Indic digits become ASCII), so "+1 555 000 1234",
 *   "05550001234" and the same number in Persian digits agree.
 * - the text hash is [StarredMessages.fingerprint], the same white-space-folded fingerprint the stars use.
 *
 * Two messages with the same direction, second, address and text have the same key and share one note.
 */
object MessageNoteKey {
    private const val VERSION = "v1"
    private const val SEP = '|'
    private const val ADDRESS_TAIL = 10

    /** The note key of a cached [message]. */
    fun of(message: Message): String = of(
        address = StarredMessages.addressOf(message),
        dateSeconds = message.date,
        received = message.type == Telephony.Sms.MESSAGE_TYPE_INBOX,
        body = message.body,
        isMms = message.isMMS,
    )

    /** The note key from the parts of a message; [dateSeconds] is the message time in seconds. */
    fun of(address: String, dateSeconds: Int, received: Boolean, body: String, isMms: Boolean): String =
        listOf(
            VERSION,
            if (isMms) "m" else "s",
            if (received) "r" else "s",
            dateSeconds.toString(),
            normalizedAddress(address),
            Integer.toHexString(StarredMessages.fingerprint(body)),
        ).joinToString(SEP.toString())

    /** Letters and digits only, digits folded to ASCII, lower case, the last ten. */
    fun normalizedAddress(raw: String): String =
        TextNormalizer.fold(raw).filter { it.isLetterOrDigit() }.lowercase().takeLast(ADDRESS_TAIL)

    /** True when [key] is the key of an MMS; false for an SMS or a key this version does not know. */
    fun isMms(key: String): Boolean = key.split(SEP).getOrNull(1) == "m"

    /** The message time in seconds that [key] was built from, or null when it is not a key of this version. */
    fun dateSeconds(key: String): Int? {
        val parts = key.split(SEP)
        return if (parts.size == PARTS && parts[0] == VERSION) parts[DATE_INDEX].toIntOrNull() else null
    }

    private const val PARTS = 6
    private const val DATE_INDEX = 3
}
