package com.glooboo.umessage.helpers

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Sent-photo copies, camera shots, vCards and shared conversation text are written to the cache for the share or send
 * and nothing deletes them afterwards. They are removed at app start once old enough that no send or draft can still
 * use them, so a hidden or locked chat's pictures and text do not stay in the cache unencrypted.
 */
object CacheCleanup {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MINUTE_MS = 60L * 1000

    /** How long an exported file stays in the cache after the share sheet opened: long enough for a slow upload. */
    const val SHARE_COPY_LIFETIME_MS = 15 * MINUTE_MS

    /** Cache folder to its age limit. */
    private val folders = listOf(
        "attachments" to DAY_MS, "compressed" to DAY_MS, "shared_logs" to SHARE_COPY_LIFETIME_MS
    )

    /** Call off the main thread. */
    fun run(context: Context, now: Long = System.currentTimeMillis()) {
        folders.forEach { (name, maxAge) -> deleteOlderThan(File(context.cacheDir, name), maxAge, now) }
    }

    /**
     * Deletes the exported [file] after [SHARE_COPY_LIFETIME_MS], so a shared chat or decision log does not stay in the
     * cache unencrypted. If the app is closed before then, [run] removes it at the next start.
     */
    fun deleteAfterShare(file: File, delayMs: Long = SHARE_COPY_LIFETIME_MS) {
        Handler(Looper.getMainLooper()).postDelayed({ Thread { deleteQuietly(file) }.start() }, delayMs)
    }

    /** Deletes [file] when it exists; true when it is gone afterwards. */
    internal fun deleteQuietly(file: File): Boolean =
        runCatching { !file.exists() || file.delete() }.getOrDefault(false)

    /** Deletes the files directly in [dir] last changed more than [maxAgeMs] before [now]; returns how many. */
    internal fun deleteOlderThan(dir: File, maxAgeMs: Long, now: Long): Int {
        var deleted = 0
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (file.isFile && now - file.lastModified() > maxAgeMs && file.delete()) deleted++
            }
        }
        return deleted
    }
}
