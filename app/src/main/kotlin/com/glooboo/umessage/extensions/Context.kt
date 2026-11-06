package com.glooboo.umessage.extensions

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.PhoneLookup
import android.provider.OpenableColumns
import android.provider.Telephony.Mms
import android.provider.Telephony.MmsSms
import android.provider.Telephony.Sms
import android.provider.Telephony.Threads
import android.provider.Telephony.ThreadsColumns
import android.telephony.SubscriptionManager
import android.text.TextUtils
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.google.android.mms.pdu_alt.PduHeaders
import org.fossify.commons.extensions.areDigitsOnly
import org.fossify.commons.extensions.getBlockedNumbers
import org.fossify.commons.extensions.getIntValue
import org.fossify.commons.extensions.getIntValueOr
import org.fossify.commons.extensions.getLongValue
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getStringValue
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.extensions.queryCursor
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.DAY_SECONDS
import org.fossify.commons.helpers.MONTH_SECONDS
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import com.glooboo.umessage.R
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.helpers.AttachmentUtils.parseAttachmentNames
import com.glooboo.umessage.helpers.Config
import com.glooboo.umessage.helpers.NotifyContext
import com.glooboo.umessage.helpers.stripControlCharacters
import com.glooboo.umessage.helpers.ConversationLookups
import com.glooboo.umessage.helpers.FILE_SIZE_NONE
import com.glooboo.umessage.helpers.MAX_MESSAGE_LENGTH
import com.glooboo.umessage.helpers.MESSAGES_LIMIT
import com.glooboo.umessage.helpers.MessageCacheImport
import com.glooboo.umessage.helpers.MessageNoteKey
import com.glooboo.umessage.helpers.messageNotes
import com.glooboo.umessage.helpers.MessagingCache
import com.glooboo.umessage.helpers.NotificationHelper
import com.glooboo.umessage.helpers.ShortcutHelper
import com.glooboo.umessage.helpers.generateRandomId
import com.glooboo.umessage.interfaces.AttachmentsDao
import com.glooboo.umessage.interfaces.ConversationsDao
import com.glooboo.umessage.interfaces.DraftsDao
import com.glooboo.umessage.interfaces.LabelsDao
import com.glooboo.umessage.interfaces.MessageAttachmentsDao
import com.glooboo.umessage.interfaces.MessagesDao
import com.glooboo.umessage.messaging.MessagingUtils
import com.glooboo.umessage.messaging.MessagingUtils.Companion.ADDRESS_SEPARATOR
import com.glooboo.umessage.messaging.SmsSender
import com.glooboo.umessage.messaging.cancelRemindersForMessage
import com.glooboo.umessage.messaging.scheduleMessage
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Draft
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageAttachment
import com.glooboo.umessage.models.MessageKey
import com.glooboo.umessage.models.NamePhoto
import com.glooboo.umessage.models.RecycleBinMessage
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.defaultRegion
import org.xmlpull.v1.XmlPullParserException
import java.io.FileNotFoundException
import kotlin.time.Duration.Companion.minutes

val Context.config: Config
    get() = Config.newInstance(applicationContext)

fun Context.getMessagesDB() = MessagesDatabase.getInstance(this)

val Context.conversationsDB: ConversationsDao
    get() = getMessagesDB().ConversationsDao()

val Context.attachmentsDB: AttachmentsDao
    get() = getMessagesDB().AttachmentsDao()

val Context.messageAttachmentsDB: MessageAttachmentsDao
    get() = getMessagesDB().MessageAttachmentsDao()

val Context.messagesDB: MessagesDao
    get() = getMessagesDB().MessagesDao()

val Context.draftsDB: DraftsDao
    get() = getMessagesDB().DraftsDao()

val Context.labelsDB: LabelsDao
    get() = getMessagesDB().LabelsDao()

val Context.notificationHelper
    get() = NotificationHelper(this)

val Context.messagingUtils
    get() = MessagingUtils(this)

val Context.smsSender
    get() = SmsSender.getInstance(applicationContext as Application)

val Context.shortcutHelper get() = ShortcutHelper(this)

/**
 * The newest [limit] messages of a thread (SMS, MMS and, unless excluded, scheduled ones), oldest first.
 *
 * With [dateFrom] (Unix seconds) only messages older than that second are read; [includeSameSecond] also takes the
 * ones sent within that second. Paging needs it (DL-17): a message's date is kept in whole seconds, so the oldest
 * message on screen does not tell which of the others sent in that same second were already loaded.
 */
