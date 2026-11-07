package com.glooboo.umessage.ui.thread

/**
 * How a text splits into SMS parts. Text in the GSM alphabet (plain English) fits 160 characters in one SMS and 153
 * in each part of a longer one; any other letter (Persian, Arabic, most emoji) makes the whole message Unicode, which
 * fits only 70, or 67 per part (RM-46). [remaining] is what still fits in the last part.
 */
data class SmsSegments(val parts: Int, val remaining: Int, val unicode: Boolean) {
    val isMultipart: Boolean get() = parts > 1

    /** So long that sending it as SMS costs [MANY_PARTS] or more messages (I5). */
    val isVeryLong: Boolean get() = parts > MANY_PARTS

    /** Characters one part holds for this kind of text. */
    val perPart: Int get() = if (unicode) {
        if (isMultipart) UCS2_MULTI else UCS2_SINGLE
    } else {
        if (isMultipart) GSM_MULTI else GSM_SINGLE
    }

    companion object {
        const val GSM_SINGLE = 160
        const val GSM_MULTI = 153
        const val UCS2_SINGLE = 70
        const val UCS2_MULTI = 67

        /** Parts above this are worth a warning: a text this long costs that many SMS (I5). */
        const val MANY_PARTS = 6

        fun of(text: String): SmsSegments {
            val gsmLength = gsmSeptets(text)
            val unicode = gsmLength == null
            val length = gsmLength ?: text.length
            val single = if (unicode) UCS2_SINGLE else GSM_SINGLE
            val multi = if (unicode) UCS2_MULTI else GSM_MULTI
            if (length <= single) return SmsSegments(parts = 1, remaining = single - length, unicode = unicode)
            val (parts, used) = pack(text, unicode, multi)
            return SmsSegments(parts = parts, remaining = multi - used, unicode = unicode)
        }

        /**
         * Fills parts of [capacity] units the way the platform splits: a character is never cut in two, so an emoji
         * (two UTF-16 units) or a GSM extension character (two septets) that would straddle the end moves to the next
         * part, which can cost one part more than length / capacity says (I6). Returns the part count and the units
         * used in the last part.
         */
        private fun pack(text: String, unicode: Boolean, capacity: Int): Pair<Int, Int> {
            var parts = 1
            var used = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val width = when {
                    unicode && c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> 2
                    !unicode && GSM_BASIC.indexOf(c) < 0 -> 2
                    else -> 1
                }
                if (used + width > capacity) {
                    parts++
                    used = 0
                }
                used += width
                i += width.takeIf { unicode } ?: 1
            }
            return parts to used
        }

        /** Length in GSM 7-bit septets (characters from the extension table take two), or null when not GSM. */
        private fun gsmSeptets(text: String): Int? {
            var septets = 0
            for (c in text) {
                septets += when {
                    GSM_BASIC.indexOf(c) >= 0 -> 1
                    GSM_EXTENSION.indexOf(c) >= 0 -> 2
                    else -> return null
                }
            }
            return septets
        }

        private const val GSM_BASIC =
            "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
                "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
        private const val GSM_EXTENSION = "\u000C^{}\\[~]|€"
    }
}
