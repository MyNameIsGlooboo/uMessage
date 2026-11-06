package com.glooboo.umessage.testing

import com.glooboo.umessage.databases.MessagesDatabase
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.ThreadPoolExecutor

/**
 * Closes and forgets the app's shared MessagesDatabase. Robolectric keeps static fields between the tests of one
 * sandbox but gives each test a fresh data folder, so a test that goes through `context.messagesDB` must call this
 * when it ends, or the next test would get a database whose file is gone.
 *
 * It first waits for the app's background threads to finish. The app (Fossify's `ensureBackgroundThread`) runs
 * database work on plain `Thread`s that Robolectric neither tracks nor idles, and some are still running when a test
 * ends: a send reporting shortcut usage, a scheduled message being cancelled and the thread reloaded, a
 * notification building its shortcut. Such a thread that touched `messagesDB` after the reset built a new shared
 * database from the finished test's context; the next test then used that database, whose folder Robolectric had
 * deleted, its background loads failed and its `waitUntil` timed out (HeldScheduledMessageTest, in a full run only).
 */
fun resetSharedMessagesDatabase() {
    joinAppBackgroundThreads()
    val field = MessagesDatabase::class.java.getDeclaredField("db").apply { isAccessible = true }
    (field.get(null) as? MessagesDatabase)?.let { runCatching { it.close() } }
    field.set(null, null)
}

/** `Thread {}` gets the JVM's default name "Thread-N"; Room, coroutine and executor threads are named otherwise. */
// While such a thread runs a coroutine (runBlocking inside the work), the coroutine debug agent that tests run
// with renames it "Thread-N @coroutine#M": those must be waited for too, or the wait ends while they still run.
private val unnamedThread = Regex("Thread-[0-9]+( @coroutine#[0-9]+)?")

/**
 * True while EventBus is still delivering an event to an `ASYNC` subscriber (the thread screen's message refresh)
 * on its own thread pool, whose idle threads stay alive and so cannot be joined.
 */
private fun eventBusBusy(): Boolean {
    // The executor is private in EventBus (its Kotlin view as a property does not compile), so read it reflectively.
    val executor = runCatching {
        EventBus::class.java.getDeclaredField("executorService").apply { isAccessible = true }.get(EventBus.getDefault())
    }.getOrNull() as? ThreadPoolExecutor ?: return false
    return executor.activeCount > 0 || executor.queue.isNotEmpty()
}

/**
 * Waits up to [timeoutMs] for every plain unnamed thread (what `ensureBackgroundThread` starts) to end, and for
 * EventBus's async deliveries (ThreadSendFlowTest: a refresh still running after the test ended, or after a
 * recreate, touched the database like the threads above). Threads started while waiting are waited for too.
 * Returns whether there was anything to wait for.
 */
fun joinAppBackgroundThreads(timeoutMs: Long = 10_000L): Boolean {
    var waited = false
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (true) {
        val running = Thread.getAllStackTraces().keys.filter {
            it !== Thread.currentThread() && it.isAlive && it.name.matches(unnamedThread)
        }
        val busBusy = eventBusBusy()
        if (running.isEmpty() && !busBusy) return waited
        waited = true
        val leftMs = (deadline - System.nanoTime()) / 1_000_000L
        if (leftMs <= 0) {
            System.err.println("Background work still running after ${timeoutMs} ms: ${running.map { it.name }}, eventBus=$busBusy")
            return true
        }
        if (running.isNotEmpty()) running.first().join(leftMs) else Thread.sleep(5)
    }
}