fun Context.getMessages(
    threadId: Long,
    dateFrom: Int = -1,
    includeScheduledMessages: Boolean = true,
    limit: Int = MESSAGES_LIMIT,
    includeSameSecond: Boolean = false,
): ArrayList<Message> {
    val uri = Sms.CONTENT_URI
    val projection = arrayOf(
        Sms._ID,
        Sms.BODY,
        Sms.TYPE,
        Sms.ADDRESS,
        Sms.DATE,
        Sms.READ,
        Sms.THREAD_ID,
        Sms.SUBSCRIPTION_ID,
        Sms.STATUS
    )

    // SMS dates are in milliseconds, MMS dates in seconds; both are read up to the end of the cutoff second when
    // includeSameSecond is set.
    val beforeSecond = if (dateFrom != -1 && includeSameSecond) dateFrom + 1 else dateFrom
    val rangeQuery = if (dateFrom == -1) "" else "AND ${Sms.DATE} < ${beforeSecond.toLong() * 1000}"
    val selection = "${Sms.THREAD_ID} = ? $rangeQuery"
    val selectionArgs = arrayOf(threadId.toString())
    val sortOrder = "${Sms.DATE} DESC LIMIT $limit"

    // Messages from a number that is on the system block list are shown too: blocking stops new messages, it does
    // not hide the history that is already in the thread (and still counted as unread).
    var messages = ArrayList<Message>()
    queryCursor(uri, projection, selection, selectionArgs, sortOrder, showErrors = true) { cursor ->
        val senderNumber = cursor.getStringValue(Sms.ADDRESS) ?: return@queryCursor

        val id = cursor.getLongValue(Sms._ID)
        val body = cursor.getStringValue(Sms.BODY)
        val type = cursor.getIntValue(Sms.TYPE)
        val namePhoto = getNameAndPhotoFromPhoneNumber(senderNumber)
        val senderName = namePhoto.name
        val photoUri = namePhoto.photoUri ?: ""
        val date = (cursor.getLongValue(Sms.DATE) / 1000).toInt()
        val read = cursor.getIntValue(Sms.READ) == 1
        val thread = cursor.getLongValue(Sms.THREAD_ID)
        val subscriptionId = cursor.getIntValueOr(
            key = Sms.SUBSCRIPTION_ID,
            defaultValue = SubscriptionManager.INVALID_SUBSCRIPTION_ID
        )

        val status = cursor.getIntValue(Sms.STATUS)
        val participants = senderNumber.split(ADDRESS_SEPARATOR).map { number ->
            val phoneNumber = PhoneNumber(number, 0, "", number)
            val participantPhoto = getNameAndPhotoFromPhoneNumber(number)
            SimpleContact(
                rawId = 0,
                contactId = 0,
                name = participantPhoto.name,
                photoUri = photoUri,
                phoneNumbers = arrayListOf(phoneNumber),
                birthdays = ArrayList(),
                anniversaries = ArrayList()
            )
        }
        val isMMS = false
        val message =
            Message(
                id = id,
                body = body,
                type = type,
                status = status,
                participants = ArrayList(participants),
                date = date,
                read = read,
                threadId = thread,
                isMMS = isMMS,
                attachment = null,
                senderPhoneNumber = senderNumber,
                senderName = senderName,
                senderPhotoUri = photoUri,
                subscriptionId = subscriptionId
            )
        messages.add(message)
    }

    messages.addAll(getMMS(threadId, sortOrder, beforeSecond))

    if (includeScheduledMessages) {
        try {
            val scheduledMessages = messagesDB.getScheduledThreadMessages(threadId)
            messages.addAll(scheduledMessages)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    messages = messages
        .filter { it.participants.isNotEmpty() }
        .filterNot { it.isExpiredScheduled(System.currentTimeMillis()) }
        .sortedWith(compareBy<Message> { it.date }.thenBy { it.id })
        .takeLast(limit)
        .toMutableList() as ArrayList<Message>

    return messages
}

/**
 * The next page of a thread's history when [loaded] is on screen and its oldest message is from second [cutoff]:
 * the messages not loaded yet from that second and before, oldest first. Empty when nothing older is left.
 *
 * DL-17: the old `date < cutoff` page skipped every message sent in the same second as the oldest one shown.
 * Scheduled messages are left out: they are the newest of all and come with the regular refresh.
 */
fun Context.getOlderThreadMessages(threadId: Long, cutoff: Int, loaded: List<Message>): List<Message> {
    val page = getMessages(threadId, cutoff, includeScheduledMessages = false, includeSameSecond = true)
        .filterNotInByKey(loaded) { it.getStableId() }
    if (page.isNotEmpty()) return page
    // A whole page from the cutoff second was already on screen; step past that second so paging keeps moving.
    return getMessages(threadId, cutoff, includeScheduledMessages = false)
        .filterNotInByKey(loaded) { it.getStableId() }
}

// as soon as a message contains multiple recipients it counts as an MMS instead of SMS
fun Context.getMMS(
    threadId: Long? = null,
    sortOrder: String? = null,
    dateFrom: Int = -1,
    uri: Uri = Mms.CONTENT_URI,
): ArrayList<Message> {
    val projection = arrayOf(
        Mms._ID,
        Mms.DATE,
        Mms.READ,
        Mms.MESSAGE_BOX,
        Mms.THREAD_ID,
        Mms.SUBSCRIPTION_ID,
        Mms.STATUS
    )

    var selection: String? = null
    var selectionArgs: Array<String>? = null

    if (threadId == null && dateFrom != -1) {
        // Should not multiply 1000 here, because date in mms's database is different from sms's.
        selection = "${Sms.DATE} < ${dateFrom.toLong()}"
    } else if (threadId != null && dateFrom == -1) {
        selection = "${Sms.THREAD_ID} = ?"
        selectionArgs = arrayOf(threadId.toString())
    } else if (threadId != null) {
        selection = "${Sms.THREAD_ID} = ? AND ${Sms.DATE} < ${dateFrom.toLong()}"
        selectionArgs = arrayOf(threadId.toString())
    }

    // DL-19: the rows are read first and their parts in one bulk query afterwards. Each MMS used to cost its own
    // parts query, sender query and participant lookups, hundreds of provider calls for a chat with many pictures.
    val rows = ArrayList<MmsRow>()
    queryCursor(uri, projection, selection, selectionArgs, sortOrder, showErrors = true) { cursor ->
        rows += MmsRow(
            id = cursor.getLongValue(Mms._ID),
            type = cursor.getIntValue(Mms.MESSAGE_BOX),
            date = cursor.getLongValue(Mms.DATE).toInt(),
            read = cursor.getIntValue(Mms.READ) == 1,
            threadId = cursor.getLongValue(Mms.THREAD_ID),
            subscriptionId = cursor.getIntValue(Mms.SUBSCRIPTION_ID),
            status = cursor.getIntValue(Mms.STATUS),
        )
    }
    if (rows.isEmpty()) return ArrayList()

    val attachments = getMmsAttachments(rows.map { it.id })
    val contactsMap = HashMap<Int, SimpleContact>()
    val participantsByThread = HashMap<Long, ArrayList<SimpleContact>>()
    val messages = ArrayList<Message>(rows.size)
    for (row in rows) {
        val participants = participantsByThread.getOrPut(row.threadId) {
            getThreadParticipants(row.threadId, contactsMap).also { found ->
                found.forEach { contactsMap[it.rawId] = it }
            }
        }

        val attachment = attachments[row.id] ?: MessageAttachment(row.id, "", arrayListOf())
        var senderNumber = ""
        var senderName = ""
        var senderPhotoUri = ""

        if (row.type != Mms.MESSAGE_BOX_SENT && row.type != Mms.MESSAGE_BOX_FAILED) {
            // In a one-to-one chat the sender is the other person; only group messages need their own lookup.
            senderNumber = participants.singleOrNull()?.phoneNumbers?.firstOrNull()?.value?.takeIf { it.isNotEmpty() }
                ?: getMMSSender(row.id)
            val namePhoto = getNameAndPhotoFromPhoneNumber(senderNumber)
            senderName = namePhoto.name
            senderPhotoUri = namePhoto.photoUri ?: ""
        }

        messages += Message(
            id = row.id,
            body = attachment.text,
            type = row.type,
            status = row.status,
            participants = ArrayList(participants),
            date = row.date,
            read = row.read,
            threadId = row.threadId,
            isMMS = true,
            attachment = attachment,
            senderPhoneNumber = senderNumber,
            senderName = senderName,
            senderPhotoUri = senderPhotoUri,
            subscriptionId = row.subscriptionId
        )
    }

    return messages
}

fun Context.getMMSSender(msgId: Long): String {
    val uri = "${Mms.CONTENT_URI}/$msgId/addr".toUri()
    val projection = arrayOf(
        Mms.Addr.ADDRESS
    )

    val selection = "${Mms.Addr.TYPE} = ?"
    val selectionArgs = arrayOf(PduHeaders.FROM.toString())

    try {
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getStringValue(Mms.Addr.ADDRESS)
            }
        }
    } catch (_: Exception) {
    }
    return ""
}

/** Unread incoming messages per thread; only [threadId]'s when it is given (one chat refreshed, MC-02). */
fun Context.getUnreadCountsByThread(threadId: Long? = null): Map<Long, Int> {
    val result = HashMap<Long, Int>(128)

    fun bump(id: Long) {
        result[id] = (result[id] ?: 0) + 1
    }

    val inThread = if (threadId != null) " AND thread_id = ?" else ""
    val args = threadId?.let { arrayOf(it.toString()) }

    // Unread SMS
    queryCursor(
        uri = Sms.CONTENT_URI,
        projection = arrayOf(Sms.THREAD_ID),
        selection = "${Sms.READ}=0 AND ${Sms.TYPE}=${Sms.MESSAGE_TYPE_INBOX}$inThread",
        selectionArgs = args,
        showErrors = false
    ) { bump(it.getLongValue(Sms.THREAD_ID)) }

    // Unread MMS
    queryCursor(
        uri = Mms.CONTENT_URI,
        projection = arrayOf(Mms.THREAD_ID),
        selection = "${Mms.READ}=0 AND ${Mms.MESSAGE_BOX}=${Mms.MESSAGE_BOX_INBOX}$inThread",
        selectionArgs = args,
        showErrors = false
    ) { bump(it.getLongValue(Mms.THREAD_ID)) }

    return result
}

