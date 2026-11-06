package com.glooboo.umessage.ui.conversations

/**
 * Pure rules for the conversation row: how many preview lines it gets and its avatar tone. Kept free of Android so
 * they run in JVM tests. A bare number's display form lives in ui/components/PhoneNumberDisplay.kt.
 */

/** U55: the conversation whose fresh code gets the Copy chip: the first row in list order that has one. */
internal fun newestCodeThread(rows: List<ConversationRow>): Long? = rows.firstOrNull { it.otpCode != null }?.threadId

/**
 * CL-05: which of [paletteSize] avatar tones a name gets. The hash is over the trimmed, lower-cased name, so it stays
 * the same across launches, devices and capitalisation ("sara" and "Sara " share a colour).
 */
internal fun avatarToneIndex(name: String, paletteSize: Int): Int {
    require(paletteSize > 0) { "empty palette" }
    var hash = 0
    name.trim().lowercase().forEach { hash = HASH_MULTIPLIER * hash + it.code }
    return hash.mod(paletteSize)
}

private const val HASH_MULTIPLIER = 31
