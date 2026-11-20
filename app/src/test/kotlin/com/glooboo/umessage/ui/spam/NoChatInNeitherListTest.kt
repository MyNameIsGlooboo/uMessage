package com.glooboo.umessage.ui.spam

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.interfaces.ConversationsDao
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.ChatSorter
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.SortResult
import com.glooboo.umessage.spam.SortingWorld
import com.glooboo.umessage.spam.SortingWorld.Companion.chat
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Provider truth, step C ("done when": no way to end with a chat in neither list). After every action of the Messages
 * list, the Spam tab and the chat line (Block, Report spam, Unblock, Not spam, Keep, Undo, a group switched off and on,
 * the filter off and on) and the sort that follows it, every cached chat is in exactly one place: the Messages list
 * (or Archived) or the Spam list. Numbers are made up.
 */
@RunWith(AndroidJUnit4::class)
class NoChatInNeitherListTest {

    private lateinit var db: MessagesDatabase
    private lateinit var dao: ConversationsDao
    private val world = SortingWorld()
    private val sorter by lazy {
        ChatSorter(db, snapshot = { world.judge.snapshot() }, contactsReadable = { world.contactsReadable })
    }

    private val unknown = "+15550001240"
    private val member = "+15550001241"
    private val contact = "+15550001242"
    private val blocked = "+15550001243"
    private val archived = "+15550001244"

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).allowMainThreadQueries().build()
        dao = db.ConversationsDao()
        listOf(
            chat(1, unknown), chat(2, member), chat(3, contact), chat(4, blocked), chat(5, archived, archived = true),
            chat(6, "+15550001245", group = true),
        ).forEach { dao.insertOrUpdate(it) }
        world.contacts += contact
        world.contacts += archived
        world.member(AllowGroup.CODE, member)
        world.block(blocked)
        sort()
    }

    @After
    fun tearDown() = db.close()

    private fun sort() {
        assertThat(sorter.recompute()).isInstanceOf(SortResult.Applied::class.java)
        assertEveryChatInExactlyOnePlace()
    }

    private fun assertEveryChatInExactlyOnePlace() {
        val messages = (dao.getMessagesList() + dao.getArchivedList()).map { it.threadId }
        val spam = SpamChatsSource.load(dao).map { it.threadId }
        val all = dao.getAllRows().map { it.threadId }
        assertThat(messages.intersect(spam.toSet())).isEmpty()
        assertThat((messages + spam).sorted()).isEqualTo(all.sorted())
    }

    private fun inSpam(threadId: Long) = SpamChatsSource.load(dao).any { it.threadId == threadId }

    private fun removeRules(pattern: String, kind: ListKind) =
        world.rules.removeAll { it.pattern == pattern && it.kind == kind }

    @Test
    fun theFirstSortPutsEveryChatInOnePlace() {
        assertThat(inSpam(1)).isTrue()
        assertThat(inSpam(4)).isTrue()
        assertThat(inSpam(2)).isFalse()
        assertThat(inSpam(3)).isFalse()
        assertThat(inSpam(6)).isFalse()
    }

    @Test
    fun blockFromMessagesAndItsUndo() {
        // Block on a code member: the membership goes, a block rule comes (SenderMoves.blockSenders).
        val removed = world.members.filter { it.address == member }
        world.members.removeAll(removed)
        world.block(member)
        sort()
        assertThat(SpamChatsSource.load(dao).single { it.threadId == 2L }.cause).isEqualTo(SpamCause.BLOCKED)

        // Undo: the rule goes, the membership comes back.
        removeRules(member, ListKind.BLOCK)
        world.members.addAll(removed)
        sort()
        assertThat(inSpam(2)).isFalse()
    }

    @Test
    fun unblockOfAnUnknownSenderLeavesTheChatInSpamAsUnknown() {
        removeRules(blocked, ListKind.BLOCK)
        sort()
        assertThat(SpamChatsSource.load(dao).single { it.threadId == 4L }.cause).isEqualTo(SpamCause.UNKNOWN)

        // Then "Not spam" from the snackbar: back in Messages.
        world.allow(blocked)
        sort()
        assertThat(inSpam(4)).isFalse()
    }

    @Test
    fun notSpamAndItsUndo() {
        world.allow(unknown)
        sort()
        assertThat(inSpam(1)).isFalse()

        removeRules(unknown, ListKind.ALLOW)
        sort()
        assertThat(inSpam(1)).isTrue()
    }

    @Test
    fun blockOfAContactInMessages() {
        world.block(contact)
        sort()
        assertThat(inSpam(3)).isTrue()
    }

    @Test
    fun blockOfAnArchivedChatMovesItOutOfArchivedIntoSpam() {
        world.block(archived)
        sort()
        assertThat(inSpam(5)).isTrue()
        assertThat(dao.getArchivedList().map { it.threadId }).doesNotContain(5L)
    }

    @Test
    fun theSwitchesMoveChatsButNeverLoseOne() {
        world.settings = world.settings.copy(codeGroupOn = false)
        sort()
        assertThat(inSpam(2)).isTrue()
        world.settings = world.settings.copy(codeGroupOn = true)
        sort()
        world.settings = world.settings.copy(filterOn = false)
        sort()
        // Filter off: only blocked senders are listed in Spam.
        assertThat(SpamChatsSource.load(dao).map { it.threadId }).containsExactly(4L)
        world.settings = world.settings.copy(filterOn = true)
        sort()
    }

    @Test
    fun aSpamRowOfAnotherSenderNeverHidesAChat() {
        // S9: a row left by an older store for this thread id names another sender; the chat must still show.
        dao.deleteSpamThread(3)
        dao.insertSpamThread(SpamThread(3, "+15550009999", SpamCause.UNKNOWN, spamSince = 1L))
        assertEveryChatInExactlyOnePlace()
        assertThat(inSpam(3)).isFalse()
    }
}
