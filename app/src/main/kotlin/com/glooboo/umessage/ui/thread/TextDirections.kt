package com.glooboo.umessage.ui.thread

/** True when the first strong character of [text] is right-to-left (Persian, Arabic, Hebrew...); neutral text is LTR. */
fun startsRightToLeft(text: CharSequence): Boolean {
    var i = 0
    while (i < text.length) {
        val codePoint = Character.codePointAt(text, i)
        when (Character.getDirectionality(codePoint)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
        }
        i += Character.charCount(codePoint)
    }
    return false
}