/**
 * Conversations from the telephony provider, newest first. [limit] stops after that many conversations are kept,
 * so the top of the list can be refreshed before the full pass. Pass the same [lookups] to several calls to read
 * contacts and addresses only once.
 */
fun Context.getConversations(
    threadId: Long? = null,
    privateContacts: ArrayList<SimpleContact> = ArrayList(),
    limit: Int? = null,
    lookups: ConversationLookups = ConversationLookups(this, bulk = threadId == null),
): ArrayList<Conversation> = readConversations(threadId, privateContacts, limit, lookups).first

/**
 * Like [getConversations], but null when the provider could not be read completely (no cursor, or an error part
 * way through). A caller that deletes cached threads missing from the result must use this: a failed read is not
 * "every conversation was deleted".
 */
fun Context.getConversationsOrNull(
    privateContacts: ArrayList<SimpleContact> = ArrayList(),
    lookups: ConversationLookups = ConversationLookups(this, bulk = true),
): ArrayList<Conversation>? {
    val (conversations, complete) = readConversations(null, privateContacts, null, lookups)
    return conversations.takeIf { complete }
}

/** The conversations read, and whether the read finished without the provider failing. */
private fun Context.readConversations(
    threadId: Long?,
    privateContacts: ArrayList<SimpleContact>,
    limit: Int?,
    lookups: ConversationLookups,
): Pair<ArrayList<Conversation>, Boolean> {
    val archiveAvailable = config.isArchiveAvailable

    val uri = "${Threads.CONTENT_URI}?simple=true".toUri()
    val projection = mutableListOf(
        Threads._ID,
        Threads.SNIPPET,
        Threads.DATE,
        Threads.RECIPIENT_IDS,
    )

    if (archiveAvailable) {
        projection += Threads.ARCHIVED
    }

    var selection = "${Threads.MESSAGE_COUNT} > 0"
    var selectionArgs = arrayOf<String>()
    if (threadId != null) {
        selection += " AND ${Threads._ID} = ?"
        selectionArgs += threadId.toString()
    }

    val sortOrder = "${Threads.DATE} DESC"

    val conversations = ArrayList<Conversation>()
    val blockedNumbers = getBlockedNumbers()
    val unreadMap = getUnreadCountsByThread(threadId)
    val markedUnread = config.markedUnreadThreads
    var complete = false
    try {
        complete = queryCursorUnsafe(
            uri,
            projection.toTypedArray(),
            selection,
            selectionArgs,
            sortOrder,
            shouldStop = { limit != null && conversations.size >= limit },
        ) { cursor ->
            val id = cursor.getLongValue(Threads._ID)
            var snippet = cursor.getStringValue(Threads.SNIPPET) ?: ""
            if (snippet.isEmpty()) {
                snippet = getThreadSnippet(id)
            }

            var date = cursor.getLongValue(Threads.DATE)
            if (date.toString().length > 10) {
                date /= 1000
            }

            // drafts are stored locally they take priority over the original date
            val draft = lookups.draft(id)
            if (draft != null) {
                date = draft.date / 1000
            }

            val rawIds = cursor.getStringValue(Threads.RECIPIENT_IDS)
            val recipientIds =
                rawIds.split(" ").filter { it.areDigitsOnly() }.map { it.toInt() }.toMutableList()
            val phoneNumbers = recipientIds.mapTo(ArrayList()) { lookups.phoneNumber(it) }
            if (phoneNumbers.isEmpty() || phoneNumbers.any {
                    isNumberBlocked(
                        it,
                        blockedNumbers
                    )
                }) {
                return@queryCursorUnsafe
            }

            val names = getThreadContactNames(phoneNumbers, privateContacts, lookups::name)
            val title = joinGroupNames(names)
            val photoUri = if (phoneNumbers.size == 1) lookups.photoUri(phoneNumbers.first()) else ""
            val isGroupConversation = phoneNumbers.size > 1
            // Unread means the thread has unread incoming messages. The provider's own threads.read flag is not
            // trusted: some OEM providers (seen on Xiaomi) leave it at 0 after every message is read, and opening the
            // thread can't clear it because there is no unread row left to update. A thread the user marked unread
            // stays unread even with no unread incoming message (e.g. only sent messages).
            val unreadCount = unreadMap[id] ?: 0
            val read = unreadCount == 0 && id.toString() !in markedUnread
            val archived =
                if (archiveAvailable) cursor.getIntValue(Threads.ARCHIVED) == 1 else false
            val conversation = Conversation(
                threadId = id,
                snippet = snippet,
                date = date.toInt(),
                read = read,
                title = title,
                photoUri = photoUri,
                isGroupConversation = isGroupConversation,
                phoneNumber = phoneNumbers.first(),
                isArchived = archived,
                unreadCount = unreadCount,
            )
            conversations.add(conversation)
        }
    } catch (sqliteException: SQLiteException) {
        if (
            sqliteException.message?.contains("no such column: archived") == true
            && archiveAvailable
        ) {
            config.isArchiveAvailable = false
            return readConversations(threadId, privateContacts, limit, lookups)
        } else {
            showErrorToast(sqliteException)
        }
    } catch (e: Exception) {
        showErrorToast(e)
    }

    conversations.sortByDescending { it.date }
    return conversations to complete
}

/** False when the provider returned no cursor at all. */
private fun Context.queryCursorUnsafe(
    uri: Uri,
    projection: Array<String>,
    selection: String? = null,
    selectionArgs: Array<String>? = null,
    sortOrder: String? = null,
    shouldStop: () -> Boolean = { false },
    callback: (cursor: Cursor) -> Unit,
): Boolean {
    val cursor = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder) ?: return false
    cursor.use {
        while (!shouldStop() && cursor.moveToNext()) {
            callback(cursor)
        }
    }
    return true
}

fun Context.getConversationIds(): List<Long> {
    val uri = "${Threads.CONTENT_URI}?simple=true".toUri()
    val projection = arrayOf(Threads._ID)
    val selection = "${Threads.MESSAGE_COUNT} > 0"
    val sortOrder = "${Threads.DATE} ASC"
    val conversationIds = mutableListOf<Long>()
    queryCursor(uri, projection, selection, null, sortOrder, true) { cursor ->
        val id = cursor.getLongValue(Threads._ID)
        conversationIds.add(id)
    }
    return conversationIds
}

// based on https://stackoverflow.com/a/6446831/1967672
@SuppressLint("NewApi")
fun Context.getMmsAttachment(id: Long): MessageAttachment {
    val uri = if (isQPlus()) {
        Mms.Part.CONTENT_URI
    } else {
        "content://mms/part".toUri()
    }

    return getMmsAttachments(listOf(id), uri)[id] ?: MessageAttachment(id, "", arrayListOf())
}

