package com.glooboo.umessage.backup

import android.content.Context
import android.content.SharedPreferences
import android.provider.Telephony
import androidx.core.content.edit
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.createTemporaryThread
import com.glooboo.umessage.extensions.getMessagesDB
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.MutePrefs
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.helpers.SavedTexts
import com.glooboo.umessage.helpers.StarredMessages
import com.glooboo.umessage.helpers.composerPrefs
import com.glooboo.umessage.messaging.armReminder
import com.glooboo.umessage.messaging.scheduleMessage
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.ScheduledBackup
import com.glooboo.umessage.models.ScheduledRepeat
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The parts of the app-data entry as this phone runs them: the real stores, alarms and system threads. */
internal object AppDataParts {

    fun of(
        context: Context,
        addressesOf: (threadId: Long) -> Set<String>,
        threadFor: (addresses: Set<String>) -> Long?,
    ): List<AppDataPart> {
        val app = context.applicationContext
        val db = app.getMessagesDB()
        // Provider truth, flag on only: M-D1 (hidden chats left out keep their scheduled messages out) and M-SEC2.
        val providerTruth = ProviderTruthFlag.enabled
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
            scheduledPart(
                app,
                ChatLookup(addressesOf = addressesOf, threadFor = threadFor, conversationFor = conversationFor(app)),
                hiddenChats = if (providerTruth) ({ HiddenChats.get(app).chosenIds() }) else ({ emptySet() }),
                hold = if (providerTruth) HeldScheduledRestore.hold(app) else null,
            ),
        )
    }

    /** The scheduled messages and reminders part; [hold] keeps restored sends for the user's yes (M-SEC2). */
    fun scheduledPart(
        app: Context,
        chats: ChatLookup,
        hiddenChats: () -> Set<Long> = { emptySet() },
        hold: ScheduledHold? = null,
    ): ScheduledPart {
        val db = app.getMessagesDB()
        val booker: suspend (Message, ScheduledRepeat?) -> Unit = { message, repeat ->
            db.MessagesDao().insertOrUpdate(message)
            repeat?.let { db.ScheduledRepeatsDao().insertOrUpdate(it.copy(messageId = message.id)) }
            if (message.threadId == message.id) {
                runCatching { app.createTemporaryThread(message, message.id, null) }
            }
            app.scheduleMessage(message, warnIfInexact = false)
        }
        return ScheduledPart(
            scheduled = { db.MessagesDao().getAllScheduledMessages() },
            repeatOf = { db.ScheduledRepeatsDao().get(it) },
            reminders = db.MessageRemindersDao(),
            chats = chats,
            booker = booker,
            arm = { app.armReminder(it) },
            hiddenChats = hiddenChats,
            hold = hold,
        )
    }

    private fun conversationFor(app: Context): (Set<String>) -> Long? = { addresses ->
        app.getThreadId(addresses).takeIf { id ->
            id != 0L && app.conversationsDB.getConversationWithThreadId(id) != null
        }
    }

    /** The chat lookup for arming held messages: they name their people, the reminders are already restored. */
    fun armingLookup(app: Context): ChatLookup = ChatLookup(
        addressesOf = { emptySet() },
        threadFor = { addresses ->
            runCatching { Telephony.Threads.getOrCreateThreadId(app, addresses) }.getOrNull()?.takeIf { it > 0 }
        },
        conversationFor = conversationFor(app),
    )
}

/**
 * M-SEC2: a restore never arms scheduled sends silently. The scheduled messages of a restored file wait here; the
 * restore's result shows how many there are and to how many numbers ([summary], see
 * [com.glooboo.umessage.helpers.BackupJob]) and asks; [arm] sets the alarms of the ones that can still happen,
 * [discard] drops them. Kept in their own preferences until then, so a closed app does not lose them.
 */
object HeldScheduledRestore {
    /** New in provider truth; not a `spamless_` name. Never rename. */
    const val PREFS = "umessage_scheduled_hold"

    /** What the restore asks about: [messages] scheduled messages to [numbers] different numbers. */
    data class Summary(val messages: Int, val numbers: Int)

    /** The held messages; null when none waits. */
    fun summary(context: Context): Summary? {
        val items = store(context).items()
        if (items.isEmpty()) return null
        val numbers = items.flatMap { it.addresses }.mapTo(HashSet()) { StarredMessages.normalizedAddress(it) }
        return Summary(items.size, numbers.size)
    }

    /** The user said yes: the held messages are scheduled (those whose time passed and do not repeat are not). */
    suspend fun arm(context: Context): Int {
        val app = context.applicationContext
        val store = store(app)
        val items = store.items()
        if (items.isEmpty()) return 0
        val armed = AppDataParts.scheduledPart(app, AppDataParts.armingLookup(app)).arm(items)
        store.clear()
        return armed
    }

    /** The user said no: nothing is scheduled. */
    fun discard(context: Context) = store(context).clear()

    internal fun hold(context: Context): ScheduledHold = store(context)

    private fun store(context: Context) = PrefsScheduledHold(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )
}

/** The held messages as one JSON list; written at once. */
internal class PrefsScheduledHold(private val prefs: SharedPreferences) : ScheduledHold {
    private val serializer = ListSerializer(ScheduledBackup.serializer())

    /** One lock for every instance: each restore makes its own (reviewer). */
    override fun add(items: List<ScheduledBackup>) = synchronized(LOCK) {
        val all = (items() + items).distinctBy { Triple(it.addresses.sorted(), it.body, it.at) }
        prefs.edit(commit = true) { putString(KEY, json.encodeToString(serializer, all)) }
    }

    fun items(): List<ScheduledBackup> = prefs.getString(KEY, null)?.let {
        runCatching { json.decodeFromString(serializer, it) }.getOrNull()
    }.orEmpty()

    fun clear() = synchronized(LOCK) { prefs.edit(commit = true) { remove(KEY) } }

    private companion object {
        val LOCK = Any()
        const val KEY = "items"
        val json = Json { ignoreUnknownKeys = true }
    }
}
