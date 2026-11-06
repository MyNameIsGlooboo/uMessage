package com.glooboo.umessage.helpers

/**
 * Drops control characters and unpaired surrogates, then trims. Addresses that reached the telephony provider from
 * a broken link (for example a NUL from `sms:%zz`) otherwise show as "?" in titles and break accessibility dumps.
 *
 * [keepFormat] keeps format characters (zero-width joiners, bidi marks): names need them, Persian ones in
 * particular use ZWNJ. Addresses do not, so recipient parsing passes false.
 */
fun String.stripControlCharacters(keepFormat: Boolean = true): String {
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        when {
            Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(this[i + 1]) -> {
                val codePoint = Character.toCodePoint(c, this[i + 1])
                if (keepFormat || Character.getType(codePoint) != Character.FORMAT.toInt()) {
                    out.append(c).append(this[i + 1])
                }
                i++
            }
            Character.isSurrogate(c) -> Unit
            Character.isISOControl(c) -> Unit
            !keepFormat && Character.getType(c) == Character.FORMAT.toInt() -> Unit
            else -> out.append(c)
        }
        i++
    }
    return out.toString().trim()
}
