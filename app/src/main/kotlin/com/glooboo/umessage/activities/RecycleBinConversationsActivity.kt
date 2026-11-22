package com.glooboo.umessage.activities

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.deleteRecycleBinMessagesOfConversations
import com.glooboo.umessage.extensions.emptyRecycleBinAndTidyConversations
import com.glooboo.umessage.extensions.getAllDrafts
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.extensions.restoreAllMessagesFromRecycleBinForConversation
import com.glooboo.umessage.helpers.ConversationListSync
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.IS_RECYCLE_BIN
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.helpers.SpamReaders
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_TITLE
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Events
import com.glooboo.umessage.ui.conversations.BinMessageCounts
import com.glooboo.umessage.ui.conversations.ConversationFolder
import com.glooboo.umessage.ui.conversations.ConversationFolderScreen
import com.glooboo.umessage.ui.conversations.ConversationRow
import com.glooboo.umessage.ui.conversations.toFolderRow
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/** Conversations that still hold messages in the Recycle Bin: restore or delete them for good. */
class RecycleBinConversationsActivity : ComposeSimpleActivity() {
    private var rows by mutableStateOf<List<ConversationRow>>(emptyList())
    private var loading by mutableStateOf(true)
    private var binCounts by mutableStateOf<BinMessageCounts?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUMessageContent {
            ConversationFolderScreen(
                folder = ConversationFolder.RecycleBin,
                rows = rows,
                loading = loading,
                binCounts = binCounts,
                onBack = ::finish,
                onOpen = ::openThread,
                onRestore = ::restore,
                onDelete = ::delete,
                onEmpty = {
                    ensureBackgroundThread {
                        val result = emptyRecycleBinAndTidyConversations()
                        load()
                        // B3-5: "Empty" that removed nothing (not the default app) says so, it is never silent.
                        result.messageRes?.let { toast(it) }
                    }
                },
            )
        }
        EventBus.getDefault().takeUnless { it.isRegistered(this) }?.register(this)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
    }

    private fun load() {
        ensureBackgroundThread {
            val conversations = try {
                // U9 (provider truth): chats in Spam are left out, like the hidden ones; a failed read lists nothing.
                val spam = SpamReaders.threadIds(this)
                SpamReaders.without(conversationsDB.getAllWithMessagesInRecycleBin(), spam) { it.threadId }
            } catch (_: Exception) {
                emptyList()
            }
            val drafts = runCatching { getAllDrafts() }.getOrDefault(HashMap())
            val pinned = config.pinnedConversations
            // B06: hidden chats stay out of the recycle bin too, until they are unlocked.
            val built = HiddenChats.get(this).withoutHidden(conversations) { it.threadId }
                .sortedWith(compareByDescending<Conversation> { pinned.contains(it.threadId.toString()) }.thenByDescending { it.date })
                .map { it.toFolderRow(this, drafts, pinned) }
            val counts = runCatching {
                BinMessageCounts(
                    perChat = built.associate {
                        it.threadId to messagesDB.getRecycleBinMessageCountForThread(it.threadId)
                    },
                    // H-4 (provider truth): the total leaves out the binned messages of chats in Spam, as the list.
                    total = if (ProviderTruthFlag.enabled) {
                        messagesDB.getRecycleBinMessageCountOutsideSpam()
                    } else {
                        messagesDB.getRecycleBinMessageCount()
                    },
                )
            }.getOrNull()
            runOnUiThread {
                rows = built
                binCounts = counts
                loading = false
            }
        }
    }

    private fun restore(ids: Set<Long>) {
        ensureBackgroundThread {
            ids.forEach { restoreAllMessagesFromRecycleBinForConversation(it) }
            // J2: the main list reads the restored chats at once, also while it is out of sight.
            ConversationListSync.refreshNow()
        }
    }

    private fun delete(ids: Set<Long>) {
        ensureBackgroundThread {
            // Only the messages in the bin go: the rest of each chat stays.
            val result = deleteRecycleBinMessagesOfConversations(ids)
            ids.forEach { notificationManager.cancel(it.hashCode()) }
            refreshConversations()
            // B3-5: "Delete forever" that removed nothing (not the default app) says so, it is never silent.
            result.messageRes?.let { toast(it) }
        }
    }

    private fun openThread(row: ConversationRow) {
        startActivity(
            Intent(this, ThreadActivity::class.java).apply {
                putExtra(THREAD_ID, row.threadId)
                putExtra(THREAD_TITLE, row.title)
                putExtra(IS_RECYCLE_BIN, true)
            },
        )
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshConversations(@Suppress("unused") event: Events.RefreshConversations) {
        load()
    }
}
