package com.glooboo.umessage.ui.spam

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.receivers.SmsReceiver
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.spam.data.DefaultSpamRepository
import com.glooboo.umessage.spam.data.PendingSmsEntity
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.data.SpamPlatform
import com.glooboo.umessage.spam.data.SpamSettingsStore
import com.glooboo.umessage.spam.engine.ContactChecker
import com.glooboo.umessage.spam.engine.DefaultSpamFilter
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.SmsPdus
import com.glooboo.umessage.testing.SpamTestWiring
import com.glooboo.umessage.ui.conversations.ConversationsEvent
import com.glooboo.umessage.ui.conversations.ConversationsViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.Collections

/**
 * CM-06: "Block number" adds a uMessage block rule instead of a system block-list entry, so later texts from the
 * number land in Blocked messages (and can be restored) rather than being thrown away by Android.
 */
@RunWith(AndroidJUnit4::class)
class SenderBlockingTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var telephony: FakeTelephonyProvider
    private lateinit var db: SpamDatabase
    private lateinit var repo: DefaultSpamRepository
    private val scope = CoroutineScope(Job() + Dispatchers.Main)

    private object NoContacts : ContactChecker {
        override fun isContact(address: String) = address == CONTACT
    }

    private object NoPlatform : SpamPlatform {
        override fun notifyBlocked(message: BlockedMessage) = Unit
        override fun deliverRestored(pending: PendingSmsEntity): Long? = 1L
    }

    @Before
    fun setUp() {
        telephony = FakeTelephonyProvider.install()
        db = SpamTestWiring.freshSpamDatabase(app)
        SpamTestWiring.resetPendingLeases()
        val normalizer = NumberNormalizer { "US" }
        repo = DefaultSpamRepository(NoPlatform, db, SpamSettingsStore(app), normalizer)
        // Unknown senders are allowed, so only a rule can send a text to Blocked.
        runBlocking { repo.updateSettings { it.copy(mode = UnknownSenderMode.OFF) } }
        val filter = DefaultSpamFilter(repo::currentRules, repo::currentSettings, NoContacts, normalizer)
        UMessage.repositoryFactory = { repo }
        UMessage.filterFactory = { filter }
    }

    @After
    fun tearDown() {
        scope.cancel()
        SpamTestWiring.uninstall()
        SpamTestWiring.clearSpamDatabase()
    }

    @Test
    fun blockFromConversationList_addsUMessageRuleAndLeavesSystemListAlone() {
        val vm = ConversationsViewModel(app)
        val events = Collections.synchronizedList(mutableListOf<ConversationsEvent>())
        scope.launch { vm.events.collect { events += it } }
        vm.submit(listOf(conversation(1, SENDER)), cached = false, firstRun = false)
        awaitUntil { vm.state.value.rows.isNotEmpty() }

        vm.startSelect(1)
        vm.blockSelected()
        awaitUntil { events.any { it is ConversationsEvent.Blocked || it is ConversationsEvent.Failed } }

        assertThat(events.filterIsInstance<ConversationsEvent.Blocked>().map { it.count }).containsExactly(1)
        assertThat(blockRules().map { it.type }).containsExactly(RuleType.NUMBER)
        assertThat(telephony.systemBlockInserts).isEqualTo(0)

        receive(SENDER, "Hello again")

        assertThat(telephony.smsRows()).isEmpty()
        val blocked = db.spamDao().getAllBlocked()
        assertThat(blocked.map { it.body }).containsExactly("Hello again")
        assertThat(blocked.single().reason).isEqualTo(DecisionReason.BLOCK_RULE)
    }

    @Test
    fun blockFromThread_sendsLaterTextsToBlocked() = runBlocking<Unit> {
        SenderBlocking.block(repo, listOf(SENDER))

        assertThat(blockRules().map { it.type to it.kind }).containsExactly(RuleType.NUMBER to ListKind.BLOCK)
        assertThat(telephony.systemBlockInserts).isEqualTo(0)

        receive(SENDER, "Still there")

        assertThat(telephony.smsRows()).isEmpty()
        assertThat(db.spamDao().getAllBlocked().single().reason).isEqualTo(DecisionReason.BLOCK_RULE)
    }

    @Test
    fun blockAgain_addsNoSecondRule() = runBlocking<Unit> {
        SenderBlocking.block(repo, listOf(SENDER))
        val second = SenderBlocking.block(repo, listOf("(202) 555-0101"))

        assertThat(second.isEmpty).isTrue()
        assertThat(blockRules()).hasSize(1)
    }

    @Test
    fun block_removesNotSpamAllowRuleAndUndoPutsItBack() = runBlocking<Unit> {
        repo.addRule(SpamRule(kind = ListKind.ALLOW, type = RuleType.NUMBER, pattern = SENDER, note = "Not spam"))

        val change = SenderBlocking.block(repo, listOf(SENDER))
        assertThat(rules().map { it.kind }).containsExactly(ListKind.BLOCK)

        SenderBlocking.undo(repo, change)
        assertThat(rules().map { it.kind }).containsExactly(ListKind.ALLOW)
    }

    @Test
    fun unblock_removesTheRuleSoTextsReachTheInboxAgain() = runBlocking<Unit> {
        SenderBlocking.block(repo, listOf(SENDER))
        assertThat(SenderBlocking.isBlocked(repo.currentRules(), SENDER)).isTrue()

        val removed = SenderBlocking.unblock(repo, SENDER)

        assertThat(removed).hasSize(1)
        assertThat(blockRules()).isEmpty()
        assertThat(SenderBlocking.isBlocked(repo.currentRules(), SENDER)).isFalse()
        assertThat(telephony.systemBlockInserts).isEqualTo(0)

        receive(SENDER, "Hello friend")

        assertThat(telephony.smsRows().map { it["body"] }).containsExactly("Hello friend")
        assertThat(db.spamDao().getAllBlocked()).isEmpty()
    }

    @Test
    fun unblock_leavesOtherSendersRulesAlone() = runBlocking<Unit> {
        SenderBlocking.block(repo, listOf(SENDER, OTHER))

        SenderBlocking.unblock(repo, SENDER)

        assertThat(SenderBlocking.isBlocked(repo.currentRules(), OTHER)).isTrue()
        assertThat(blockRules()).hasSize(1)
    }

    @Test
    fun afterUnblock_unknownSenderInSmartModeIsStillFiltered() = runBlocking<Unit> {
        repo.updateSettings { it.copy(mode = UnknownSenderMode.SMART) }
        SenderBlocking.block(repo, listOf(SENDER))
        SenderBlocking.unblock(repo, SENDER)

        assertThat(SenderBlocking.reachesInbox(UMessage.filter(app), SENDER, 1)).isFalse()
        receive(SENDER, "Hello friend")
        assertThat(db.spamDao().getAllBlocked().single().reason).isEqualTo(DecisionReason.UNKNOWN_SENDER)
    }

    @Test
    fun afterUnblock_contactOrOffModeReachesInbox() = runBlocking<Unit> {
        repo.updateSettings { it.copy(mode = UnknownSenderMode.SMART) }
        assertThat(SenderBlocking.reachesInbox(UMessage.filter(app), CONTACT, 1)).isTrue()

        repo.updateSettings { it.copy(mode = UnknownSenderMode.OFF) }
        assertThat(SenderBlocking.reachesInbox(UMessage.filter(app), SENDER, 1)).isTrue()
    }

    @Test
    fun alwaysAllow_letsAnUnknownSenderThroughAndUndoRemovesIt() = runBlocking<Unit> {
        repo.updateSettings { it.copy(mode = UnknownSenderMode.STRICT) }
        SenderBlocking.block(repo, listOf(SENDER))
        SenderBlocking.unblock(repo, SENDER)

        val id = SenderBlocking.allow(repo, SENDER)

        assertThat(rules().map { it.kind to it.type }).containsExactly(ListKind.ALLOW to RuleType.NUMBER)
        assertThat(SenderBlocking.reachesInbox(UMessage.filter(app), SENDER, 1)).isTrue()
        receive(SENDER, "Hello friend")
        assertThat(telephony.smsRows().map { it["body"] }).containsExactly("Hello friend")

        repo.removeRule(id)
        assertThat(SenderBlocking.reachesInbox(UMessage.filter(app), SENDER, 1)).isFalse()
    }

    private fun rules() = repo.currentRules()

    private fun blockRules() = rules().filter { it.kind == ListKind.BLOCK }

    private fun receive(sender: String, body: String) {
        SmsReceiver().handle(app, SmsPdus.deliverIntent(SmsPdus.deliverPdu(sender, body)))
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "condition not met" }
            Thread.sleep(10)
        }
    }

    private companion object {
        const val SENDER = "+12025550101"
        const val OTHER = "+12025550199"
        const val CONTACT = "+12025550142"

        fun conversation(id: Long, number: String) = Conversation(
            threadId = id, snippet = "hi", date = 100, read = true, title = number, photoUri = "",
            isGroupConversation = false, phoneNumber = number,
        )
    }
}
