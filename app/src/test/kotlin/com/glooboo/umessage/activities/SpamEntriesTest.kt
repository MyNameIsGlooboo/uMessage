package com.glooboo.umessage.activities

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.helpers.MainTabLinks
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.data.BlockedNotifier
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.log.restoreCandidates
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Provider truth, step H fix round: the Settings "Spam" row opens the Spam tab and clears the Spam notices (H-10), the
 * decision log offers no old restore card (H-14), and the share targets leave the Spam chats out. Fake numbers only.
 */
@RunWith(AndroidJUnit4::class)
class SpamEntriesTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val held = BlockedMessage(
        id = 1, address = "+15550001234", body = "Cheap loans", subscriptionId = 0, isMms = false,
        receivedAt = 1_000L, reason = DecisionReason.UNKNOWN_SENDER,
    )

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    @Test
    fun theSettingsRowOpensTheSpamTabAndClearsTheSpamNotices() {
        BlockedNotifier.show(app, held, chatId = 5L)
        assertThat(shadowOf(manager).allNotifications).isNotEmpty()

        app.openSpamTab()

        val opened = shadowOf(app).nextStartedActivity
        assertThat(opened.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(opened.getStringExtra(MainTabLinks.EXTRA_OPEN_TAB)).isEqualTo(MainTabLinks.TAB_BLOCKED)
        assertThat(shadowOf(manager).allNotifications).isEmpty()
    }

    @Test
    fun theDecisionLogOffersNoOldRestoreCardWithTheFlagOn() {
        assertThat(restoreCandidates(listOf(held))).isEmpty()

        ProviderTruthFlag.enabled = false
        assertThat(restoreCandidates(listOf(held))).containsExactly(held)
    }

    @Test
    fun theShareTargetsLeaveOutTheSpamChat() {
        offMainThread {
            listOf(SPAM to "+15550001234", SHOWN to "+15550001235").forEach { (thread, number) ->
                app.conversationsDB.insertOrUpdate(Conversation(thread, "", 0, true, number, "", false, number))
            }
            app.conversationsDB.insertSpamThread(SpamThread(SPAM, "+15550001234", SpamCause.UNKNOWN, spamSince = 1L))
        }

        assertThat(offMainThread { app.shareTargetChats() }.map { it.threadId }).containsExactly(SHOWN)

        ProviderTruthFlag.enabled = false
        assertThat(offMainThread { app.shareTargetChats() }.map { it.threadId }).containsExactly(SPAM, SHOWN)
    }

    private companion object {
        const val SPAM = 121L
        const val SHOWN = 122L
    }
}