/** One MMS as read from its row, before its parts are attached. */
private class MmsRow(
    val id: Long,
    val type: Int,
    val date: Int,
    val read: Boolean,
    val threadId: Long,
    val subscriptionId: Int,
    val status: Int,
)

/** Parts of many MMS read at once, this many message ids per query (well under SQLite's limits). */
private const val MMS_PARTS_CHUNK = 200

/**
 * The text and attachments of every MMS in [ids], with one parts query per [MMS_PARTS_CHUNK] messages instead of one
 * per message (DL-19). Messages without parts are left out of the map.
 */
@SuppressLint("NewApi")
fun Context.getMmsAttachments(
    ids: Collection<Long>,
    uri: Uri = if (isQPlus()) Mms.Part.CONTENT_URI else "content://mms/part".toUri(),
): Map<Long, MessageAttachment> {
    val projection = arrayOf(
        Mms._ID,
        Mms.Part.MSG_ID,
        Mms.Part.CONTENT_TYPE,
        Mms.Part.TEXT
    )
    val result = HashMap<Long, MessageAttachment>()
    val attachmentNames = HashMap<Long, List<String>?>()
    val attachmentCounts = HashMap<Long, Int>()

    ids.distinct().chunked(MMS_PARTS_CHUNK).forEach { chunk ->
        // The ids are numbers from the provider, so they can go in the selection as they are.
        val selection = "${Mms.Part.MSG_ID} IN (${chunk.joinToString(",")})"
        val sortOrder = "${Mms.Part.MSG_ID} ASC, ${Mms._ID} ASC"
        queryCursor(uri, projection, selection, null, sortOrder, showErrors = true) { cursor ->
            val partId = cursor.getLongValue(Mms._ID)
            val id = cursor.getLongValue(Mms.Part.MSG_ID)
            val mimetype = cursor.getStringValue(Mms.Part.CONTENT_TYPE) ?: ""
            val messageAttachment = result.getOrPut(id) { MessageAttachment(id, "", arrayListOf()) }
            if (mimetype == "text/plain") {
                messageAttachment.text = cursor
                    .getStringValue(Mms.Part.TEXT)
                    ?.take(MAX_MESSAGE_LENGTH)
                    .orEmpty()
            } else if (mimetype.startsWith("image/") || mimetype.startsWith("video/")) {
                val fileUri = Uri.withAppendedPath(uri, partId.toString())
                messageAttachment.attachments.add(
                    Attachment(
                        id = partId,
                        messageId = id,
                        uriString = fileUri.toString(),
                        mimetype = mimetype,
                        width = 0,
                        height = 0,
                        filename = ""
                    )
                )
            } else if (mimetype != "application/smil") {
                val count = attachmentCounts[id] ?: 0
                val attachmentName = attachmentNames[id]?.getOrNull(count) ?: ""
                val attachment = Attachment(
                    id = partId,
                    messageId = id,
                    uriString = Uri.withAppendedPath(uri, partId.toString()).toString(),
                    mimetype = mimetype,
                    width = 0,
                    height = 0,
                    filename = attachmentName
                )
                messageAttachment.attachments.add(attachment)
                attachmentCounts[id] = count + 1
            } else {
                val text = cursor.getStringValue(Mms.Part.TEXT)
                attachmentNames[id] = try {
                    parseAttachmentNames(text)
                } catch (e: XmlPullParserException) {
                    e.printStackTrace()
                    null
                }
            }
        }
    }

    return result
}

fun Context.getLatestMMS(): Message? {
    val sortOrder = "${Mms.DATE} DESC LIMIT 1"
    return getMMS(sortOrder = sortOrder).firstOrNull()
}

fun Context.getMMS(messageUri: Uri): Message? {
    return getMMS(uri = messageUri).firstOrNull()
}

fun Context.getThreadSnippet(threadId: Long): String {
    val sortOrder = "${Mms.DATE} DESC LIMIT 1"
    val latestMms = getMMS(threadId, sortOrder).firstOrNull()
    var snippet = latestMms?.body ?: ""

    val uri = Sms.CONTENT_URI
    val projection = arrayOf(
        Sms.BODY
    )

    // DL-16: MMS dates are in seconds and SMS dates in milliseconds, so comparing them as they are made every SMS
    // newer than any MMS and an older text hid the newest picture message.
    val selection = "${Sms.THREAD_ID} = ? AND ${Sms.DATE} > ?"
    val selectionArgs = arrayOf(
        threadId.toString(),
        latestMms?.millis()?.toString() ?: "0"
    )
    try {
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
        cursor?.use {
            if (cursor.moveToFirst()) {
                snippet = cursor.getStringValue(Sms.BODY)
            }
        }
    } catch (_: Exception) {
    }
    return snippet
}

fun Context.getMessageRecipientAddress(messageId: Long): String {
    val uri = Sms.CONTENT_URI
    val projection = arrayOf(
        Sms.ADDRESS
    )

    val selection = "${Sms._ID} = ?"
    val selectionArgs = arrayOf(messageId.toString())

    try {
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                return cursor.getStringValue(Sms.ADDRESS)
            }
        }
    } catch (_: Exception) {
    }

    return ""
}

fun Context.getThreadParticipants(
    threadId: Long,
    contactsMap: HashMap<Int, SimpleContact>?,
): ArrayList<SimpleContact> {
    val recipientIds = getThreadRecipientIds(threadId)
    val phoneNumbers = getThreadPhoneNumbers(recipientIds)
    MessagingCache.participantsCache.get(threadId, phoneNumbers)?.let {
        return it
    }

    val participants = ArrayList<SimpleContact>()
    recipientIds.zip(phoneNumbers).forEach { (addressId, number) ->
        val cachedContact = contactsMap?.get(addressId)
        val threadPhoneNumber = cachedContact
            ?.phoneNumbers
            ?.firstOrNull { it.normalizedNumber == number }
        if (cachedContact != null && threadPhoneNumber != null) {
            participants.add(
                cachedContact.copy(
                    phoneNumbers = arrayListOf(threadPhoneNumber.copy()),
                    birthdays = ArrayList(cachedContact.birthdays),
                    anniversaries = ArrayList(cachedContact.anniversaries)
                )
            )
            return@forEach
        }

        val namePhoto = getNameAndPhotoFromPhoneNumber(number)
        val name = namePhoto.name
        val photoUri = namePhoto.photoUri ?: ""
        val phoneNumber = PhoneNumber(number, 0, "", number)
        val contact = SimpleContact(
            rawId = addressId,
            contactId = addressId,
            name = name,
            photoUri = photoUri,
            phoneNumbers = arrayListOf(phoneNumber),
            birthdays = ArrayList(),
            anniversaries = ArrayList()
        )
        participants.add(contact)
    }

    MessagingCache.participantsCache.put(threadId, phoneNumbers, participants)
    return participants
}

private fun Context.getThreadRecipientIds(threadId: Long): List<Int> {
    val recipientIds = ArrayList<Int>()
    queryCursor(
        uri = "${MmsSms.CONTENT_CONVERSATIONS_URI}?simple=true".toUri(),
        projection = arrayOf(ThreadsColumns.RECIPIENT_IDS),
        selection = "${Mms._ID} = ?",
        selectionArgs = arrayOf(threadId.toString()),
        showErrors = true
    ) { cursor ->
        cursor.getStringValue(ThreadsColumns.RECIPIENT_IDS)
            .split(" ")
            .filter { it.areDigitsOnly() }
            .forEach { recipientIds.add(it.toInt()) }
    }

    return recipientIds
}

