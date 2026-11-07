package com.glooboo.umessage.export

import java.io.InputStream

/**
 * D9: reads one attachment for a backup without letting a single huge part (a long video) fail the whole backup. A
 * part above [MAX_BYTES], or one that runs the app out of memory, comes back as null; the caller counts it as an
 * unreadable attachment, which the backup summary shows, and the rest of the backup goes on.
 */
object PartBytes {
    /** The largest attachment a backup holds: its Base64 text and the JSON copy of it both have to fit in memory. */
    const val MAX_BYTES = 20 * 1024 * 1024

    /** The bytes of [stream], or null when empty, larger than [max], or too big for the memory left. */
    fun read(stream: InputStream, max: Int = MAX_BYTES): ByteArray? {
        return try {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > max) return null
                out.write(buffer, 0, count)
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private const val BUFFER = 64 * 1024
}
