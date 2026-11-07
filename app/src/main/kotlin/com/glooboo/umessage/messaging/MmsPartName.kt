package com.glooboo.umessage.messaging

import android.webkit.MimeTypeMap

private const val FALLBACK_EXTENSION = "bin"
private const val MAX_NAME_LENGTH = 100

/**
 * A name for an MMS part that is never empty. The MMS library rejects a part with an empty name, content id or
 * location, and a picture read back from the provider (a resend) has no stored filename. Uses [filename] when it holds
 * something, else "part_<[partId] or [fallbackStamp]>.<extension of [mimeType]>", with "bin" for an unknown type.
 * Path separators and spaces are replaced, so the name is safe as a content id and location.
 */
fun mmsPartName(filename: String?, partId: Long?, mimeType: String?, fallbackStamp: Long): String {
    val clean = filename.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        .map { if (it.isWhitespace() || it.isISOControl()) '_' else it }
        .joinToString("")
        .take(MAX_NAME_LENGTH)
    if (clean.isNotBlank() && clean.any { it != '.' && it != '_' }) return clean
    val extension = mimeType?.takeIf { it.isNotBlank() }
        ?.let { runCatching { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }.getOrNull() }
        ?.takeIf { it.isNotBlank() } ?: FALLBACK_EXTENSION
    return "part_${partId ?: fallbackStamp}.$extension"
}
