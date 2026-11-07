package com.glooboo.umessage.helpers

import android.content.Context
import com.glooboo.umessage.export.ConversationExportJob
import java.io.File

/**
 * Deletes the plain copies the app leaves in its cache: whole-chat exports and shared text or log files, the
 * compressed copies of outgoing photos, and camera captures. Android clears the cache only when storage runs out, so
 * without this an export of a chat the user has since hidden would stay readable in app storage for good.
 *
 * Runs about once a day from [DailyChecks] and removes files older than 24 hours (a share or a send needs them for
 * seconds), so a message still being written keeps its picture for a day.
 */
object CacheCleaner {
    /** The cache sub-folders that hold shared or outgoing copies (the same names the share code writes to). */
    val FOLDERS = listOf(ConversationExportJob.SHARED_DIR, "compressed", "attachments")

    const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private const val PREFS = "umessage_cache_cleaner"
    private const val KEY_LAST_RUN = "last_run"

    /** Runs the clean-up when it has not run for a day. Blocking: call it on a background thread. */
    fun runIfDue(context: Context, now: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_RUN, 0L)
        if (now - last in 0 until MAX_AGE_MS) return
        prefs.edit().putLong(KEY_LAST_RUN, now).apply()
        clean(context.cacheDir, now)
    }

    /** Deletes the files in [FOLDERS] under [cacheDir] that were last changed more than [maxAgeMs] before [now]. */
    fun clean(cacheDir: File, now: Long, maxAgeMs: Long = MAX_AGE_MS): Int {
        var deleted = 0
        for (name in FOLDERS) {
            val files = File(cacheDir, name).listFiles() ?: continue
            for (file in files) {
                if (file.isFile && now - file.lastModified() > maxAgeMs && file.delete()) deleted++
            }
        }
        return deleted
    }
}
