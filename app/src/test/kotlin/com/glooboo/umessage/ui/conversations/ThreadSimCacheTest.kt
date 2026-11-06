package com.glooboo.umessage.ui.conversations

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.testing.CountingInboxProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** MC-18: the SIM chips no longer read every SMS each time the list opens. 2000 chats, 20000 messages. */
@RunWith(AndroidJUnit4::class)
class ThreadSimCacheTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var provider: CountingInboxProvider
    private lateinit var file: File

    @Before
    fun setUp() {
        provider = CountingInboxProvider.install()
        provider.seed(threads = 2000, messagesPerThread = 10) { thread -> if (thread % 2 == 0L) 2 else 1 }
        file = File(context.noBackupFilesDir, "thread_sims_test.txt").apply { delete() }
        provider.resetCounts()
    }

    @Test
    fun oneSimReadsNothing() {
        assertThat(ThreadSimCache(file).load(context, simCount = 1)).isEmpty()
        assertThat(provider.totalQueries()).isEqualTo(0)
    }

    @Test
    fun twoSimsReadEverythingOnceThenOnlyNewMessages() {
        val cache = ThreadSimCache(file)
        val first = cache.load(context, simCount = 2)
        val firstRows = provider.rows.get()
        assertThat(first).hasSize(2000)
        assertThat(first[4L]).isEqualTo(2)
        assertThat(first[5L]).isEqualTo(1)
        provider.resetCounts()

        cache.load(context, simCount = 2)
        val secondRows = provider.rows.get()
        provider.resetCounts()

        // A restart: a new cache over the same file reads only what is newer than the saved date.
        provider.receive(
            id = 100_000,
            thread = 5,
            body = "Sent on SIM 2",
            dateMs = CountingInboxProvider.BASE_MS + 999_000_000,
            subId = 2,
        )
        val restarted = ThreadSimCache(file).load(context, simCount = 2)
        val restartRows = provider.rows.get()

        println("MC-18 rows read: first open $firstRows, next open $secondRows, after restart $restartRows")
        assertThat(firstRows).isEqualTo(20_000)
        assertThat(secondRows).isAtMost(1)
        assertThat(restartRows).isAtMost(2)
        assertThat(provider.queryCount("sms")).isEqualTo(1)
        assertThat(restarted).hasSize(2000)
        assertThat(restarted[5L]).isEqualTo(2)
    }

    @Test
    fun aDamagedFileMeansOneFullRead() {
        file.writeText("not a date\n1 x\n")
        assertThat(ThreadSimCache(file).load(context, simCount = 2)).hasSize(2000)
        assertThat(provider.rows.get()).isEqualTo(20_000)
    }
}
