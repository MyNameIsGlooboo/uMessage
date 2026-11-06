package com.glooboo.umessage.dialogs

import org.fossify.commons.helpers.AppLockSecurity
import org.fossify.commons.helpers.MAX_PASSWORD_RETRY_COUNT
import org.fossify.commons.helpers.PROTECTION_PATTERN
import org.fossify.commons.helpers.PROTECTION_PIN
import java.security.MessageDigest

/**
 * Pure rules behind the app-lock dialog, shared with the commons lock screen ([AppLockSecurity]).
 *
 * An entry is first reduced to its "entry hash": lowercase hex SHA-1 of the PIN, or of the pattern cells written as
 * consecutive decimal numbers (row * 3 + column). That is what the lock screen computes too. The stored value is a
 * salted PBKDF2 of the entry hash ([protect]); a stored bare SHA-1 from older versions still verifies and is replaced
 * after the next correct entry ([upgrade]).
 */
object AppLockLogic {
    const val PATTERN_SIZE = 3
    /** Shortest new PIN; existing shorter ones keep working (see [isWeak]). Six digits are suggested. */
    const val MIN_PIN_LENGTH = 4
    const val SUGGESTED_PIN_LENGTH = 6

    /** Shortest new pattern, as on the Android lock screen: a 1-dot pattern has only 9 possibilities. */
    const val MIN_PATTERN_DOTS = 4
    const val MAX_PIN_LENGTH = 10
    const val MAX_RETRIES = MAX_PASSWORD_RETRY_COUNT

    /** Minimum length of a new credential of [type] (digits or dots), 0 when length does not apply. */
    fun minimumLength(type: Int): Int = when (type) {
        PROTECTION_PIN -> MIN_PIN_LENGTH
        PROTECTION_PATTERN -> MIN_PATTERN_DOTS
        else -> 0
    }

    /** True when an entry of [length] digits or dots is shorter than a new one may be: the user should change it. */
    fun isWeak(type: Int, length: Int): Boolean = length < minimumLength(type)

    /** The value to store for a new credential. */
    fun protect(entryHash: String): String = AppLockSecurity.create(entryHash)

    fun verify(stored: String, entryHash: String): Boolean = AppLockSecurity.verify(stored, entryHash)

    fun upgrade(stored: String, entryHash: String): String = AppLockSecurity.upgrade(stored, entryHash)

    fun sha1Hex(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun hashPin(pin: String): String = sha1Hex(pin)

    /** [cells] are indexes 0..8 (row * 3 + column). */
    fun hashPattern(cells: List<Int>): String = sha1Hex(cells.joinToString(""))

    /**
     * Adds [cell] to the drawn pattern the way the system lock pad does: a dot that lies exactly between the last dot
     * and [cell] is taken along when it was not visited yet. Visited cells are ignored.
     */
    fun addPatternCell(pattern: List<Int>, cell: Int): List<Int> {
        if (cell in pattern || cell !in 0 until PATTERN_SIZE * PATTERN_SIZE) return pattern
        val last = pattern.lastOrNull() ?: return pattern + cell
        val dRow = cell / PATTERN_SIZE - last / PATTERN_SIZE
        val dCol = cell % PATTERN_SIZE - last % PATTERN_SIZE
        var fillRow = last / PATTERN_SIZE
        var fillCol = last % PATTERN_SIZE
        if (kotlin.math.abs(dRow) == 2 && kotlin.math.abs(dCol) != 1) fillRow += if (dRow > 0) 1 else -1
        if (kotlin.math.abs(dCol) == 2 && kotlin.math.abs(dRow) != 1) fillCol += if (dCol > 0) 1 else -1
        val fill = fillRow * PATTERN_SIZE + fillCol
        return if (fill != last && fill !in pattern) pattern + fill + cell else pattern + cell
    }

    /**
     * Seconds the user still has to wait after [failures] consecutive wrong entries, the last at [lastFailureMs]. The
     * wait starts at the [MAX_RETRIES]th wrong entry and doubles with each further one; only a correct entry resets it.
     */
    fun lockoutSeconds(failures: Int, lastFailureMs: Long, nowMs: Long): Int =
        AppLockSecurity.remainingSeconds(failures, lastFailureMs, nowMs)
}

/**
 * Two-step entry for PIN and pattern, fed with entry hashes. With no stored value the first entry is kept and has to be
 * repeated (setting up), and [Result.Correct] carries the value to store ([protect]). With one, the entry has to match
 * it (checking), and [Result.Correct] carries the value to keep (an old format upgraded).
 *
 * A new credential shorter than [minLength] digits or dots is refused with [Result.TooShort]; checking an existing one
 * never looks at its length, so an old short PIN or pattern keeps working.
 */
class CredentialEntry(
    private val requiredHash: String,
    private val minLength: Int = 0,
    private val protect: (String) -> String = AppLockLogic::protect,
) {
    private var expected = requiredHash

    /** True while waiting for the repeated entry of a new credential. */
    val isRepeating: Boolean get() = requiredHash.isEmpty() && expected.isNotEmpty()

    sealed interface Result {
        /** First entry of a new credential taken; ask for it again. */
        data object Repeat : Result

        /** A new credential that is too short; nothing was kept, ask again. */
        data object TooShort : Result
        data class Correct(val hash: String) : Result
        data object Wrong : Result
    }

    /** [length] is the number of digits or dots entered. */
    fun submit(hash: String, length: Int = Int.MAX_VALUE): Result = when {
        expected.isEmpty() && length < minLength -> Result.TooShort
        expected.isEmpty() -> {
            expected = hash
            Result.Repeat
        }
        requiredHash.isEmpty() && expected == hash -> Result.Correct(protect(hash))
        requiredHash.isNotEmpty() && AppLockLogic.verify(requiredHash, hash) ->
            Result.Correct(AppLockLogic.upgrade(requiredHash, hash))
        else -> {
            if (requiredHash.isEmpty()) expected = ""
            Result.Wrong
        }
    }
}

/** Touch geometry of the 3x3 pattern pad. */
object PatternGeometry {
    /** Fraction of a cell's width within which a touch counts as hitting its dot. */
    const val HIT_RADIUS_FRACTION = 0.35f

    /** The cell (row * 3 + column) whose dot [x],[y] touches on a pad of [width] x [height], or null. */
    fun cellAt(x: Float, y: Float, width: Float, height: Float): Int? {
        if (width <= 0f || height <= 0f) return null
        val cellW = width / AppLockLogic.PATTERN_SIZE
        val cellH = height / AppLockLogic.PATTERN_SIZE
        val col = (x / cellW).toInt()
        val row = (y / cellH).toInt()
        if (x < 0f || y < 0f || col !in 0 until AppLockLogic.PATTERN_SIZE || row !in 0 until AppLockLogic.PATTERN_SIZE) return null
        val dx = x - (col + 0.5f) * cellW
        val dy = y - (row + 0.5f) * cellH
        val radius = minOf(cellW, cellH) * HIT_RADIUS_FRACTION
        return if (dx * dx + dy * dy <= radius * radius) row * AppLockLogic.PATTERN_SIZE + col else null
    }
}