fun Context.getThreadPhoneNumbers(recipientIds: List<Int>): ArrayList<String> {
    val numbers = ArrayList<String>()
    recipientIds.forEach {
        numbers.add(getPhoneNumberFromAddressId(it))
    }
    return numbers
}

fun Context.getThreadContactNames(
    phoneNumbers: List<String>,
    privateContacts: ArrayList<SimpleContact>,
    nameOf: (String) -> String = SimpleContactsHelper(this)::getNameFromPhoneNumber,
): ArrayList<String> {
    val names = ArrayList<String>()
    phoneNumbers.forEach { number ->
        val name = nameOf(number)
        if (name != number) {
            names.add(name)
        } else {
            val privateContact = privateContacts.firstOrNull { it.doesHavePhoneNumber(number) }
            if (privateContact == null) {
                names.add(name)
            } else {
                names.add(privateContact.name)
            }
        }
    }
    // A thread created from a broken link can carry a NUL in its address; never show it in a title.
    return names.mapTo(ArrayList()) { it.stripControlCharacters() }
}

fun Context.getPhoneNumberFromAddressId(canonicalAddressId: Int): String {
    val uri = Uri.withAppendedPath(MmsSms.CONTENT_URI, "canonical-addresses")
    val projection = arrayOf(
        Mms.Addr.ADDRESS
    )

    val selection = "${Mms._ID} = ?"
    val selectionArgs = arrayOf(canonicalAddressId.toString())
    try {
        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                return cursor.getStringValue(Mms.Addr.ADDRESS)
            }
        }
    } catch (e: Exception) {
        showErrorToast(e)
    }
    return ""
}

fun Context.getSuggestedContacts(
    privateContacts: ArrayList<SimpleContact>,
): ArrayList<SimpleContact> {
    val contacts = ArrayList<SimpleContact>()
    val region = defaultRegion(this)
    val normalizer = NumberNormalizer { region }
    val uri = Sms.CONTENT_URI
    val projection = arrayOf(
        Sms.ADDRESS
    )

    val sortOrder = "${Sms.DATE} DESC LIMIT 50"
    val blockedNumbers = getBlockedNumbers()

    queryCursor(uri, projection, null, null, sortOrder, showErrors = true) { cursor ->
        val senderNumber = cursor.getStringValue(Sms.ADDRESS) ?: return@queryCursor
        val namePhoto = getNameAndPhotoFromPhoneNumber(senderNumber)
        var senderName = namePhoto.name
        var photoUri = namePhoto.photoUri ?: ""
        if (isNumberBlocked(senderNumber, blockedNumbers)) {
            return@queryCursor
        } else if (namePhoto.name == senderNumber) {
            if (privateContacts.isNotEmpty()) {
                val privateContact = privateContacts.firstOrNull {
                    it.phoneNumbers.first().normalizedNumber == senderNumber
                }
                if (privateContact != null) {
                    senderName = privateContact.name
                    photoUri = privateContact.photoUri
                } else {
                    return@queryCursor
                }
            } else {
                return@queryCursor
            }
        }

        val phoneNumber = PhoneNumber(senderNumber, 0, "", senderNumber)
        val contact = SimpleContact(
            rawId = 0,
            contactId = 0,
            name = senderName,
            photoUri = photoUri,
            phoneNumbers = arrayListOf(phoneNumber),
            birthdays = ArrayList(),
            anniversaries = ArrayList()
        )
        // Two senders are one suggestion only when they are the same number, not merely the same last 9 digits.
        if (contacts.none { normalizer.sameAddress(it.phoneNumbers.first().normalizedNumber, senderNumber) }) {
            contacts.add(contact)
        }
    }

    return contacts
}

/** True when [number] belongs to a saved contact. False without the contacts permission. */
fun Context.isSavedContactNumber(number: String): Boolean {
    if (number.isEmpty() || !hasPermission(PERMISSION_READ_CONTACTS)) return false
    val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
    return try {
        contentResolver.query(uri, arrayOf(PhoneLookup._ID), null, null, null)?.use { it.moveToFirst() } == true
    } catch (_: Exception) {
        false
    }
}

fun Context.getNameAndPhotoFromPhoneNumber(number: String): NamePhoto {
    MessagingCache.namePhoto.get(number)?.let { return it }
    if (!hasPermission(PERMISSION_READ_CONTACTS)) {
        return NamePhoto(number, null)
    }

    val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
    val projection = arrayOf(
        PhoneLookup.DISPLAY_NAME,
        PhoneLookup.PHOTO_URI
    )

    val result = try {
        val cursor = contentResolver.query(uri, projection, null, null, null)
        cursor.use {
            if (cursor?.moveToFirst() == true) {
                val name = cursor.getStringValue(PhoneLookup.DISPLAY_NAME)
                val photoUri = cursor.getStringValue(PhoneLookup.PHOTO_URI)
                NamePhoto(name, photoUri)
            } else {
                NamePhoto(number, null)
            }
        }
    } catch (_: Exception) {
        NamePhoto(number, null)
    }

    MessagingCache.namePhoto.put(number, result)
    return result
}

fun Context.insertNewSMS(
    address: String,
    subject: String,
    body: String,
    date: Long,
    read: Int,
    threadId: Long,
    type: Int,
    subscriptionId: Int,
): Long {
    val uri = Sms.CONTENT_URI
    val contentValues = ContentValues().apply {
        put(Sms.ADDRESS, address)
        put(Sms.SUBJECT, subject)
        put(Sms.BODY, body)
        put(Sms.DATE, date)
        put(Sms.READ, read)
        put(Sms.THREAD_ID, threadId)
        put(Sms.TYPE, type)
        put(Sms.SUBSCRIPTION_ID, subscriptionId)
    }

    return try {
        val newUri = contentResolver.insert(uri, contentValues)
        newUri?.lastPathSegment?.toLong() ?: 0L
    } catch (_: Exception) {
        0L
    }
}

fun Context.removeAllArchivedConversations(callback: (() -> Unit)? = null) {
    ensureBackgroundThread {
        try {
            for (conversation in conversationsDB.getAllArchived()) {
                deleteConversation(conversation.threadId)
            }
            toast(R.string.archive_emptied_successfully)
            callback?.invoke()
        } catch (_: Exception) {
            toast(org.fossify.commons.R.string.unknown_error_occurred)
        }
    }
}

fun Context.deleteConversation(threadId: Long) {
    var uri = Sms.CONTENT_URI
    val selection = "${Sms.THREAD_ID} = ?"
    val selectionArgs = arrayOf(threadId.toString())
    try {
        contentResolver.delete(uri, selection, selectionArgs)
    } catch (e: Exception) {
        showErrorToast(e)
    }

    uri = Mms.CONTENT_URI
    try {
        contentResolver.delete(uri, selection, selectionArgs)
    } catch (e: Exception) {
        e.printStackTrace()
    }

    conversationsDB.deleteThreadId(threadId)
    kotlinx.coroutines.runBlocking { labelsDB.removeAllFromConversation(threadId) }
    kotlinx.coroutines.runBlocking { messageNotes.onThreadDeleted(threadId) }
    messagesDB.deleteThreadMessages(threadId)
    MessagingCache.participantsCache.remove(threadId)

    if (config.customNotifications.contains(threadId.toString())) {
        config.removeCustomNotificationsByThreadId(threadId)
        notificationManager.deleteNotificationChannel(threadId.toString())
    }
    if(shortcutHelper.getShortcut(threadId) != null) {
        shortcutHelper.removeShortcutForThread(threadId)
    }
}

