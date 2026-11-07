package com.glooboo.umessage.backup

import android.content.Context
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.createTemporaryThread
import com.glooboo.umessage.extensions.getMessagesDB
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.MutePrefs
import com.glooboo.umessage.helpers.SavedTexts
import com.glooboo.umessage.helpers.composerPrefs
import com.glooboo.umessage.messaging.armReminder
import com.glooboo.umessage.messaging.scheduleMessage
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.ScheduledRepeat

/** The parts of the app-data entry as this phone runs them: the real stores, alarms and system threads. */
internal object AppDataParts {

    fun of(
        context: Context,
        addressesOf: (threadId: Long) -> Set<String>,
        threadFor: (addresses: Set<String>) -> Long?,
    ): List<AppDataPart> {
        val app = context.applicationContext
        val db = app.getMessagesDB()
        val booker: suspend (Message, ScheduledRepeat?) -> Unit = { message, repeat ->
            db.MessagesDao().insertOrUpdate(message)
            repeat?.let { db.ScheduledRepeatsDao().insertOrUpdate(it.copy(messageId = message.id)) }
            if (message.threadId == message.id) {
                runCatching { app.createTemporaryThread(message, message.id, null) }
            }
            app.scheduleMessage(message, warnIfInexact = false)
        }
        return listOf(
            ChatStatePart(
                drafts = db.DraftsDao(),
                mutes = MutePrefs.get(app),
                hidden = HiddenChats.get(app),
                savedTexts = SavedTexts(app.composerPrefs),
                addressesOf = addressesOf,
                threadFor = threadFor,
            ),
            RecycleBinPart(db.MessagesDao(), PendingRecycled(app)),
            ScheduledPart(
                scheduled = { db.MessagesDao().getAllScheduledMessages() },
                repeatOf = { db.ScheduledRepeatsDao().get(it) },
                reminders = db.MessageRemindersDao(),
                chats = ChatLookup(
                    addressesOf = addressesOf,
                    threadFor = threadFor,
                    conversationFor = { addresses ->
                        app.getThreadId(addresses).takeIf { id ->
                            id != 0L && app.conversationsDB.getConversationWithThreadId(id) != null
                        }
                    },
                ),
                booker = booker,
                arm = { app.armReminder(it) },
            ),
        )
    }
}
