package com.glooboo.umessage.ui.thread

/** How the composer's text is combined with a stored draft or a shared text, so neither is silently lost (B8, B9). */
object DraftText {
    /** The composer after the stored [draft] is put back: unchanged when it already holds it, else both. */
    fun restore(current: String, draft: String): String = when {
        current.isEmpty() -> draft
        current.contains(draft) -> current
        else -> draft + "\n" + current
    }

    /** The composer after text [shared] into this chat arrives: after what is typed, on a new line. */
    fun appendShared(current: String, shared: String): String = when {
        current.isEmpty() -> shared
        current.contains(shared) -> current
        else -> current + "\n" + shared
    }
}