fun Context.checkAndDeleteOldRecycleBinMessages(callback: (() -> Unit)? = null) {
    if (
        config.useRecycleBin
        && config.lastRecycleBinCheck < System.currentTimeMillis() - DAY_SECONDS * 1000
    ) {
        config.lastRecycleBinCheck = System.currentTimeMillis()
        ensureBackgroundThread {
            try {
                deleteOldRecycleBinMessages(System.currentTimeMillis())
                callback?.invoke()
            } catch (_: Exception) {
            }
        }
    }
}

/** One SMS or MMS read from the provider, or null when it is gone or cannot be read. */
private fun Context.readProviderMessage(id: Long, isMMS: Boolean): Message? = try {
    if (isMMS) {
        getMMS(Uri.withAppendedPath(Mms.CONTENT_URI, id.toString()))
    } else {
        var threadId = -1L
        var dateSec = -1
        queryCursor(
            Sms.CONTENT_URI, arrayOf(Sms.THREAD_ID, Sms.DATE), "${Sms._ID} = ?", arrayOf(id.toString())
        ) { cursor ->
            threadId = cursor.getLongValue(Sms.THREAD_ID)
            dateSec = (cursor.getLongValue(Sms.DATE) / 1000).toInt()
        }
        if (threadId == -1L) {
            null
        } else {
            getMessages(threadId, dateSec, includeScheduledMessages = false, includeSameSecond = true)
                .firstOrNull { it.key == MessageKey(id, false) }
        }
    }
} catch (e: Exception) {
    null
}

/** Removes for good every recycle-bin message deleted more than a month before [nowMillis]. */
fun Context.deleteOldRecycleBinMessages(nowMillis: Long) {
    purgeRecycleBinEntries(messagesDB.getOldRecycleBinEntries(nowMillis - MONTH_SECONDS * 1000L))
}

fun Context.emptyMessagesRecycleBin() {
    purgeRecycleBinEntries(messagesDB.getAllRecycleBinEntries())
}

/**
 * DL-13: works from the bin entries themselves, so no entry is skipped. An entry with a cached copy is a message
 * the bin shows: it is deleted from the phone. An entry without one (left by older versions, which could bin a
 * message that was never cached) is only dropped: its message never left the chat, and for entries carried over from
 * before messages were keyed by type, whether it was an SMS or an MMS is a guess, so deleting by it could remove
 * a different message.
 */
private fun Context.purgeRecycleBinEntries(entries: List<RecycleBinMessage>) {
    for (entry in entries) {
        if (messagesDB.getMessage(entry.id, entry.isMMS) != null) {
            deleteMessage(entry.id, entry.isMMS)
        } else {
            messagesDB.deleteFromRecycleBin(entry.id, entry.isMMS)
        }
    }
}

fun Context.emptyMessagesRecycleBinForConversation(threadId: Long) {
    val messages = messagesDB.getThreadMessagesFromRecycleBin(threadId)
    for (message in messages) {
        deleteMessage(message.id, message.isMMS)
    }
}

fun Context.restoreAllMessagesFromRecycleBinForConversation(threadId: Long) {
    messagesDB.deleteThreadMessagesFromRecycleBin(threadId)
}

/**
 * Moves a message to the recycle bin. The bin lists, restores and empties messages through their cached copies,
 * so one is made first when the message was never cached (DL-13: an older message paged in, or one deleted from
 * its notification); [message] is that copy when the caller has it, otherwise it is read from the provider.
 */
