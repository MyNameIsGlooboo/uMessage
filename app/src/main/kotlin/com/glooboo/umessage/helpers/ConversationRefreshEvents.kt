package com.glooboo.umessage.helpers

import com.glooboo.umessage.models.Events
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * Hands [Events.RefreshConversations] from the app's EventBus to [onRefresh] (on the main thread) while registered,
 * so a view model can listen without the screen doing it for it. The thread id is null for "every thread"; urgent is
 * the event's own mark (a restore or an import).
 */
class ConversationRefreshEvents(
    private val onRefresh: (threadId: Long?, urgent: Boolean) -> Unit,
    private val bus: EventBus = EventBus.getDefault(),
) {
    fun register() {
        if (!bus.isRegistered(this)) bus.register(this)
    }

    fun unregister() {
        if (bus.isRegistered(this)) bus.unregister(this)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefreshConversations(event: Events.RefreshConversations) = onRefresh(event.threadId, event.urgent)
}
