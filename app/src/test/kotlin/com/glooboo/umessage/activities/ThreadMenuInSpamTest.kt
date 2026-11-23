package com.glooboo.umessage.activities

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_NUMBER
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.thread.ThreadMenuState
import com.glooboo.umessage.ui.thread.ThreadViewModel
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

/** J20: the real chat menu of a chat in Spam has no "Mark as spam" (it is held already) and no home screen shortcut. */
@RunWith(AndroidJUnit4::class)
class ThreadMenuInSpamTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<ThreadActivity>? = null

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        Robolectric.buildContentProvider(FakeTelephonyProvider::class.java)
            .create(ProviderInfo().apply { authority = "mms-sms" })
        FakeSmsMmsProvider.installSms()
        FakeSmsMmsProvider.installMms()
    }

    @After
    fun tearDown() {
        runCatching { scenario?.close() }
        resetSharedMessagesDatabase()
    }

    private fun chat(spam: Boolean) = offMainThread {
        app.conversationsDB.insertOrUpdate(
            Conversation(
                threadId = THREAD, snippet = "Hello", date = 1, read = true, title = SENDER, photoUri = "",
                isGroupConversation = false, phoneNumber = SENDER,
            ),
        )
        if (spam) app.conversationsDB.insertSpamThread(SpamThread(THREAD, SENDER, SpamCause.UNKNOWN, 0, 1L))
    }

    private fun menuAfterOpening(): ThreadMenuState {
        scenario = ActivityScenario.launch(
            Intent(app, ThreadActivity::class.java).putExtra(THREAD_ID, THREAD).putExtra(THREAD_NUMBER, SENDER),
        )
        var menu = ThreadMenuState()
        val until = System.currentTimeMillis() + SETTLE_MS
        while (System.currentTimeMillis() < until) {
            Thread.sleep(STEP_MS)
            ShadowLooper.idleMainLooper()
        }
        scenario!!.onActivity { menu = ViewModelProvider(it)[ThreadViewModel::class.java].menu }
        return menu
    }

    @Test
    fun aChatInSpamOffersNoMarkAsSpam() {
        chat(spam = true)
        assertThat(menuAfterOpening().reportSpam).isFalse()
    }

    @Test
    fun aChatInMessagesStillOffersMarkAsSpam() {
        chat(spam = false)
        assertThat(menuAfterOpening().reportSpam).isTrue()
    }

    private companion object {
        const val THREAD = 1L
        const val SENDER = "+15550001234"
        const val SETTLE_MS = 3_000L
        const val STEP_MS = 50L
    }
}
