package com.glooboo.umessage.receivers

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** What [MmsReceiver] relies on to keep the broadcast open until mmslib's worker thread is done. */
class WorkerThreadTrackerTest {

    @Test
    fun await_returnsOnlyAfterTheMarkedThreadFinished() {
        val tracker = WorkerThreadTracker()
        val finished = AtomicBoolean(false)
        Thread {
            tracker.mark()
            Thread.sleep(300)
            tracker.mark() // later hooks on the same thread change nothing
            finished.set(true)
        }.start()

        assertThat(tracker.await(5_000)).isTrue()
        assertThat(finished.get()).isTrue()
    }

    @Test
    fun await_waitsForAMarkThatComesLate() {
        val tracker = WorkerThreadTracker()
        val finished = AtomicBoolean(false)
        Thread {
            Thread.sleep(200) // file read and parse before the first hook
            tracker.mark()
            finished.set(true)
        }.start()

        assertThat(tracker.await(5_000)).isTrue()
        assertThat(finished.get()).isTrue()
    }

    @Test
    fun await_givesUpWhenNothingEverMarks() {
        val start = System.nanoTime()

        assertThat(WorkerThreadTracker().await(100)).isFalse()
        assertThat((System.nanoTime() - start) / 1_000_000).isAtLeast(90L)
    }

    @Test
    fun await_givesUpWhenTheWorkerRunsTooLong() {
        val tracker = WorkerThreadTracker()
        val release = Object()
        val worker = Thread {
            tracker.mark()
            synchronized(release) { release.wait(5_000) }
        }
        worker.start()

        assertThat(tracker.await(200)).isFalse()
        synchronized(release) { release.notifyAll() }
        worker.join()
    }

    @Test
    fun await_onTheWorkerItselfDoesNotDeadlock() {
        val tracker = WorkerThreadTracker()
        tracker.mark()

        assertThat(tracker.await(1_000)).isTrue()
    }
}
