package com.glooboo.umessage.helpers

import com.glooboo.umessage.models.Events
import com.google.common.truth.Truth.assertThat
import org.greenrobot.eventbus.EventBus
import org.junit.Test

class ConversationRefreshEventsTest {

    private val bus = EventBus.builder().build()
    private val heard = mutableListOf<Long?>()
    private val events = ConversationRefreshEvents(onRefresh = { heard += it }, bus = bus)

    @Test
    fun forwardsOneThreadAndEveryThreadWhileRegistered() {
        events.register()
        bus.post(Events.RefreshConversations(threadId = 7))
        bus.post(Events.RefreshConversations())
        assertThat(heard).containsExactly(7L, null).inOrder()
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
