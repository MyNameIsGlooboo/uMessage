package com.glooboo.umessage.helpers

import android.content.Context
import com.glooboo.umessage.extensions.getMessagesDB
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.interfaces.StarredMessagesDao
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.models.StarredMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val Context.starredDB: StarredMessagesDao
    get() = getMessagesDB().StarredMessagesDao()

/**
 * I13: starring and finding starred messages again. The messages table is a cache the app can rebuild, and the
 * provider may then hand out other ids, so a star also keeps the sender, the time and a hash of the text (a
 * fingerprint) next to a copy of the text. Deleting a message or its chat leaves the star alone; only Unstar removes
 * the copy.
 */
object StarredMessages {

    private val spaces = Regex("""\s+""")
    private const val ADDRESS_TAIL = 10

    /** The text with its white space folded, so a re-imported copy of a message gets the same fingerprint. */
    fun fingerprint(body: String): Int = body.trim().replace(spaces, " ").hashCode()

    /** Letters and digits only, lower case, the last ten of them: "+1 555 000 1234" and "05550001234" agree. */
    fun normalizedAddress(raw: String): String =
        raw.filter { it.isLetterOrDigit() }.lowercase().takeLast(ADDRESS_TAIL)

    /** Who the message came from, or for a sent message whom it went to (the first person of the chat). */
    fun addressOf(message: Message): String =
        if (message.isReceivedMessage()) {
            message.senderPhoneNumber
        } else {
            message.participants.firstOrNull()?.phoneNumbers?.firstOrNull()?.normalizedNumber.orEmpty()
        }

    fun snapshotOf(message: Message, nowMillis: Long) = StarredMessage(
        id = message.id,
        isMMS = message.isMMS,
        threadId = message.threadId,
        address = addressOf(message),
        date = message.date.toLong(),
        bodyHash = fingerprint(message.body),
        starredTS = nowMillis,
        snapshotBody = message.body,
    )

    /** The cached message that [star] now points at under another id, or null. Same thread wins, else the first. */
    fun relinkTarget(star: StarredMessage, candidates: List<Message>): Message? {
        val address = normalizedAddress(star.address)
        val matches = candidates.filter {
            it.key != star.key && fingerprint(it.body) == star.bodyHash && normalizedAddress(addressOf(it)) == address
        }
        return matches.firstOrNull { it.threadId == star.threadId } ?: matches.firstOrNull()
    }

    suspend fun star(context: Context, messages: List<Message>) = withContext(Dispatchers.IO) {
        val dao = context.applicationContext.starredDB
        val now = System.currentTimeMillis()
        messages.filter { !it.isScheduled }.forEach { dao.insert(snapshotOf(it, now)) }
    }

    suspend fun unstar(context: Context, keys: Collection<MessageKey>) = withContext(Dispatchers.IO) {
        val dao = context.applicationContext.starredDB
        keys.forEach { dao.delete(it.id, it.isMMS) }
    }

    /** Points stars whose message came back under a new id at it. Never throws: stars are left as they are. */
    suspend fun relink(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val app = context.applicationContext
            val dao = app.starredDB
            dao.getAll().forEach { star ->
                val current = app.messagesDB.getMessage(star.id, star.isMMS)
                if (current != null && fingerprint(current.body) == star.bodyHash) return@forEach
                val target = relinkTarget(star, app.messagesDB.getMessagesAtDate(star.date.toSecondsInt()))
                    ?: return@forEach
                dao.delete(star.id, star.isMMS)
                dao.insert(star.copy(id = target.id, isMMS = target.isMMS, threadId = target.threadId))
            }
        }
        Unit
    }
}
