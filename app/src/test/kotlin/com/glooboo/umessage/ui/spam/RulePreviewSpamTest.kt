package com.glooboo.umessage.ui.spam

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.FakeMessageStore
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
 * Provider truth, step H (section 5): "Test this rule" reads the new model. The inbox part holds the texts of chats in
 * Messages only, so a Spam sender is never shown there as an example; the texts of Spam chats are their own part.
 * Fake numbers only.
 */
@RunWith(AndroidJUnit4::class)
class RulePreviewSpamTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var store: FakeMessageStore

    @Before
    fun setUp() {
        store = FakeMessageStore.install()
        store.addSms(SPAM_SENDER, "Win a prize", date = 2_000L, threadId = SPAM)
        store.addSms(FRIEND, "Dinner at eight", date = 1_000L, threadId = SHOWN)
        offMainThread {
            listOf(SPAM to SPAM_SENDER, SHOWN to FRIEND).forEach { (thread, number) ->
                app.conversationsDB.insertOrUpdate(
                    Conversation(thread, "", 0, true, number, "", false, number),
                )
            }
            app.conversationsDB.insertSpamThread(SpamThread(SPAM, SPAM_SENDER, SpamCause.UNKNOWN, spamSince = 1L))
        }
    }

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    private val source get() = AndroidRulePreviewSource(app)

    @Test
    fun theInboxPartHoldsMessagesOnlyAndSpamIsItsOwnPart() {
        val inbox = offMainThread { source.inbox(RuleDryRun.MAX_SCAN) }
        val spam = offMainThread { source.spam(RuleDryRun.MAX_SCAN) }

        assertThat(inbox.map { it.address }).containsExactly(FRIEND)
        assertThat(spam.map { it.address }).containsExactly(SPAM_SENDER)
    }

    @Test
    fun withTheFlagOffTheInboxIsWholeAndThereIsNoSpamPart() {
        ProviderTruthFlag.enabled = false

        val inbox = offMainThread { source.inbox(RuleDryRun.MAX_SCAN) }
        val spam = offMainThread { source.spam(RuleDryRun.MAX_SCAN) }

        assertThat(inbox.map { it.address }).containsExactly(SPAM_SENDER, FRIEND).inOrder()
        assertThat(spam).isEmpty()
    }

    @Test
    fun theDefaultSourceHasNoSpamPart() {
        assertThat(NoRulePreviewSource.spam(RuleDryRun.MAX_SCAN)).isEmpty()
    }

    private companion object {
        const val SPAM = 91L
        const val SHOWN = 92L
        const val SPAM_SENDER = "+15550001234"
        const val FRIEND = "+15550001235"
    }
}
