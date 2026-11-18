package com.glooboo.umessage.ui.conversations

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Publish the count again after this many more chats. */
internal const val MARK_READ_STEP = 50

/** Publish the count again after this long, so a slow run still moves. */
internal const val MARK_READ_INTERVAL_MS = 100L

/** "Mark all read" is working: [done] of [total] chats are read. */
data class MarkReadProgress(val done: Int, val total: Int) {
    /** 0f..1f, for the bar. */
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/** Decides which counts reach the screen: every [step] chats or [intervalMs], and always the last one. */
internal class ProgressThrottle(
    startAt: Long,
    private val step: Int = MARK_READ_STEP,
    private val intervalMs: Long = MARK_READ_INTERVAL_MS,
) {
    private var lastDone = 0
    private var lastAt = startAt

    fun shouldPublish(done: Int, total: Int, now: Long): Boolean {
        val due = done >= total || done - lastDone >= step || now - lastAt >= intervalMs
        if (due) {
            lastDone = done
            lastAt = now
        }
        return due
    }
}

/**
 * Marks chats read one by one off the main thread and reports throttled [progress]. One run at a time: [start]
 * returns false while another is active. The loop is not cancelled with [scope], so a screen that goes away leaves
 * no half-finished run behind.
 */
internal class MarkReadRunner(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _progress = MutableStateFlow<MarkReadProgress?>(null)

    /** Null while nothing runs. */
    val progress: StateFlow<MarkReadProgress?> = _progress.asStateFlow()

    val isRunning: Boolean get() = _progress.value != null

    /**
     * Starts marking [ids] with [mark]; [onFinished] runs on [scope]'s dispatcher with whether a chat failed (the run
     * stops at the first failure). Call from the main thread. Returns false when nothing was started.
     */
    fun start(ids: List<Long>, mark: (Long) -> Unit, onFinished: (failed: Boolean) -> Unit): Boolean {
        if (ids.isEmpty() || isRunning) return false
        _progress.value = MarkReadProgress(0, ids.size)
        scope.launch {
            try {
                val failed = withContext(worker + NonCancellable) { runCatching { markAll(ids, mark) }.isFailure }
                onFinished(failed)
            } finally {
                _progress.value = null
            }
        }
        return true
    }

    private fun markAll(ids: List<Long>, mark: (Long) -> Unit) {
        val throttle = ProgressThrottle(clock())
        ids.forEachIndexed { index, id ->
            mark(id)
            val done = index + 1
            if (throttle.shouldPublish(done, ids.size, clock())) _progress.value = MarkReadProgress(done, ids.size)
        }
    }
}
