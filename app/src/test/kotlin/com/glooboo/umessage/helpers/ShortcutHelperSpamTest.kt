package com.glooboo.umessage.helpers

import android.app.Application
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.SpamThread
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

/**
 * Provider truth, step H (section 5, U9): a chat in Spam has no launcher or share shortcut. Its old one goes, a usage
 * report makes no new one, and where it cannot be told (the main thread) nothing is made. Fake numbers only.
 */
@RunWith(AndroidJUnit4::class)
class ShortcutHelperSpamTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val helper get() = ShortcutHelper(app)

    @Before
    fun setUp() {
        offMainThread {
            listOf(SPAM, SHOWN).forEach { thread ->
                app.conversationsDB.insertOrUpdate(
                    Conversation(
                        threadId = thread, snippet = "", date = 0, read = true, title = number(thread), photoUri = "",
                        isGroupConversation = false, phoneNumber = number(thread),
                    ),
                )
            }
            app.conversationsDB.insertSpamThread(SpamThread(SPAM, number(SPAM), SpamCause.UNKNOWN, spamSince = 1L))
        }
        push(SPAM)
        push(SHOWN)
    }

    @After
    fun tearDown() {
        ShortcutHelper.stopWatchingSpam()
        ShortcutHelper.updateShortcutsCall = { context, list -> ShortcutManagerCompat.updateShortcuts(context, list) }
        ShortcutHelper.inSpamRead = { context, thread -> context.conversationsDB.isInSpam(thread) }
        ShortcutManagerCompat.removeAllDynamicShortcuts(app)
        resetSharedMessagesDatabase()
    }

    private fun number(thread: Long) = "+155500012$thread"

    /** A shortcut as an earlier version (or the chat before it went to Spam) made it. */
    private fun push(thread: Long) = ShortcutManagerCompat.pushDynamicShortcut(app, shortcut(thread))

    private fun shortcut(thread: Long) = ShortcutInfoCompat.Builder(app, thread.toString())
        .setShortLabel(number(thread))
        .setLongLived(true)
        .setIntent(Intent(Intent.ACTION_VIEW).setPackage(app.packageName))
        .build()

    @Test
    fun anUpdateTheLauncherTurnsDownStillReturnsThePublishedShortcut() {
        // Reviewer: a rate-limited update keeps the chat's shortcut, so the notification still carries it.
        ShortcutHelper.updateShortcutsCall = { _, _ -> false }

        val published = offMainThread { helper.publish(shortcut(SHOWN)) }

        assertThat(published?.id).isEqualTo(SHOWN.toString())
        assertThat(shortcutIds()).contains(SHOWN.toString())
    }

    @Test
    fun aSpamChatPublishesNothingAndGetsNullButWithTheFlagOffAsBefore() {
        assertThat(offMainThread { helper.publish(shortcut(SPAM)) }).isNull()

        ProviderTruthFlag.enabled = false
        assertThat(offMainThread { helper.publish(shortcut(SPAM)) }?.id).isEqualTo(SPAM.toString())
    }

    private fun shortcutIds() = ShortcutManagerCompat.getDynamicShortcuts(app).map { it.id }.toSet()

    @Test
    fun theSweepRemovesTheShortcutsOfSpamChatsOnly() {
        offMainThread { helper.removeSpamShortcuts() }

        assertThat(shortcutIds()).containsExactly(SHOWN.toString())
    }

    @Test
    fun aUsageReportForASpamChatRemovesItsShortcutAndMakesNone() {
        offMainThread { helper.reportReceiveMessageUsage(SPAM) }
        assertThat(shortcutIds()).containsExactly(SHOWN.toString())

        offMainThread { helper.reportSendMessageUsage(SPAM) }
        assertThat(shortcutIds()).containsExactly(SHOWN.toString())
    }

    @Test
    fun aFailedSpamReadRemovesTheShortcutAndMakesNone() {
        // H-20: the chat may be in Spam, so its old shortcut goes, as for a chat that is.
        ShortcutHelper.inSpamRead = { _, _ -> error("table gone") }

        offMainThread { helper.reportReceiveMessageUsage(SHOWN) }
        assertThat(shortcutIds()).containsExactly(SPAM.toString())

        assertThat(offMainThread { helper.publish(shortcut(SHOWN)) }).isNull()
        assertThat(shortcutIds()).containsExactly(SPAM.toString())
    }

    @Test
    fun onTheMainThreadNothingIsMadeAndNothingIsRemoved() {
        helper.reportReceiveMessageUsage(SPAM)
        helper.removeSpamShortcuts()

        assertThat(shortcutIds()).containsExactly(SPAM.toString(), SHOWN.toString())
    }

    @Test
    fun theWatchStartedAtAppStartSweepsAtOnce() {
        ShortcutHelper.watchSpam(app)
        // A second start does not start a second watch.
        ShortcutHelper.watchSpam(app)

        val deadline = System.currentTimeMillis() + WAIT_MS
        while (shortcutIds().size > 1 && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)

        assertThat(shortcutIds()).containsExactly(SHOWN.toString())
    }

    @Test
    fun withTheFlagOffTheSweepLeavesEveryShortcut() {
        ProviderTruthFlag.enabled = false

        offMainThread { helper.removeSpamShortcuts() }

        assertThat(shortcutIds()).containsExactly(SPAM.toString(), SHOWN.toString())
    }

    private companion object {
        const val SPAM = 81L
        const val SHOWN = 82L
        const val WAIT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
