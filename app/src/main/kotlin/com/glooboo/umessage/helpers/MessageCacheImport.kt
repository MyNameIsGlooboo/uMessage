package com.glooboo.umessage.helpers

import android.content.Context
import androidx.core.content.edit
import com.glooboo.umessage.backup.PendingRecycled
import com.glooboo.umessage.extensions.getMessages
import com.glooboo.umessage.extensions.getOlderThreadMessages
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.models.Message
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * DL-10 / MC-16: copies every SMS and MMS of the phone into the Room `messages` cache, which is what search reads.
 *
 * The old first-run import copied only the newest [MESSAGES_LIMIT] messages of each thread, and only on the very
 * first start, so older messages were never searchable and a first start that was cut short stayed incomplete.
 * This import pages through each whole thread, newest first, one page in memory at a time. It is resumable: each
 * finished thread is recorded, so a stopped import continues with the next unfinished thread on the next start, and
 * re-reading a thread is harmless because rows are inserted with IGNORE on their (id, is_mms) key. Once every thread
 * is done the import is marked complete and later starts skip it; messages that arrive afterwards are cached as the
 * app sees them. A restored backup restarts it ([restart]).
 *
 * Searching the provider directly was the other option. It was not taken: MMS text lives in a separate parts table,
 * the provider cannot run the Persian/Arabic folding of [com.glooboo.umessage.spam.TextNormalizer.sqlFoldedLike]
 * (MC-15), and results would still need the cache to open the found message in its thread.
 */
class MessageCacheImport(
    private val progress: Progress,
    /** The page of [threadId] older than [cutoff] (Unix seconds, -1 for the newest page), without [loaded]. */
    private val readPage: (threadId: Long, cutoff: Int, loaded: List<Message>) -> List<Message>,
    private val save: (List<Message>) -> Unit,
) {
    /** Where the import stands; kept across starts. */
    interface Progress {
        var complete: Boolean
        fun isThreadDone(threadId: Long): Boolean
        fun markThreadDone(threadId: Long)
    }

    /**
     * Imports every thread in [threadIds] not done yet. Returns true when the import is complete. A thread that fails
     * to read is left unfinished and tried again on the next run; the others still go ahead.
     */
    fun run(threadIds: List<Long>, isCancelled: () -> Boolean = { false }): Boolean {
        if (progress.complete) return true
        var allDone = true
        for (threadId in threadIds) {
            if (isCancelled()) return false
            if (progress.isThreadDone(threadId)) continue
            val ok = runCatching { importThread(threadId, isCancelled) }.getOrDefault(false)
            if (ok) progress.markThreadDone(threadId) else allDone = false
        }
        if (allDone) progress.complete = true
        return allDone
    }

    /** True when the whole thread was read; false when cancelled half way. */
    private fun importThread(threadId: Long, isCancelled: () -> Boolean): Boolean {
        var cutoff = -1
        // Messages already read from second `cutoff`: a page ends inside a second, so the next one starts in it too.
        var atCutoff: List<Message> = emptyList()
        repeat(MAX_PAGES) {
            if (isCancelled()) return false
            val page = readPage(threadId, cutoff, atCutoff)
            if (page.isEmpty()) return true
            save(page)
            val oldest = page.minOf { it.date }
            atCutoff = page.filter { it.date == oldest } + if (oldest == cutoff) atCutoff else emptyList()
            cutoff = oldest
        }
        return true
    }

    companion object {
        /** A safety stop for one thread: [MESSAGES_LIMIT] times this is far more than any real thread holds. */
        const val MAX_PAGES = 20_000

        private const val PREFS = "message_cache_import"
        private const val KEY_COMPLETE = "complete"
        private const val KEY_DONE_THREADS = "done_threads"

        private val running = AtomicBoolean(false)

        /** Bumped by [restart]: a run that started before stops and records nothing more. */
        private val generation = AtomicInteger(0)

        /**
         * Runs the import on the calling (background) thread unless it is complete or already running elsewhere.
         * [threadIds] are the provider's threads in the order to import them (newest conversation first).
         */
        fun importIfNeeded(context: Context, threadIds: List<Long>) {
            val app = context.applicationContext
            val startedIn = generation.get()
            val isStale = { generation.get() != startedIn }
            val progress = PrefsProgress(app, isStale)
            if (progress.complete || !running.compareAndSet(false, true)) return
            try {
                MessageCacheImport(
                    progress = progress,
                    readPage = { threadId, cutoff, loaded ->
                        if (cutoff == -1) {
                            app.getMessages(threadId, includeScheduledMessages = false)
                        } else {
                            app.getOlderThreadMessages(threadId, cutoff, loaded)
                        }
                    },
                    save = { app.messagesDB.insertOrIgnoreAll(it) },
                ).run(threadIds, isCancelled = isStale)
                // Messages a restore recycled are cached now, so the names it kept can find them.
                runCatching { PendingRecycled(app).applyTo(app.messagesDB) }
            } finally {
                running.set(false)
            }
        }

        /** Imports everything again on the next start, after the provider gained messages the app did not see. */
        fun restart(context: Context) {
            generation.incrementAndGet()
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
        }
    }

    private class PrefsProgress(context: Context, private val isStale: () -> Boolean) : Progress {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val done = HashSet(prefs.getStringSet(KEY_DONE_THREADS, emptySet()).orEmpty())

        override var complete: Boolean
            get() = prefs.getBoolean(KEY_COMPLETE, false)
            set(value) {
                if (isStale()) return
                prefs.edit {
                    putBoolean(KEY_COMPLETE, value)
                    if (value) remove(KEY_DONE_THREADS)
                }
            }

        override fun isThreadDone(threadId: Long) = threadId.toString() in done

        override fun markThreadDone(threadId: Long) {
            if (isStale()) return
            done.add(threadId.toString())
            prefs.edit { putStringSet(KEY_DONE_THREADS, HashSet(done)) }
        }
    }
}
