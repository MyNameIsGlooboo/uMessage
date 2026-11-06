package com.glooboo.umessage.export

/** The plain text file: a few header lines, the message text block exactly as hashed, then the footer lines. */
object ConversationTextWriter {
    fun render(export: ConversationExport, header: List<String>, footer: List<String>): String =
        (listOf(header.joinToString("\n"), export.textBlock, footer.joinToString("\n")))
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
}
