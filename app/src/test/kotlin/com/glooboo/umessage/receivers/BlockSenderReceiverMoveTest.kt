package com.glooboo.umessage.receivers

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.AllowGroupMember
import com.glooboo.umessage.spam.BlockReceipt
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.MemberSource
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.spam.FakeSpamRepository
import com.glooboo.umessage.ui.spam.RuleNotes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Provider truth, step C (4.1: U5, M-SP3): Block from a notification moves the chat to Spam (the sender's automatic
 * allow rule and group membership go, the chat is sorted again) and its Undo puts the rules, the memberships and the
 * unread state back, also from the intent alone. Numbers are made up.
 */
@RunWith(AndroidJUnit4::class)
class BlockSenderReceiverMoveTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val repository = FakeSpamRepository(initialBlocked = emptyList(), initialRules = emptyList())
    private val receiver = BlockSenderReceiver()
    private val sorted = mutableListOf<Long>()
    private val sortBefore = BlockSenderReceiver.sortThread

    @Before
    fun setUp() {
        BlockSenderReceiver.sortThread = { _, id -> sorted += id }
    }

    @After
    fun tearDown() {
        BlockSenderReceiver.sortThread = sortBefore
        resetSharedMessagesDatabase()
    }

    private fun rules() = runBlocking { repository.rules.first() }
    private fun members() = runBlocking { repository.groupMembers.first() }

    private fun cacheChat(read: Boolean) = offMainThread {
        context.conversationsDB.insertOrUpdate(
            Conversation(
                threadId = THREAD, snippet = "hi", date = 1, read = read, title = SENDER, photoUri = "",
                isGroupConversation = false, phoneNumber = SENDER,
            ),
        )
    }

    /** As the receiver runs it: off the main thread (it reads the cached chat). */
    private fun handle(intent: Intent) = offMainThread { runBlocking { receiver.handle(context, repository, intent) } }

    @Test
    fun blockMovesTheChatAndUndoPutsEverythingBack() = runBlocking {
        cacheChat(read = false)
        val wordAllow = repository.addRule(
            SpamRule(
                kind = ListKind.ALLOW, type = RuleType.NUMBER, pattern = SENDER, note = RuleNotes.ALLOWED_FROM_KEYWORD,
            ),
        )
        repository.joinGroup(AllowGroup.WROTE, SENDER)
        manager.notify(THREAD.hashCode(), Notification.Builder(context, "c").setSmallIcon(1).build())

        handle(BlockSenderReceiver.blockIntent(context, SENDER, THREAD))

        assertThat(rules().map { it.kind }).containsExactly(ListKind.BLOCK)
        assertThat(members()).isEmpty()
        assertThat(sorted).containsExactly(THREAD)
        val notice = shadowOf(manager).allNotifications.single()
        assertThat(notice.channelId).isEqualTo(BlockSenderReceiver.CHANNEL_ID)

        // The Undo button's intent, as the notification holds it.
        val undo = shadowOf(notice.actions.single().actionIntent).savedIntent
        assertThat(undo.getStringExtra(BlockSenderReceiver.EXTRA_UNDO)).isNotNull()
        assertThat(BlockUndoCodec.decode(undo.getStringExtra(BlockSenderReceiver.EXTRA_UNDO)!!)!!.wasUnread).isTrue()
        handle(undo)

        assertThat(rules().map { it.id }).containsExactly(wordAllow)
        assertThat(members().map { it.group }).containsExactly(AllowGroup.WROTE)
        assertThat(sorted).containsExactly(THREAD, THREAD)
        assertThat(shadowOf(manager).allNotifications).isEmpty()
    }

    @Test
    fun theUndoCodecKeepsTheWholeReceipt() {
        val receipt = BlockReceipt(
            address = SENDER,
            ruleId = 12L,
            removedRules = listOf(
                SpamRule(
                    id = 5, kind = ListKind.ALLOW, type = RuleType.NUMBER, pattern = SENDER,
                    note = RuleNotes.ALLOWED_FROM_KEYWORD, createdAt = 77L, hitCount = 3, lastHit = 99L,
                    acknowledged = true,
                ),
            ),
            removedMembers = listOf(
                AllowGroupMember(AllowGroup.CODE, SENDER, SENDER, 55L, MemberSource.HISTORY, acknowledged = true),
            ),
            stillAllowedBy = 8L,
        )
        val decoded = BlockUndoCodec.decode(BlockUndoCodec.encode(BlockUndo(receipt, wasUnread = true)))

        assertThat(decoded).isEqualTo(BlockUndo(receipt, wasUnread = true))
        assertThat(BlockUndoCodec.decode("not json")).isNull()
    }

    @Test
    fun anOldUndoIntentStillRemovesItsRules() = runBlocking {
        val id = repository.addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = SENDER))
        val undo = Intent(BlockSenderReceiver.ACTION_UNDO)
            .putExtra(THREAD_ID, THREAD)
            .putExtra(BlockSenderReceiver.EXTRA_RULE_IDS, longArrayOf(id))

        receiver.handle(context, repository, undo)

        assertThat(rules()).isEmpty()
    }

    private companion object {
        const val SENDER = "+15550001234"
        const val THREAD = 42L
    }
}
