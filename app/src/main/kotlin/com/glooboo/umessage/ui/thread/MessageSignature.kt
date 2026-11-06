package com.glooboo.umessage.ui.thread

/** The signature line (I60): one short line added under a message when it is sent, never in the message field. */
object MessageSignature {
    const val MAX_LENGTH = 80

    /** What is stored for [typed]: one line, trimmed, at most [MAX_LENGTH] characters. */
    fun clean(typed: String): String = typed.lineSequence().joinToString(" ") { it.trim() }.trim().take(MAX_LENGTH)

    /** [text] with [signature] on a new line; an empty message or no signature leaves [text] as it is. */
    fun applyTo(text: String, signature: String): String {
        val line = clean(signature)
        return if (text.isBlank() || line.isEmpty()) text else "$text\n$line"
    }
}
