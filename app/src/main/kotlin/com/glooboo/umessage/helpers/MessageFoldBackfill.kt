package com.glooboo.umessage.helpers

import android.content.Context
import android.os.Process
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.interfaces.MessagesDao
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PF2: fills `messages.body_folded` for the rows that were cached before the column existed (database version 15
 * adds it empty so that the upgrade stays instant). It walks the table by row number, [BATCH] rows per transaction,
 * and is resumable by nature: it only selects rows whose folded body is still empty, so a stopped run continues
 * where it left off on the next start. Rows inserted afterwards carry their folded body already (Message.bodyFolded).
 *
 * Until a run has gone through the whole table in this process, [isComplete] is false and the search also reads the
 * still-empty rows the old way (see MessagesDao.searchMessages), so search stays correct while this runs.
 */
class MessageFoldBackfill(private val dao: MessagesDao, private val batchSize: Int = BATCH) {

    /**
     * Folds every row still empty, [batchSize] at a time, then marks the backfill complete. [isCancelled] is checked
     * between batches; a cancelled run leaves the flag as it was.
     */
    fun run(isCancelled: () -> Boolean = { false }): Boolean {
        var after = 0L
        var batch = dao.getUnfoldedBodies(after, batchSize)
        while (batch.isNotEmpty()) {
            if (isCancelled()) return false
            dao.updateBodiesFolded(batch)
            after = batch.last().rowid
            batch = dao.getUnfoldedBodies(after, batchSize)
        }
        complete.set(true)
        return true
    }

    companion object {
        private const val BATCH = 500

        private val complete = AtomicBoolean(false)
        private val running = AtomicBoolean(false)

        /** Whether this process has seen every old row folded; false until a run finishes. */
        val isComplete: Boolean get() = complete.get()

        /** For tests: forget that a run has finished. */
        internal fun reset() {
            complete.set(false)
            running.set(false)
        }

        /** Runs the backfill on the calling (background) thread unless it is done or running elsewhere. */
        fun runIfNeeded(context: Context) {
            if (complete.get() || !running.compareAndSet(false, true)) return
            val oldPriority = runCatching { Process.getThreadPriority(0) }.getOrNull()
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            try {
                MessageFoldBackfill(context.applicationContext.messagesDB).run()
            } finally {
                running.set(false)
                oldPriority?.let { runCatching { Process.setThreadPriority(it) } }
            }
        }
    }
}
