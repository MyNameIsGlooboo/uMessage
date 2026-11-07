package com.glooboo.umessage.export

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * D15: the name of a shared conversation file: `uMessage-<chat name>-<yyyy-MM-dd-HHmmss>.<ext>`. Two exports never
 * share a name (the time to the second, and a counter when even that is taken), so a receiving app that still reads
 * the earlier file is never served another chat under the same address. The chat name keeps letters and digits of any
 * script and turns everything else into a dash, so it can never hold a path, a dot-dot or a control character.
 */
object ExportFileName {
    const val PREFIX = "uMessage-"
    private const val MAX_TITLE = 40
    private const val FALLBACK = "chat"

    /** [title] reduced to letters and digits (any script), single dashes, at most [MAX_TITLE] characters. */
    fun sanitize(title: String): String {
        val dashed = buildString {
            for (c in title) append(if (c.isLetterOrDigit()) c else '-')
        }
        return dashed.replace(Regex("-+"), "-").trim('-').take(MAX_TITLE).trim('-').ifEmpty { FALLBACK }
    }

    /** A file name in [dir] for [title] at [now] that does not exist yet. */
    fun unique(dir: File, title: String, extension: String, now: Long): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date(now))
        val base = "$PREFIX${sanitize(title)}-$stamp"
        var file = File(dir, "$base.$extension")
        var counter = 2
        while (file.exists()) file = File(dir, "$base-${counter++}.$extension")
        return file
    }

    /** Deletes export files in [dir] older than [maxAgeMs], so shared ones stay readable for a while. */
    fun deleteOld(dir: File, now: Long, maxAgeMs: Long) {
        dir.listFiles { f -> f.name.startsWith(PREFIX) && now - f.lastModified() > maxAgeMs }?.forEach { it.delete() }
    }
}
