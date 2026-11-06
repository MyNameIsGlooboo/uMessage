package com.glooboo.umessage.helpers

/**
 * I28, I56: the web links of a message text, in order and without repeats. A link starts with http://, https:// or
 * www.; it ends at a space, at a Persian letter or at the punctuation that ends a sentence. One class, so the search
 * shortcut and the conversation's links list agree on what a link is.
 */
class LinkExtractor {

    fun extract(body: String): List<String> {
        if (body.isEmpty()) return emptyList()
        val links = LinkedHashSet<String>()
        link.findAll(body).forEach { match ->
            val prefix = match.groupValues[1]
            val url = match.value.trimEnd { it in TRAILING_PUNCTUATION }
            if (url.length > prefix.length) links.add(url)
        }
        return links.toList()
    }

    fun hasLink(body: String): Boolean = extract(body).isNotEmpty()

    private companion object {
        val link = Regex("""(?i)(https?://|www\.)[^\s<>"\u0600-\u06FF\u200c\u200f]+""")
        const val TRAILING_PUNCTUATION = ".,;:!?)]}'\u060C\u061B\u061F"
    }
}
