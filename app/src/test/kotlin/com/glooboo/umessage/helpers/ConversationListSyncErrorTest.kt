package com.glooboo.umessage.helpers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.glooboo.umessage.testing.resetSharedMessagesDatabase

/** PF-B7: an Error (out of memory, stack overflow) in one refresh must not stop every later refresh. */
@RunWith(AndroidJUnit4::class)
class ConversationListSyncErrorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        worker.shutdownNow()
        resetSharedMessagesDatabase()
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val noImport = Executor { }

    private fun idle() { worker.submit {}.get(30, TimeUnit.SECONDS) }

    @Test
    fun outOfMemoryInOneRefreshStillAllowsTheNext() {
        var calls = 0
        val sync = ConversationListSync(
            context, worker, { _, _ -> if (calls++ == 0) throw OutOfMemoryError("test") }, noImport, {},
        )
        sync.requestRepublish()
        idle()
        assertThat(calls).isEqualTo(1)
        sync.requestRepublish()
        idle()
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun anyOtherErrorEndsTheJobButFreesTheSync() {
        var calls = 0
        val sync = ConversationListSync(
            context, worker, { _, _ -> if (calls++ == 0) throw StackOverflowError() }, noImport, {},
        )
        sync.requestRepublish()
        idle()
        sync.requestRepublish()
        idle()
        assertThat(calls).isEqualTo(2)
    }
}
