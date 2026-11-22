package com.glooboo.umessage.helpers

import com.glooboo.umessage.models.Events
import com.google.common.truth.Truth.assertThat
import org.greenrobot.eventbus.EventBus
import org.junit.Test

class ConversationRefreshEventsTest {

    private val bus = EventBus.builder().build()
    private val heard = mutableListOf<Long?>()
    private val urgent = mutableListOf<Boolean>()
    private val events = ConversationRefreshEvents(onRefresh = { id, isUrgent ->
        heard += id
        urgent += isUrgent
    }, bus = bus)

    @Test
    fun forwardsOneThreadAndEveryThreadWhileRegistered() {
        events.register()
        bus.post(Events.RefreshConversations(threadId = 7))
        bus.post(Events.RefreshConversations())
        assertThat(heard).containsExactly(7L, null).inOrder()
        assertThat(urgent).containsExactly(false, false)
    }

    /** Reviewer: a restore's urgency rides on its event, not on a counter of the process. */
    @Test
    fun forwardsTheUrgentMarkOfTheEvent() {
        events.register()
        bus.post(Events.RefreshConversations(urgent = true))
        assertThat(heard).containsExactly(null)
        assertThat(urgent).containsExactly(true)
    }

    @Test
    fun refreshNowPostsAnUrgentEventForEveryThread() {
        val onDefault = ConversationRefreshEvents(onRefresh = { id, isUrgent ->
            heard += id
            urgent += isUrgent
        })
        onDefault.register()
        try {
            ConversationListSync.refreshNow()
        } finally {
            onDefault.unregister()
        }
        assertThat(heard).containsExactly(null)
        assertThat(urgent).containsExactly(true)
    }

    @Test
    fun registeringTwiceHearsEachEventOnce() {
        events.register()
        events.register()
        bus.post(Events.RefreshConversations(threadId = 1))
        assertThat(heard).containsExactly(1L)
    }

    @Test
    fun hearsNothingAfterUnregistering() {
        events.register()
        events.unregister()
        events.unregister()
        bus.post(Events.RefreshConversations(threadId = 1))
        assertThat(heard).isEmpty()
    }
}
