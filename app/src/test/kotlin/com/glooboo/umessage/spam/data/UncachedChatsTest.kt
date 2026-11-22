package com.glooboo.umessage.spam.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.helpers.ConversationRefreshEvents
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.testing.offMainThread
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import java.util.Collections

/**
 * R3-1: a chat that could not be cached when its message was stored. [UncachedChats.cache] reads it from the store
 * once the store lists it, caches it (in Spam when its sender's row is there) and tells the lists; a chat cached
 * meanwhile is left alone. [UncachedChats.cacheOrRequest], the step of the MMS path, asks for the chat when its read
 * fails. The fixture's stand-in store has no conversation query, so the store's answer is given as [read].
 */
@RunWith(AndroidJUnit4::class)
class UncachedChatsTest : ProviderTruthSmsFixture() {

    private val refreshed: MutableList<Long?> = Collections.synchronizedList(mutableListOf())
    private val events = ConversationRefreshEvents(onRefresh = { refreshed += it })

    @Before
    fun registerEvents() {
        events.register()
    }

    @After
    fun unregisterEvents() {
        events.unregister()
    }

    /** The store's row of [threadId] for [STRANGER], with [title]. */
    private fun storeRow(threadId: Long, title: String = "Store") =
        Conversation(threadId, TEXT, 200, true, title, "", false, STRANGER)

    @Test
    fun aSpamTextsChatTheStoreListsNow_isCachedListedInSpam_andTheListsAreTold() {
        val thread = store.threadOf(STRANGER)
        receive(TEXT)
        // Stored for Spam while its chat could not be cached: asked for, in no list yet.
        assertThat(spamRow()?.sender).isEqualTo(STRANGER)
        assertThat(spamList()).doesNotContain(thread)
        assertThat(askedToCache.toList()).contains(thread)

        offMainThread { UncachedChats.cache(app, thread) { storeRow(it) } }
        ShadowLooper.idleMainLooper()

        assertThat(offMainThread { app.conversationsDB.getConversationWithThreadId(thread) }).isNotNull()
        assertThat(spamList()).contains(thread)
        assertThat(messagesList()).doesNotContain(thread)
        assertThat(refreshed.toList()).contains(thread)
    }

    @Test
    fun aChatCachedMeanwhile_isLeftAlone() {
        val thread = store.threadOf(STRANGER)
        cache(STRANGER, thread)
        var reads = 0

        offMainThread {
            UncachedChats.cache(app, thread) {
                reads++
                storeRow(it)
            }
        }
        ShadowLooper.idleMainLooper()

        assertThat(reads).isEqualTo(0)
        assertThat(offMainThread { app.conversationsDB.getConversationWithThreadId(thread) }?.title).isEqualTo("Chat")
        assertThat(refreshed.toList()).doesNotContain(thread)
    }

    @Test
    fun theMmsStep_asksForTheChatWhenTheStoreReadFails() {
        offMainThread { UncachedChats.cacheOrRequest(app, MMS_THREAD) { error("store busy") } }

        assertThat(askedToCache.toList()).containsExactly(MMS_THREAD)
    }

    @Test
    fun theMmsStep_asksForTheChatWhenTheStoreListsNoSuchThread() {
        offMainThread { UncachedChats.cacheOrRequest(app, MMS_THREAD) { null } }

        assertThat(askedToCache.toList()).containsExactly(MMS_THREAD)
    }

    @Test
    fun theMmsStep_cachesAChatTheStoreLists_andAsksForNothing() {
        offMainThread { UncachedChats.cacheOrRequest(app, MMS_THREAD) { storeRow(it) } }

        assertThat(offMainThread { app.conversationsDB.getConversationWithThreadId(MMS_THREAD) }).isNotNull()
        assertThat(askedToCache).isEmpty()
    }

    private companion object {
        const val TEXT = "Big sale today only"
        const val MMS_THREAD = 77L
    }
}
