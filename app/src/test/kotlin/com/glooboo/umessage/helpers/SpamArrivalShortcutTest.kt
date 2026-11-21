package com.glooboo.umessage.helpers

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.SpamThreads
import com.glooboo.umessage.spam.data.SpamPlacement
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

/**
 * Provider truth, step H fix round: H-3, a chat that enters Spam by an incoming SMS or MMS loses its launcher and
 * share shortcut at once; H-11, a notification of a chat whose shortcut is not published carries none and is logged
 * as POSTED_NO_SHORTCUT (M-P2: the post itself always stands). Fake numbers only.
 */
@RunWith(AndroidJUnit4::class)
class SpamArrivalShortcutTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        offMainThread {
            listOf(SMS_CHAT, MMS_CHAT).forEach { thread ->
                app.conversationsDB.insertOrUpdate(
                    Conversation(thread, "", 0, true, number(thread), "", false, number(thread)),
                )
            }
        }
        push(SMS_CHAT)
        push(MMS_CHAT)
    }

    @After
    fun tearDown() {
        ShortcutManagerCompat.removeAllDynamicShortcuts(app)
        resetSharedMessagesDatabase()
    }

    private fun number(thread: Long) = "+155500012$thread"

    private fun push(thread: Long) {
        val shortcut = ShortcutInfoCompat.Builder(app, thread.toString())
            .setShortLabel(number(thread))
            .setLongLived(true)
            .setIntent(Intent(Intent.ACTION_VIEW).setPackage(app.packageName))
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(app, shortcut)
    }

    private fun shortcutIds() = ShortcutManagerCompat.getDynamicShortcuts(app).map { it.id }.toSet()

    @Test
    fun anSmsPlacedInSpamRemovesTheChatsShortcut() {
        val result = offMainThread { SpamPlacement.place(app, SMS_CHAT, number(SMS_CHAT), SpamCause.UNKNOWN, 1_000L) }

        assertThat(result).isEqualTo(SpamPlacement.Result.PLACED)
        assertThat(shortcutIds()).containsExactly(MMS_CHAT.toString())
    }

    @Test
    fun anMmsNotedInSpamRemovesTheChatsShortcut() {
        offMainThread { SpamThreads.noteArrival(app, MMS_CHAT, number(MMS_CHAT), SpamCause.UNKNOWN, 1_000L) }

        assertThat(shortcutIds()).containsExactly(SMS_CHAT.toString())
    }

    @Test
    fun withTheFlagOffAPlacementLeavesTheShortcut() {
        ProviderTruthFlag.enabled = false

        offMainThread { SpamThreads.noteArrival(app, MMS_CHAT, number(MMS_CHAT), SpamCause.UNKNOWN, 1_000L) }

        assertThat(shortcutIds()).containsExactly(SMS_CHAT.toString(), MMS_CHAT.toString())
    }

    @Test
    fun aNotificationOfASpamChatIsPostedWithoutAShortcut() {
        offMainThread {
            val row = SpamThread(SMS_CHAT, number(SMS_CHAT), SpamCause.UNKNOWN, spamSince = 1L)
            app.conversationsDB.insertSpamThread(row)
        }
        ShortcutManagerCompat.removeAllDynamicShortcuts(app)
        ShadowLog.reset()

        offMainThread {
            NotificationHelper(app).postMessageNotification(
                messageId = 1L, isMms = false, address = number(SMS_CHAT), body = "See you at noon",
                threadId = SMS_CHAT, bitmap = null, sender = "Alex", notify = NotifyContext(),
            )
        }

        assertThat(ShadowLog.getLogsForTag(NotifyLog.TAG).single().msg).contains(" r=POSTED_NO_SHORTCUT ")
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertThat(shadowOf(manager).allNotifications.single().shortcutId).isNull()
        assertThat(shortcutIds()).isEmpty()
    }

    private companion object {
        const val SMS_CHAT = 21L
        const val MMS_CHAT = 22L
    }
}