fun Context.moveMessageToRecycleBin(id: Long, isMMS: Boolean, message: Message? = null) {
    try {
        if (messagesDB.getMessage(id, isMMS) == null) {
            val copy = message?.takeIf { it.key == MessageKey(id, isMMS) } ?: readProviderMessage(id, isMMS)
            copy?.let { messagesDB.insertOrIgnore(it) }
        }
        messagesDB.insertRecycleBinEntry(RecycleBinMessage(id, isMMS, System.currentTimeMillis()))
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

fun Context.restoreMessageFromRecycleBin(id: Long, isMMS: Boolean) {
    try {
        messagesDB.deleteFromRecycleBin(id, isMMS)
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

fun Context.updateConversationArchivedStatus(threadId: Long, archived: Boolean) {
    val uri = Threads.CONTENT_URI
    val values = ContentValues().apply {
        put(Threads.ARCHIVED, archived)
    }
    val selection = "${Threads._ID} = ?"
    val selectionArgs = arrayOf(threadId.toString())
    try {
        contentResolver.update(uri, values, selection, selectionArgs)
    } catch (sqliteException: SQLiteException) {
        if (
            sqliteException.message?.contains("no such column: archived") == true
            && config.isArchiveAvailable
        ) {
            config.isArchiveAvailable = false
            return
        } else {
            throw sqliteException
        }
    }
    if (archived) {
        conversationsDB.moveToArchive(threadId)
    } else {
        conversationsDB.unarchive(threadId)
    }
}

fun Context.deleteMessage(id: Long, isMMS: Boolean) {
    val uri = if (isMMS) Mms.CONTENT_URI else Sms.CONTENT_URI
    val selection = "${Sms._ID} = ?"
    val selectionArgs = arrayOf(id.toString())
    // The note key is built from the cached copy, so read it before the copy goes. A message that was never cached
    // keeps its note (it is only dropped with the chat): nothing here can tell which note it had.
    val noteKey = runCatching { messagesDB.getMessage(id, isMMS)?.let { MessageNoteKey.of(it) } }.getOrNull()
    try {
        contentResolver.delete(uri, selection, selectionArgs)
        messagesDB.delete(id, isMMS)
        cancelRemindersForMessage(MessageKey(id, isMMS))
        noteKey?.let { kotlinx.coroutines.runBlocking { messageNotes.onMessageDeleted(it) } }
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

fun Context.deleteScheduledMessage(messageId: Long) {
    try {
        messagesDB.deleteScheduledMessage(messageId)
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

fun Context.markMessageRead(id: Long, isMMS: Boolean) {
    val uri = if (isMMS) Mms.CONTENT_URI else Sms.CONTENT_URI
    val contentValues = ContentValues().apply {
        put(Sms.READ, 1)
        put(Sms.SEEN, 1)
    }
    val selection = "${Sms._ID} = ?"
    val selectionArgs = arrayOf(id.toString())
    contentResolver.update(uri, contentValues, selection, selectionArgs)
    messagesDB.markRead(id, isMMS)
}

fun Context.markThreadMessagesRead(threadId: Long) {
    val id = threadId.toString()

    val smsValues = ContentValues().apply {
        put(Sms.READ, 1)
        put(Sms.SEEN, 1)
    }
    val smsSelection = "${Sms.THREAD_ID}=? AND ${Sms.TYPE}=? AND (${Sms.READ}=? OR ${Sms.SEEN}=?)"
    val smsArgs = arrayOf(id, Sms.MESSAGE_TYPE_INBOX.toString(), "0", "0")
    contentResolver.update(Sms.CONTENT_URI, smsValues, smsSelection, smsArgs)

    val mmsValues = ContentValues().apply {
        put(Mms.READ, 1)
        put(Mms.SEEN, 1)
    }
    val mmsSelection = "${Mms.THREAD_ID}=? AND ${Mms.MESSAGE_BOX}=? AND (${Mms.READ}=? OR ${Mms.SEEN}=?)"
    val mmsArgs = arrayOf(id, Mms.MESSAGE_BOX_INBOX.toString(), "0", "0")
    contentResolver.update(Mms.CONTENT_URI, mmsValues, mmsSelection, mmsArgs)

    messagesDB.markThreadRead(threadId)
    conversationsDB.markRead(threadId)
    config.setThreadMarkedUnread(threadId, false)
}

/**
 * Marks the thread unread the way Google Messages does: only the newest incoming message becomes unread, so the
 * badge shows 1, not the whole history. A thread with no incoming message is kept unread by the Config flag alone,
 * which shows it unread without a number.
 */
fun Context.markThreadMessagesUnread(threadId: Long) {
    val id = threadId.toString()
    // Id and date of the newest row; only the first row of the cursor is read.
    fun newest(uri: Uri, selection: String): Pair<Long, Long>? = try {
        contentResolver.query(uri, arrayOf(Sms._ID, Sms.DATE), selection, arrayOf(id), "${Sms.DATE} DESC")?.use {
            if (it.moveToFirst()) it.getLongValue(Sms._ID) to it.getLongValue(Sms.DATE) else null
        }
    } catch (_: Exception) {
        null
    }

    val sms = newest(Sms.CONTENT_URI, "${Sms.THREAD_ID}=? AND ${Sms.TYPE}=${Sms.MESSAGE_TYPE_INBOX}")
    // MMS dates are seconds, SMS dates milliseconds.
    val mms = newest(Mms.CONTENT_URI, "${Mms.THREAD_ID}=? AND ${Mms.MESSAGE_BOX}=${Mms.MESSAGE_BOX_INBOX}")
        ?.let { it.first to it.second * 1000 }
    val target = when {
        sms == null && mms == null -> null
        mms == null || (sms != null && sms.second >= mms.second) -> Sms.CONTENT_URI to sms!!.first
        else -> Mms.CONTENT_URI to mms.first
    }
    if (target != null) {
        val contentValues = ContentValues().apply {
            put(Sms.READ, 0)
            put(Sms.SEEN, 0)
        }
        contentResolver.update(target.first, contentValues, "${Sms._ID} = ?", arrayOf(target.second.toString()))
    }
    conversationsDB.markUnread(threadId)
    config.setThreadMarkedUnread(threadId, true)
}

@SuppressLint("NewApi")
fun Context.getThreadId(address: String): Long {
    return try {
        Threads.getOrCreateThreadId(this, address)
    } catch (_: Exception) {
        0L
    }
}

@SuppressLint("NewApi")
fun Context.getThreadId(addresses: Set<String>): Long {
    return try {
        Threads.getOrCreateThreadId(this, addresses)
    } catch (_: Exception) {
        0L
    }
}

fun Context.showReceivedMessageNotification(
    messageId: Long,
    isMms: Boolean,
    address: String,
    senderName: String,
    body: String,
    threadId: Long,
    bitmap: Bitmap?,
    subscriptionId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
    notify: NotifyContext = NotifyContext(),
) {
    Handler(Looper.getMainLooper()).post {
        notificationHelper.showMessageNotification(
            messageId = messageId,
            isMms = isMms,
            address = address,
            body = body,
            threadId = threadId,
            bitmap = bitmap,
            sender = senderName,
            subscriptionId = subscriptionId,
            notify = notify,
        )
    }
}

fun Context.getNameFromAddress(address: String, privateCursor: Cursor?): String {
    var sender = getNameAndPhotoFromPhoneNumber(address).name
    if (address == sender) {
        val privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)
        sender = privateContacts.firstOrNull { it.doesHavePhoneNumber(address) }?.name ?: address
    }
    return sender
}

fun Context.getContactFromAddress(address: String, callback: ((contact: SimpleContact?) -> Unit)) {
    val privateCursor = getMyContactsCursor(false, true)
    SimpleContactsHelper(this).getAvailableContacts(false) {
        // Closed on both paths; getSimpleContacts only closes it when it reads it.
        val contact = privateCursor.use { cursor ->
            it.firstOrNull { it.doesHavePhoneNumber(address) }
                ?: MyContactsContentProvider.getSimpleContacts(this, cursor)
                    .firstOrNull { it.doesHavePhoneNumber(address) }
        }
        callback(contact)
    }
}

fun Context.getNotificationBitmap(photoUri: String): Bitmap? {
    val size = resources.getDimension(R.dimen.notification_large_icon_size).toInt()
    if (photoUri.isEmpty()) {
        return null
    }

    val options = RequestOptions()
        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
        .centerCrop()

    return try {
        Glide.with(this)
            .asBitmap()
            .load(photoUri)
            .apply(options)
            .apply(RequestOptions.circleCropTransform())
            .into(size, size)
            .get()
    } catch (_: Exception) {
        null
    }
}

fun Context.removeDiacriticsIfNeeded(text: String): String {
    return if (config.useSimpleCharacters) text.normalizeString() else text
}

fun Context.getSmsDraft(threadId: Long): String {
    val draft = try {
        draftsDB.getDraftById(threadId)
    } catch (_: Exception) {
        null
    }

    return draft?.body.orEmpty()
}

fun Context.getAllDrafts(): HashMap<Long, String> {
    val drafts = HashMap<Long, String>()
    try {
        draftsDB.getAll().forEach {
            drafts[it.threadId] = it.body
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return drafts
}

fun Context.saveSmsDraft(body: String, threadId: Long) {
    val draft = Draft(
        threadId = threadId,
        body = body,
        date = System.currentTimeMillis()
    )

    try {
        draftsDB.insertOrUpdate(draft)
    } catch (e: Exception) {
        e.printStackTrace()
        showErrorToast(e)
    }
}

fun Context.deleteSmsDraft(threadId: Long) {
    try {
        draftsDB.delete(threadId)
    } catch (e: Exception) {
        e.printStackTrace()
        showErrorToast(e)
    }
}

fun Context.updateLastConversationMessage(threadId: Long) {
    updateLastConversationMessage(setOf(threadId))
}

/**
 * Makes the provider date [threadId] by its newest message again. The provider stamps a thread with the time of
 * each insert, not the message's own date, so a message stored later than it arrived (a "Not spam" restore, a
 * replayed or slowly filtered SMS) left the thread at the insert time until something else recomputed it: the chat
 * list showed one time and then, after the next delete anywhere, an earlier one. An always-false delete on the
 * thread's URI runs the provider's own recompute without deleting anything.
 */
fun Context.recomputeThreadDate(threadId: Long) {
    try {
        contentResolver.delete(ContentUris.withAppendedId(Threads.CONTENT_URI, threadId), "1 = 0", null)
    } catch (_: Exception) {
    }
}

/** True when a message dated [dateMillis] is stored noticeably later than it arrived (see [recomputeThreadDate]). */
fun isStoredLate(dateMillis: Long, nowMillis: Long = System.currentTimeMillis()): Boolean =
    nowMillis - dateMillis > STORED_LATE_MS

private const val STORED_LATE_MS = 1_000L

fun Context.updateLastConversationMessage(threadIds: Iterable<Long>) {
    // update the date and the snippet of the threads, by triggering the
    // following Android code (which runs even if no messages are deleted):
    // https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/android14-release/src/com/android/providers/telephony/MmsSmsProvider.java#1409
    val uri = Threads.CONTENT_URI
    val selection =
        "1 = 0" // always-false condition, because we don't actually want to delete any messages
    try {
        contentResolver.delete(uri, selection, null)
        for (threadId in threadIds) {
            val newConversation = getConversations(threadId)[0]
            insertOrUpdateConversation(newConversation)
        }
    } catch (_: Exception) {
    }
}

fun Context.getFileSizeFromUri(uri: Uri): Long {
    val assetFileDescriptor = try {
        contentResolver.openAssetFileDescriptor(uri, "r")
    } catch (_: FileNotFoundException) {
        null
    }

    // uses ParcelFileDescriptor#getStatSize underneath if failed
    val length = assetFileDescriptor?.use { it.length } ?: FILE_SIZE_NONE
    if (length != -1L) {
        return length
    }

    // if "content://" uri scheme, try contentResolver table
    if (uri.scheme.equals(ContentResolver.SCHEME_CONTENT)) {
        return contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                // maybe shouldn't trust ContentResolver for size:
                // https://stackoverflow.com/questions/48302972/content-resolver-returns-wrong-size
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex == -1) {
                    return@use FILE_SIZE_NONE
                }
                cursor.moveToFirst()
                return try {
                    cursor.getLong(sizeIndex)
                } catch (_: Throwable) {
                    FILE_SIZE_NONE
                }
            } ?: FILE_SIZE_NONE
    } else {
        return FILE_SIZE_NONE
    }
}

// fix a glitch at enabling Release version minifying from 5.12.3
// reset messages in 5.14.3 again, as PhoneNumber is no longer minified
// reset messages in 5.19.1 again, as SimpleContact is no longer minified
fun Context.clearAllMessagesIfNeeded(callback: () -> Unit) {
    if (!config.wasDbCleared) {
        ensureBackgroundThread {
            // Only the cache: scheduled messages and the recycle bin exist nowhere else.
            messagesDB.deleteCachedMessages()
            MessageCacheImport.restart(this)
            config.wasDbCleared = true
            Handler(Looper.getMainLooper()).post(callback)
        }
    } else {
        callback()
    }
}

fun Context.subscriptionManagerCompat(): SubscriptionManager {
    return getSystemService(SubscriptionManager::class.java)
}

fun Context.insertOrUpdateConversation(
    conversation: Conversation,
    cachedConv: Conversation? = conversationsDB.getConversationWithThreadId(conversation.threadId),
) {
    var updatedConv = conversation
    if (cachedConv != null && cachedConv.usesCustomTitle) {
        updatedConv = updatedConv.copy(
            title = cachedConv.title,
            usesCustomTitle = true
        )
    }
    conversationsDB.insertOrUpdate(updatedConv)
}

fun Context.renameConversation(conversation: Conversation, newTitle: String): Conversation {
    val updatedConv = conversation.copy(title = newTitle, usesCustomTitle = true)
    try {
        conversationsDB.insertOrUpdate(updatedConv)
        ensureBackgroundThread {
            shortcutHelper.createOrUpdateShortcut(updatedConv)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return updatedConv
}

fun Context.createTemporaryThread(
    message: Message,
    threadId: Long = generateRandomId(),
    cachedConv: Conversation?,
) {
    val simpleContactHelper = SimpleContactsHelper(this)
    val addresses = message.participants.getAddresses()
    val photoUri = if (addresses.size == 1) {
        simpleContactHelper.getPhotoUriFromPhoneNumber(addresses.first())
    } else {
        ""
    }

    val title = if (cachedConv != null && cachedConv.usesCustomTitle) {
        cachedConv.title
    } else {
        message.participants.getThreadTitle()
    }

    val conversation = Conversation(
        threadId = threadId,
        snippet = message.body,
        date = message.date,
        read = true,
        title = title,
        photoUri = photoUri,
        isGroupConversation = addresses.size > 1,
        phoneNumber = addresses.first(),
        isScheduled = true,
        usesCustomTitle = cachedConv?.usesCustomTitle == true,
        isArchived = false,
        unreadCount = 0,
    )
    try {
        conversationsDB.insertOrUpdate(conversation)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/**
 * Moves the scheduled [messages] to [newThreadId]. Callers pass a snapshot, so this must not write the rows back:
 * re-inserting them brought back a scheduled message that was sent and deleted meanwhile (Retry on a held message,
 * while the refresh after the send moved the thread's scheduled messages).
 */
fun Context.updateScheduledMessagesThreadId(messages: List<Message>, newThreadId: Long) {
    messages.filter { it.isScheduled }.forEach { messagesDB.moveScheduledMessage(it.id, newThreadId) }
}

fun Context.clearExpiredScheduledMessages(threadId: Long, messagesToDelete: List<Message>? = null) {
    val messages = messagesToDelete ?: messagesDB.getScheduledThreadMessages(threadId)
    val cutoff = System.currentTimeMillis() - 1.minutes.inWholeMilliseconds

    try {
        messages.filter { it.isExpiredScheduled(cutoff) }.forEach { msg ->
            messagesDB.deleteScheduledMessage(msg.id)
        }
        if (messages.filterNot { it.isExpiredScheduled(cutoff) }.isEmpty()) {
            // delete empty temporary thread
            val conversation = conversationsDB.getConversationWithThreadId(threadId)
            if (conversation != null && conversation.isScheduled) {
                conversationsDB.deleteThreadId(threadId)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        return
    }
}

fun Context.rescheduleAllScheduledMessages() {
    val scheduledMessages = try {
        messagesDB.getAllScheduledMessages()
    } catch (_: Exception) {
        return
    }

    // Falls back to inexact alarms without exact-alarm access; no toast, this runs from boot and similar broadcasts.
    // A scheduled message whose send failed is held (type FAILED): it goes out only when the user retries it.
    scheduledMessages.filter { it.type != Sms.MESSAGE_TYPE_FAILED }.forEach { message ->
        runCatching { scheduleMessage(message, warnIfInexact = false) }
    }
}

fun Context.getDefaultKeyboardHeight(): Int {
    return resources.getDimensionPixelSize(R.dimen.default_keyboard_height)
}

fun Context.shouldUnarchive(): Boolean {
    return config.isArchiveAvailable && !config.keepConversationsArchived
}

fun Context.copyToUri(src: Uri, dst: Uri) {
    contentResolver.openInputStream(src)?.use { input ->
        contentResolver.openOutputStream(dst, "rwt")?.use { out ->
            input.copyTo(out)
        }
    }
}
