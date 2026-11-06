package com.glooboo.umessage.helpers

private val webLink = Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE)
private const val TRAILING_PUNCTUATION = ".,;:!?)]}'\u060C\u061B"

/** The first http(s) link of [body] without the punctuation that ends a sentence, or null when there is none. */
fun firstWebLink(body: String): String? =
    webLink.find(body)?.value?.trimEnd { it in TRAILING_PUNCTUATION }?.takeIf { it.length > "https://".length }
