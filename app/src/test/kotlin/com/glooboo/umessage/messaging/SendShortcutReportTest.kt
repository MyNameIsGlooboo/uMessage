package com.glooboo.umessage.messaging

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.helpers.ShortcutHelper
import com.glooboo.umessage.testing.FakeMessageStore
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.testing.joinAppBackgroundThreads
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * M-P1: the shortcut report runs after the SMS was handed to the system. When the shortcut service throws there, the
 * send still counts as handed off and nothing reports it as "not sent". Fake numbers only.
 */
@RunWith(AndroidJUnit4::class)
class SendShortcutReportTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ShadowRecordingSmsManager.reset()
        FakeMessageStore.install()
        app.config.sendGroupMessageMMS = false
        app.config.sendLongAsMmsAfterParts = LongTextMms.NEVER
    }

    @After
    fun tearDown() {
        ShortcutHelper.usageReportFault = null
        joinAppBackgroundThreads()
        resetSharedMessagesDatabase()
    }

    @Test
    fun aThrowingShortcutReportLeavesTheSendHandedOff() {
        val reported = CountDownLatch(1)
        ShortcutHelper.usageReportFault = {
            reported.countDown()
            throw UnsupportedOperationException("shortcut service died")
        }
        var failed = false

        val outcome = app.sendMessageDetailed(
            "Hello", listOf("+15550001234"), subId = null, attachments = emptyList(), onFailure = { failed = true },
        )

        assertThat(reported.await(10, TimeUnit.SECONDS)).isTrue()
        joinAppBackgroundThreads()
        assertThat(outcome.handedOff).isTrue()
        assertThat(failed).isFalse()
        assertThat(ShadowRecordingSmsManager.textSends.map { it.destination }).containsExactly("+15550001234")
    }

    @Test
    fun aRefusedShortcutAccessStillLeavesTheSendHandedOff() {
        ShortcutHelper.usageReportFault = { throw SecurityException("no shortcut access") }

        val outcome = app.sendMessageCompat("Hello", listOf("+15550001234"), subId = null, attachments = emptyList())

        joinAppBackgroundThreads()
        assertThat(outcome).isTrue()
    }
}
