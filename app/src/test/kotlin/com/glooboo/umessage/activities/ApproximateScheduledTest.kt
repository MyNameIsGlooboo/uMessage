package com.glooboo.umessage.activities

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.Telephony.Sms.MESSAGE_TYPE_QUEUED
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_NUMBER
import com.glooboo.umessage.messaging.cancelScheduleSendPendingIntent
import com.glooboo.umessage.messaging.scheduleMessage
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/** RM-27: a message scheduled with an inexact alarm ("Schedule anyway") says its time is approximate. */
@RunWith(AndroidJUnit4::class)
class ApproximateScheduledTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val approximate get() = app.getString(R.string.thread_scheduled_approximate)
    private var scenario: ActivityScenario<ThreadActivity>? = null

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        Robolectric.buildContentProvider(FakeTelephonyProvider::class.java)
            .create(ProviderInfo().apply { authority = "mms-sms" })
        FakeSmsMmsProvider.installSms()
        FakeSmsMmsProvider.installMms()
        app.config.inexactScheduledMessages = emptySet()
    }

    @After
    fun tearDown() {
        scenario?.close()
        resetSharedMessagesDatabase()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    private fun scheduled(id: Long, body: String) = Message(
        id = id, body = body, type = MESSAGE_TYPE_QUEUED, status = -1,
        participants = arrayListOf(SimpleContact(1, 1, "111", "", arrayListOf(PhoneNumber("111", 0, "", "111")), ArrayList(), ArrayList())),
        date = ((System.currentTimeMillis() + 86_400_000L) / 1000).toInt(),
        read = true, threadId = THREAD, isMMS = false, attachment = null,
        senderPhoneNumber = "", senderName = "", senderPhotoUri = "", subscriptionId = -1, isScheduled = true,
    )

    @Test
    fun schedulingRecordsWhetherTheAlarmIsExact() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertThat(app.scheduleMessage(scheduled(SCHEDULED_ID, "x"), warnIfInexact = false)).isFalse()
        assertThat(app.config.inexactScheduledMessages).containsExactly(SCHEDULED_ID.toString())

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertThat(app.scheduleMessage(scheduled(SCHEDULED_ID, "x"), warnIfInexact = false)).isTrue()
        assertThat(app.config.inexactScheduledMessages).isEmpty()

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        app.scheduleMessage(scheduled(SCHEDULED_ID, "x"), warnIfInexact = false)
        app.cancelScheduleSendPendingIntent(SCHEDULED_ID)
        assertThat(app.config.inexactScheduledMessages).isEmpty()
    }

    @Test
    fun onlyTheInexactMessageSaysApproximate() {
        offMainThread {
            app.messagesDB.insertOrUpdate(scheduled(SCHEDULED_ID, "Inexact one"))
            app.messagesDB.insertOrUpdate(scheduled(SCHEDULED_ID + 1, "Exact one"))
        }
        app.config.inexactScheduledMessages = setOf(SCHEDULED_ID.toString())

        scenario = ActivityScenario.launch(
            Intent(app, ThreadActivity::class.java).putExtra(THREAD_ID, THREAD).putExtra(THREAD_NUMBER, "111")
        )

        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithText("Exact one").fetchSemanticsNodes().isNotEmpty() }
        assertThat(compose.onAllNodesWithText(approximate, useUnmergedTree = true).fetchSemanticsNodes()).hasSize(1)
    }

    private companion object {
        const val THREAD = 1L // the id the fake thread lookup gives "111"
        const val SCHEDULED_ID = 1_700_000_000_500L
        const val TIMEOUT = 10_000L
    }
}
