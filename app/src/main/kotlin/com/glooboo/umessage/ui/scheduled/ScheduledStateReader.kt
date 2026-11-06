package com.glooboo.umessage.ui.scheduled

import android.app.Application
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.getMessagesDB
import com.glooboo.umessage.extensions.getThreadTitle
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.messaging.RepeatRule
import com.glooboo.umessage.models.Message
import kotlinx.coroutines.runBlocking

/** Reads every scheduled message with its repeat rule and conversation title into the list's state. Blocking. */
class ScheduledStateReader(private val app: Application) {

    fun read(): ScheduledListState {
        val all = runCatching { app.messagesDB.getAllScheduledMessages() }.getOrDefault(emptyList())
        // B06: scheduled messages of a hidden chat are not listed, or counted, until it is unlocked.
        val messages = HiddenChats.get(app).withoutHidden(all) { it.threadId }
        val repeats = runCatching {
            runBlocking { app.getMessagesDB().ScheduledRepeatsDao().getAllRepeating() }
        }.getOrDefault(emptyList()).associate { it.messageId to RepeatRule.fromName(it.rule) }
        val approximate = app.config.inexactScheduledMessages
        return groupScheduled(
            messages.map { message ->
                ScheduledRow(
                    id = message.id,
                    threadId = message.threadId,
                    title = titleOf(message),
                    text = message.body,
                    millis = message.millis(),
                    held = message.isHeldScheduled(),
                    repeat = repeats[message.id] ?: RepeatRule.NONE,
                    approximate = approximate.contains(message.id.toString()),
                )
            },
        )
    }

    private fun titleOf(message: Message): String =
        runCatching { app.conversationsDB.getConversationWithThreadId(message.threadId)?.title }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: message.participants.getThreadTitle()
}
