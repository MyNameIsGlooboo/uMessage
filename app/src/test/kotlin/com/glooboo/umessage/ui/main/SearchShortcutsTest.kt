package com.glooboo.umessage.ui.main

import android.content.Context
import android.provider.Telephony
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.helpers.MessageCategory
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageAttachment
import com.glooboo.umessage.models.RecycleBinMessage
import com.glooboo.umessage.ui.conversations.ConversationRow
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchShortcutsTest {

    private fun attachments(id: Long, types: List<String>) = MessageAttachment(
        id,
        "",
        ArrayList(types.mapIndexed { i, type -> Attachment(null, id, "content://mms/part/$id$i", type, 0, 0, "f") }),
    )

    private fun message(
        id: Long,
        body: String = "hello",
        mimetypes: List<String> = emptyList(),
        thread: Long = 1,
    ) = Message(
        id = id,
        body = body,
        type = Telephony.Sms.MESSAGE_TYPE_INBOX,
        status = 0,
        participants = ArrayList(),
        date = id.toInt(),
        read = true,
        threadId = thread,
        isMMS = mimetypes.isNotEmpty(),
        attachment = mimetypes.takeIf { it.isNotEmpty() }?.let { attachments(id, it) },
        senderPhoneNumber = "+15550100",
        senderName = "",
        senderPhotoUri = "",
        subscriptionId = 0,
    )

    private fun row(id: Long) = ConversationRow(
        threadId = id, title = "T$id", snippet = "hi", dateLabel = "", unread = false, unreadBadge = "",
        unreadCount = 0, pinned = false, muted = false, hasDraft = false, scheduled = false, simSlot = null,
        photoUri = "", isGroup = false, phoneNumber = "", category = MessageCategory.Other,
    )

    @Test
    fun imagesKeepOnlyMessagesWithAPicture() {
        val found = SearchShortcuts.images(
            listOf(
                message(1),
                message(2, mimetypes = listOf("image/jpeg")),
                message(3, mimetypes = listOf("video/mp4")),
                message(4, mimetypes = listOf("audio/amr", "IMAGE/PNG")),
            ),
        )
        assertThat(found.map { it.id }).containsExactly(2L, 4L).inOrder()
    }

    @Test
    fun linksKeepOnlyMessagesWithALink() {
        val found = SearchShortcuts.links(
            listOf(
                message(1, "call me"),
                message(2, "see https://one.test/a"),
                message(3, "ببین www.two.test/x"),
                message(4, "mail a@b.test or http://"),
            ),
        )
        assertThat(found.map { it.id }).containsExactly(2L, 3L).inOrder()
    }

    @Test
    fun theResultIsCappedAtTheLimit() {
        val many = (1L..(SearchShortcuts.LIMIT + EXTRA_ROWS)).map { message(it, "https://x.test/$it") }
        assertThat(SearchShortcuts.links(many)).hasSize(SearchShortcuts.LIMIT)
    }

    @Test
    fun theLookupTilesKeepTheConversationsTheyFound() {
        val rows = listOf(row(1), row(2), row(3))
        val threads = setOf(2L, 3L)
        InboxFilter.entries.filter { InboxFilterPolicy.needsLookup(it) }.forEach { filter ->
            assertThat(InboxFilterPolicy.filterRows(rows, filter, threads).map { it.threadId })
                .containsExactly(2L, 3L).inOrder()
        }
        assertThat(InboxFilterPolicy.filterRows(rows, InboxFilter.Starred)).isEmpty()
    }

    @Test
    fun onlyStarredImagesAndLinksListMessages() {
        assertThat(InboxFilter.entries.filter { InboxFilterPolicy.listsMessages(it) })
            .containsExactly(InboxFilter.Starred, InboxFilter.Images, InboxFilter.Links)
        assertThat(InboxFilterPolicy.needsLookup(InboxFilter.Unknown)).isTrue()
        assertThat(InboxFilterPolicy.needsLookup(InboxFilter.Unread)).isFalse()
    }

    @Test
    fun everyNewFilterHasATile() {
        listOf(InboxFilter.Unknown, InboxFilter.Starred, InboxFilter.Images, InboxFilter.Links).forEach {
            assertThat(filterTile(it)).isNotNull()
        }
    }

    @Test
    fun theQueriesAnswerQuicklyOnTwentyThousandRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.MessagesDao()
            val rows = (1L..ROWS).map { id ->
                when {
                    id % LINK_EVERY == 0L -> message(id, "see https://x.test/$id", thread = id % THREADS)
                    id % PICTURE_EVERY == 0L -> message(id, mimetypes = listOf("image/jpeg"), thread = id % THREADS)
                    else -> message(id, "plain text $id", thread = id % THREADS)
                }
            }
            dao.insertOrIgnoreAll(rows)
            dao.insertRecycleBinEntry(RecycleBinMessage(LINK_EVERY, false, 1))

            val start = System.nanoTime()
            val links = SearchShortcuts.links(dao.getLinkCandidates(SearchShortcuts.CANDIDATES))
            val pictures = SearchShortcuts.images(dao.getImageCandidates(SearchShortcuts.CANDIDATES))
            val millis = (System.nanoTime() - start) / NANOS_PER_MILLI

            assertThat(links).isNotEmpty()
            assertThat(links.size).isAtMost(SearchShortcuts.LIMIT)
            assertThat(links.map { it.id }).doesNotContain(LINK_EVERY)
            assertThat(pictures).isNotEmpty()
            assertThat(pictures.map { it.id }).isInOrder(compareByDescending<Long> { it })
            assertThat(millis).isLessThan(BOUND_MILLIS)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val EXTRA_ROWS = 50L
        const val ROWS = 20_000L
        const val LINK_EVERY = 7L
        const val PICTURE_EVERY = 11L
        const val THREADS = 50L
        const val NANOS_PER_MILLI = 1_000_000L
        const val BOUND_MILLIS = 10_000L
    }
}
