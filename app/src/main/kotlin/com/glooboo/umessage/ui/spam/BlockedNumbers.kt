package com.glooboo.umessage.ui.spam

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.BlockedNumberContract.BlockedNumbers
import android.provider.ContactsContract.PhoneLookup
import android.telephony.PhoneNumberUtils
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat

/** One row of the system block list. [contactName] is set when the number belongs to a contact. */
@Immutable
data class BlockedNumberItem(val id: Long, val number: String, val contactName: String? = null) {
    val isPattern: Boolean get() = BlockedNumberFormat.isPattern(number)
}

/** Pure text rules of the blocked-numbers screen: input clean-up and the import/export file format. */
object BlockedNumberFormat {
    /** Separator of the export file, compatible with files exported by Fossify apps. */
    const val EXPORT_DELIMITER = ","

    fun isPattern(number: String): Boolean = '*' in number

    /** Trims the input and turns a regex-style ".*" into the "*" wildcard the matcher understands. */
    fun normalizeInput(raw: String): String = raw.trim().replace(".*", "*")

    private val formatting = Regex("[\\s\\-().]")
    private val numberChars = Regex("\\+?[0-9*]+")

    /**
     * True for something the block list can use: a phone number (3 to 15 digits, optional +, spaces and dashes
     * allowed) or a wildcard pattern with at least one digit. Words, rule lines and a lone "*" are refused: the list is
     * shared with the phone app, so junk there would also affect calls.
     */
    fun isValidEntry(number: String): Boolean {
        val cleaned = normalizeInput(number).replace(formatting, "")
        if (!numberChars.matches(cleaned) || cleaned.indexOf('+') > 0) return false
        val digits = cleaned.count { it.isDigit() }
        return if (isPattern(cleaned)) digits >= 1 else digits in 3..15
    }

    /**
     * Longest number still read as a short code ("30002", "123456"). Seven and eight digits are ordinary local numbers
     * ("5559990", a landline dialled without its area code), so they get no warning.
     */
    private const val SHORT_CODE_DIGITS = 6

    /** Start of bulk-SMS service numbers, which run longer than a short code. */
    private val bulkPrefixes = listOf("1000", "2000", "3000", "5000", "9000")

    /**
     * A short code or bulk service number (BR-28): digits only, without a leading + or 0, short or with a bulk prefix.
     * Android throws away texts from numbers on its list before uMessage sees them, and these are the senders whose
     * texts people most often want back (codes, bank and carrier notices), so the add dialog warns about them.
     */
    fun isShortCode(number: String): Boolean {
        val cleaned = normalizeInput(number).replace(formatting, "")
        // Digits only also rules out "+", a wildcard and the empty string.
        val plainDigits = cleaned.isNotEmpty() && cleaned.all { it.isDigit() }
        if (!plainDigits || cleaned.startsWith("0")) return false
        return cleaned.length in 3..SHORT_CODE_DIGITS || bulkPrefixes.any { cleaned.startsWith(it) }
    }

    /** [numbers] to add, and how many entries were [skipped] because they are not numbers. */
    data class ImportResult(val numbers: List<String>, val skipped: Int)

    /** Numbers in an import file: comma separated (Fossify format) or one per line; blanks and duplicates dropped. */
    fun parseImportChecked(text: String): ImportResult {
        val entries = text.split(',', '\n', '\r').map(::normalizeInput).filter { it.isNotEmpty() }
        val (valid, invalid) = entries.partition(::isValidEntry)
        return ImportResult(valid.distinct(), invalid.size)
    }

    fun parseImport(text: String): List<String> = parseImportChecked(text).numbers

    fun exportText(numbers: List<String>): String = numbers.joinToString(EXPORT_DELIMITER)

    /** Contacts first by name, then the rest by number (the order the Fossify screen used). */
    fun sorted(items: List<BlockedNumberItem>): List<BlockedNumberItem> {
        val (named, unnamed) = items.partition { it.contactName != null }
        return named.sortedBy { it.contactName } + unnamed.sortedBy { it.number }
    }
}

/**
 * The platform block list ([BlockedNumberContract]), shared with the phone app. Only the default SMS app, dialer
 * or carrier app may read it; every call fails soft (empty list, false) when access is refused.
 */
object SystemBlockList {
    fun isAvailable(context: Context): Boolean =
        runCatching { BlockedNumberContract.canCurrentUserBlockNumbers(context) }.getOrDefault(false)

    fun load(context: Context): List<BlockedNumberItem> {
        val items = mutableListOf<BlockedNumberItem>()
        val projection = arrayOf(BlockedNumbers.COLUMN_ID, BlockedNumbers.COLUMN_ORIGINAL_NUMBER)
        runCatching {
            context.contentResolver.query(BlockedNumbers.CONTENT_URI, projection, null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(BlockedNumbers.COLUMN_ID)
                val numberColumn = cursor.getColumnIndexOrThrow(BlockedNumbers.COLUMN_ORIGINAL_NUMBER)
                while (cursor.moveToNext()) {
                    val number = cursor.getString(numberColumn).orEmpty()
                    if (number.isNotEmpty()) items += BlockedNumberItem(cursor.getLong(idColumn), number)
                }
            }
        }
        val canReadContacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        val named = if (canReadContacts) items.map { it.copy(contactName = contactName(context, it)) } else items
        return BlockedNumberFormat.sorted(named)
    }

    fun add(context: Context, number: String): Boolean = runCatching {
        val values = ContentValues().apply {
            put(BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number)
            if (!BlockedNumberFormat.isPattern(number) && PhoneNumberUtils.isGlobalPhoneNumber(number)) {
                put(BlockedNumbers.COLUMN_E164_NUMBER, PhoneNumberUtils.normalizeNumber(number))
            }
        }
        context.contentResolver.insert(BlockedNumbers.CONTENT_URI, values) != null
    }.getOrDefault(false)

    fun remove(context: Context, id: Long): Boolean = runCatching {
        context.contentResolver.delete(ContentUris.withAppendedId(BlockedNumbers.CONTENT_URI, id), null, null) > 0
    }.getOrDefault(false)

    private fun contactName(context: Context, item: BlockedNumberItem): String? {
        if (item.isPattern) return null
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(item.number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()
    }
}
