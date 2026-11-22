package com.glooboo.umessage.spam.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.os.Handler
import android.net.Uri
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.blockedNumberCountryIso
import org.fossify.commons.extensions.getBlockedNumbers
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isOnMainThread
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.messaging.MessagingUtils
import com.glooboo.umessage.messaging.OwnSentRows
import com.glooboo.umessage.messaging.SendOutcome
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.ChatSorter
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ForwardFormat
import com.glooboo.umessage.spam.MemberSource
import com.glooboo.umessage.spam.SpamSorting
import com.glooboo.umessage.spam.SpamThreads
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.data.DefaultSpamRepository
import com.glooboo.umessage.spam.data.RemovedMembers
import com.glooboo.umessage.spam.data.SpamDatabase
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

fun defaultRegion(context: Context): String {
    val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    val candidates = listOf(telephony?.simCountryIso, telephony?.networkCountryIso, Locale.getDefault().country)
    return candidates.firstOrNull { !it.isNullOrBlank() }?.uppercase() ?: "US"
}

/** Country of the SIM with [subscriptionId], or null when unknown (invalid id, no SIM info). */
fun subscriptionRegion(context: Context, subscriptionId: Int): String? {
    if (subscriptionId < 0 || subscriptionId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) return null
    return runCatching {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        telephony?.createForSubscriptionId(subscriptionId)?.simCountryIso?.takeIf { it.isNotBlank() }?.uppercase()
    }.getOrNull()
}

class SystemContactChecker(
    context: Context,
    private val normalizer: NumberNormalizer,
) : ContactChecker {

    private val appContext = context.applicationContext

    @Volatile private var cache: ContactNumberIndex<Unit>? = null

    /** The app's private contacts (Fossify Contacts), indexed. Null until loaded, after a change, or once stale. */
    @Volatile private var privateCache: ContactNumberIndex<Unit>? = null
    @Volatile private var privateCacheLoadedAt = 0L

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            cache = null
            privateCache = null
        }
    }

    /** Normalized and raw addresses the user has sent a message to. Null until loaded or after a change. */
    @Volatile private var sentCache: SentAddresses? = null

    private class SentAddresses(val normalized: MutableSet<String>, val index: ContactNumberIndex<Unit>)

    /**
     * Messages that changed since [sentCache] was built. Every received text changes the message store, so instead of
     * reading every sent message again (SE-19: a full scan per incoming message) only the changed rows are read, on
     * the next lookup. A change that does not name one row drops the whole cache.
     */
    private val changedMessages = ConcurrentLinkedQueue<Uri>()
    private val sentLock = Any()

    private val sentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = onChange(selfChange, null)

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (uri?.authority == Telephony.MmsSms.CONTENT_URI.authority) {
                // The provider notifies mms-sms for every SMS and MMS change, next to the sms or mms row itself.
                return
            }
            // Provider truth (R3): a reply sent outside uMessage makes its recipient a member; flag off, nothing.
            if (uri != null) SentRowWatch.onChanged(appContext, uri)
            if (uri != null && messageRowId(uri) != null && changedMessages.size < MAX_CHANGED_MESSAGES) {
                changedMessages.add(uri)
            } else {
                // A change that names no row, or a bulk import that queued too many, drops the cache; the next
                // lookup reads everything once instead of one query per changed row.
                sentCache = null
            }
        }
    }

    init {
        runCatching {
            appContext.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            appContext.contentResolver.registerContentObserver(ContactsContract.Data.CONTENT_URI, true, observer)
        }
        runCatching {
            appContext.contentResolver.registerContentObserver(MyContactsContentProvider.CONTACTS_CONTENT_URI, true, observer)
        }
        runCatching {
            appContext.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, sentObserver)
            appContext.contentResolver.registerContentObserver(Telephony.Mms.CONTENT_URI, true, sentObserver)
            appContext.contentResolver.registerContentObserver(Telephony.MmsSms.CONTENT_URI, true, sentObserver)
        }
    }

    /**
     * Stops watching the contacts and the message store. A checker made for a single check must be closed (the
     * resolver keeps every registered observer, and through it the checker and its caches, alive); the process-wide one
     * of [SpamEngine] lives as long as the process.
     */
    fun close() {
        runCatching { appContext.contentResolver.unregisterContentObserver(observer) }
        runCatching { appContext.contentResolver.unregisterContentObserver(sentObserver) }
        cache = null
        privateCache = null
        sentCache = null
    }

    /**
     * FOUND when an outgoing SMS (sent, outbox, queued, failed) or a recent sent MMS went to [address]. The addresses are
     * read once with an indexed query on the message type and kept until the message store changes, so a later message
     * is answered from memory. UNKNOWN when the SMS store cannot be read, so the filter delivers rather than guess.
     */
    override fun sentTo(address: String): ContactLookup {
        if (address.isBlank() || normalizer.isSenderId(address)) return ContactLookup.NOT_FOUND
        val sent = sentAddresses() ?: return ContactLookup.UNKNOWN
        if (normalizer.normalize(address) in sent.normalized) return ContactLookup.FOUND
        // The normalized forms can differ when a number was typed without its country code in another region; the
        // index catches those, but never across two known countries (PhoneNumberUtils.compare matched trailing digits).
        return if (sent.index.contains(address)) ContactLookup.FOUND else ContactLookup.NOT_FOUND
    }

    private fun sentAddresses(): SentAddresses? = synchronized(sentLock) {
        val cached = sentCache
        if (cached != null && applyChangedMessages(cached)) return cached
        changedMessages.clear()
        val raw = loadSentSmsAddresses() ?: return null
        raw += loadSentMmsAddresses()
        val index = ContactNumberIndex<Unit>(normalizer, plausibleRegions(appContext))
        raw.forEach { index.add(it, Unit) }
        return SentAddresses(raw.mapTo(HashSet()) { normalizer.normalize(it) }, index).also { sentCache = it }
    }

    /**
     * Adds the recipients of changed messages that are outgoing to [sent]; incoming ones change nothing. False when a
     * changed row is gone (a deleted message may have been the only one sent to someone) or cannot be read, so the
     * caller reads everything again.
     */
    private fun applyChangedMessages(sent: SentAddresses): Boolean {
        while (true) {
            val uri = changedMessages.poll() ?: return true
            val id = messageRowId(uri) ?: return false
            val recipients = if (uri.authority == Telephony.Mms.CONTENT_URI.authority) {
                changedMmsRecipients(id)
            } else {
                changedSmsRecipient(id)
            } ?: return false
            recipients.forEach { address ->
                sent.index.add(address, Unit)
                sent.normalized += normalizer.normalize(address)
            }
        }
    }

    /** The recipient when SMS [id] is outgoing, empty when it is incoming, null when it is gone or unreadable. */
    private fun changedSmsRecipient(id: Long): List<String>? = runCatching {
        appContext.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.TYPE),
            "${Telephony.Sms._ID} = ?",
            arrayOf(id.toString()),
            null,
        )?.use { cursor ->
            when {
                !cursor.moveToFirst() -> null
                cursor.getInt(1) !in OUTGOING_SMS_TYPES -> emptyList()
                else -> listOfNotNull(cursor.getString(0)?.trim()?.takeIf(String::isNotEmpty))
            }
        }
    }.getOrNull()

    /** The recipients when MMS [id] is sent or in the outbox, empty when it is incoming, null when gone or unreadable. */
    private fun changedMmsRecipients(id: Long): List<String>? = runCatching {
        val box = appContext.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms.MESSAGE_BOX),
            "${Telephony.Mms._ID} = ?",
            arrayOf(id.toString()),
            null,
        )?.use { if (it.moveToFirst()) it.getInt(0) else null } ?: return@runCatching null
        if (box != Telephony.Mms.MESSAGE_BOX_SENT && box != Telephony.Mms.MESSAGE_BOX_OUTBOX) return@runCatching emptyList()
        mmsRecipients(id)
    }.getOrNull()

    private fun mmsRecipients(id: Long): List<String> {
        val result = ArrayList<String>()
        appContext.contentResolver.query(
            Uri.parse("content://mms/$id/addr"),
            arrayOf(Telephony.Mms.Addr.ADDRESS),
            "${Telephony.Mms.Addr.TYPE} IN ($PDU_TO,$PDU_CC,$PDU_BCC)",
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) c.getString(0)?.trim()?.takeIf { it.isNotEmpty() && it != MMS_NO_ADDRESS }?.let(result::add)
        }
        return result
    }

    /** Null when the SMS provider cannot be read (null cursor, missing permission, provider error). */
    private fun loadSentSmsAddresses(): HashSet<String>? = runCatching {
        val result = HashSet<String>()
        val cursor = appContext.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS),
            "${Telephony.Sms.TYPE} IN (${OUTGOING_SMS_TYPES.joinToString(",")})",
            null,
            null,
        ) ?: return null
        cursor.use {
            while (it.moveToNext()) {
                it.getString(0)?.trim()?.takeIf(String::isNotEmpty)?.let(result::add)
            }
        }
        result
    }.getOrNull()

    /**
     * Recipients of the most recent sent MMS (group texts included). Best effort: MMS storage differs between devices,
     * so a failure here leaves only the SMS answer instead of turning the whole check off.
     */
    private fun loadSentMmsAddresses(): Set<String> = runCatching {
        val ids = ArrayList<Long>()
        appContext.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID),
            "${Telephony.Mms.MESSAGE_BOX} IN (${Telephony.Mms.MESSAGE_BOX_SENT},${Telephony.Mms.MESSAGE_BOX_OUTBOX})",
            null,
            "${Telephony.Mms.DATE} DESC LIMIT $MAX_SENT_MMS",
        )?.use { while (it.moveToNext() && ids.size < MAX_SENT_MMS) ids += it.getLong(0) }
        val result = HashSet<String>()
        for (id in ids) result += mmsRecipients(id)
        result
    }.getOrDefault(emptySet())

    private companion object {
        val OUTGOING_SMS_TYPES = listOf(
            Telephony.Sms.MESSAGE_TYPE_SENT,
            Telephony.Sms.MESSAGE_TYPE_OUTBOX,
            Telephony.Sms.MESSAGE_TYPE_QUEUED,
            Telephony.Sms.MESSAGE_TYPE_FAILED,
        )
        const val MAX_SENT_MMS = 200
        const val MAX_CHANGED_MESSAGES = 50
        const val PRIVATE_CONTACTS_MAX_AGE_MS = 10 * 60_000L

        /** The row id a change notification names (content://sms/42, content://sms/inbox/42), or null. */
        fun messageRowId(uri: Uri): Long? = uri.lastPathSegment?.toLongOrNull()

        // PDU header field ids for the recipient lists (PduHeaders.TO / CC / BCC).
        const val PDU_TO = 151
        const val PDU_CC = 130
        const val PDU_BCC = 129
        const val MMS_NO_ADDRESS = "insert-address-token"
    }

    override fun canReadContacts(): Boolean = hasContactsPermission()

    private fun hasContactsPermission(): Boolean = runCatching {
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    override fun isContact(address: String): Boolean = lookup(address) == ContactLookup.FOUND

    /**
     * FOUND or NOT_FOUND only when a lookup really answered. A provider error (null cursor, exception) gives UNKNOWN,
     * so a contact is never mistaken for a stranger because the contacts database was busy for a moment.
     */
    override fun lookup(address: String): ContactLookup {
        if (address.isBlank() || normalizer.isSenderId(address)) return ContactLookup.NOT_FOUND
        val numbers = numbers()
        return when {
            numbers != null && numbers.contains(address) -> ContactLookup.FOUND
            // The address book was read and does not have it; only the app's private contacts are left. The system
            // PhoneLookup is not asked here: it matches on the last digits, so it took a stranger from another
            // country whose number ends like a contact's for that contact (DL-24).
            numbers != null -> if (inPrivateContacts(address)) ContactLookup.FOUND else ContactLookup.NOT_FOUND
            // B-2: without the permission only the private contacts can answer; their index is read once and kept,
            // not a cursor opened for every chat of a sort.
            !hasContactsPermission() -> privateLookup(address)
            else -> cursorLookup(address)
        }
    }

    /** B-2: the private contacts' index only; UNKNOWN when it could not be read. */
    private fun privateLookup(address: String): ContactLookup {
        val privateIndex = privateNumbers() ?: return ContactLookup.UNKNOWN
        return if (privateIndex.contains(address)) ContactLookup.FOUND else ContactLookup.NOT_FOUND
    }

    /** The address book could not be read although it may be: one lookup with the private contacts' cursor. */
    private fun cursorLookup(address: String): ContactLookup {
        val detail = runCatching {
            appContext.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use { privateCursor ->
                SimpleContactsHelper(appContext).existsSync(address, privateCursor)
            }
        }.getOrNull()
        return when (detail) {
            ContactLookupResult.Found -> ContactLookup.FOUND
            ContactLookupResult.NotFound -> ContactLookup.NOT_FOUND
            else -> ContactLookup.UNKNOWN
        }
    }

    /**
     * Numbers of the app's private contacts (Fossify Contacts), compared like the address book. False on error. They
     * are read once and kept until the contacts change (or for [PRIVATE_CONTACTS_MAX_AGE_MS], in case that provider
     * does not announce changes), instead of being read again for every stranger's message (SE-19).
     */
    private fun inPrivateContacts(address: String): Boolean = privateNumbers()?.contains(address) ?: false

    private fun privateNumbers(): ContactNumberIndex<Unit>? {
        val cached = privateCache
        if (cached != null && System.currentTimeMillis() - privateCacheLoadedAt < PRIVATE_CONTACTS_MAX_AGE_MS) return cached
        val loaded = runCatching {
            appContext.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use { cursor ->
                val index = ContactNumberIndex<Unit>(normalizer, plausibleRegions(appContext))
                if (cursor == null) return@use index // no private contacts app: nothing to find, ask again later
                MyContactsContentProvider.getSimpleContacts(appContext, cursor).forEach { contact ->
                    contact.phoneNumbers.forEach { phone ->
                        index.add(phone.value, Unit)
                        index.add(phone.normalizedNumber, Unit)
                    }
                }
                index
            }
        }.getOrNull() ?: return null
        privateCacheLoadedAt = System.currentTimeMillis()
        privateCache = loaded
        return loaded
    }

    /** All contact numbers. Null when they could not be read (no permission, provider error). */
    private fun numbers(): ContactNumberIndex<Unit>? {
        cache?.let { return it }
        val loaded = loadNumbers()
        if (loaded != null) cache = loaded
        return loaded
    }

    private fun loadNumbers(): ContactNumberIndex<Unit>? {
        if (!hasContactsPermission()) return null
        return runCatching {
            val result = ContactNumberIndex<Unit>(normalizer, plausibleRegions(appContext))
            // A null cursor means the provider could not be reached: that is a failed read, not "no contacts".
            val cursor = appContext.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            ) ?: return null
            cursor.use {
                while (it.moveToNext()) {
                    val number = it.getString(0).orEmpty()
                    if (number.isNotBlank()) result.add(number, Unit)
                }
            }
            result
        }.getOrNull()
    }
}

/**
 * B-2: Android's block list read once for a batch of lookups on this thread (the history check's bulk join), instead
 * of once per sender. Outside [during] every lookup reads the list as before.
 */
object BlockListBatch {
    private class Batch {
        var check: ((String) -> Boolean)? = null
    }

    private val current = ThreadLocal<Batch?>()

    fun <T> during(block: () -> T): T {
        if (current.get() != null) return block()
        current.set(Batch())
        try {
            return block()
        } finally {
            current.remove()
        }
    }

    /** The batch's check, built by [build] on first use; null outside a batch. */
    internal fun check(build: () -> (String) -> Boolean): ((String) -> Boolean)? {
        val batch = current.get() ?: return null
        return batch.check ?: build().also { batch.check = it }
    }
}

class FossifyLegacyBlocklist(private val context: Context) : LegacyBlocklist, FreezableBlocklist {
    /** Inside [BlockListBatch.during] the list is read once for the whole batch (B-2). */
    override fun isNumberBlocked(address: String): Boolean =
        BlockListBatch.check(::frozenNumberCheck)?.invoke(address) ?: context.isNumberBlocked(address)

    /** B-2: Android's block list and its country read once; matching then reads nothing. */
    override fun frozenNumberCheck(): (String) -> Boolean {
        val list = context.getBlockedNumbers()
        val iso = context.blockedNumberCountryIso()
        return { address -> list.isNotEmpty() && context.isNumberBlocked(address, list, iso) }
    }

    override fun blockedKeywords(): Collection<String> = context.config.blockedKeywords
    override fun blockUnknownNumbers() = context.baseConfig.blockUnknownNumbers
}

/**
 * Provider truth (6.3, D3 A): writing to someone allows them. A number the user writes to joins "People you wrote
 * to" (source LIVE) when the sender verdict calls it unknown (in Spam as unknown, or in Messages only because the
 * filter is off), and its chat is sorted again. A blocked number, a contact, an allowed sender, a member and a sender
 * that cannot be read change nothing. Membership is made also while the filter is off (answer 3). A failure never
 * fails a send.
 */
object WroteToGroup {

    /**
     * E-1: a member one send made: [address] was not a member before, and [wasRemoved] says whether the join cleared
     * the user's "Remove" mark (the undo puts it back).
     */
    class Joined(val address: String, val wasRemoved: Boolean)

    /**
     * uMessage's own send, from sendToDistinctRecipients only (R10: automatic forwards and carrier reports send past
     * it). Called before the send, on the sending thread, so the member lands before the new chat is cached (B2-14).
     * Returns the members this call made ([undoSend] takes them back). A send from the main thread joins on a
     * helper thread, as the rules and contacts are not read there: no caller does that today, and such a send has no
     * ordering promise (its new chat may be cached first) and no undo.
     */
    fun onSend(context: Context, addresses: List<String>, groupThread: Boolean = false): List<Joined> {
        // A group MMS thread makes no member (6.3, R11: a group sent as separate texts does).
        if (addresses.isEmpty() || groupThread) return emptyList()
        val app = context.applicationContext
        if (isOnMainThread()) {
            runCatching { ensureBackgroundThread { joinNew(app, addresses) } }
            return emptyList()
        }
        return joinNew(app, addresses)
    }

    /**
     * D-6: the send handed nothing off and saved nothing, so no text went to [joined]: the members this send made go
     * again (removed quietly, not as the user's "Remove"). Never throws.
     */
    fun undoSend(context: Context, joined: List<Joined>) {
        if (joined.isEmpty()) return
        val app = context.applicationContext
        runCatching {
            val repo = UMessage.repository(app) as DefaultSpamRepository
            val dao = SpamDatabase.getInstance(app).spamDao()
            val readers = SpamThreads.readers(app)
            val members = repo.groupMembership.members().filter { it.group == AllowGroup.WROTE }
            joined.forEach { made ->
                members.filter { SpamThreads.same(readers, it.address, made.address) }
                    .forEach { dao.deleteMember(it.group, it.senderKey) }
                if (made.wasRemoved) RemovedMembers.add(AllowGroup.WROTE, keysOf(readers, made.address))
            }
        }
    }

    /** [undoSend] when [outcome] handed nothing off and sent no part. */
    fun undoUnlessSent(context: Context, joined: List<Joined>, outcome: SendOutcome) {
        if (!outcome.handedOff && outcome.sentCount == 0) undoSend(context, joined)
    }

    /** Blocking. Returns the numbers of [addresses] that are members now (new or not). Never throws. */
    fun join(context: Context, addresses: Collection<String>): List<String> =
        joinNew(context, addresses, newOnly = false).map { it.address }

    /** The join of [join]; with [newOnly], only the members that were not members before. */
    private fun joinNew(context: Context, addresses: Collection<String>, newOnly: Boolean = true): List<Joined> {
        if (addresses.isEmpty()) return emptyList()
        val app = context.applicationContext
        return runCatching {
            val filter = UMessage.filter(app) as DefaultSpamFilter
            val repo = UMessage.repository(app) as DefaultSpamRepository
            val snapshot = filter.senderJudge.snapshot()
            val readers = SpamThreads.readers(app)
            val before = repo.groupMembership.members().filter { it.group == AllowGroup.WROTE }
            val joined = addresses.map { it.trim() }.distinct()
                .filter { isUnknown(snapshot.verdict(it)) }
                .mapNotNull { address ->
                    val member = before.any { SpamThreads.same(readers, it.address, address) }
                    val removed = RemovedMembers.contains(AllowGroup.WROTE, keysOf(readers, address))
                    val made = runBlocking { repo.joinGroup(AllowGroup.WROTE, address, MemberSource.LIVE) }
                    Joined(address, removed).takeIf { made && !(newOnly && member) }
                }
            if (joined.isNotEmpty()) sortAgain(app, joined.map { it.address })
            joined
        }.getOrDefault(emptyList())
    }

    private fun keysOf(readers: NumberReaders, address: String): Set<String> =
        (readers.senderKeys(address) + readers.senderKey(address)).filter { it.isNotEmpty() }.toSet()

    /** The same test as the history check: unknown, or in Messages only because the filter is off. */
    internal fun isUnknown(verdict: SenderVerdict): Boolean = when (verdict) {
        is SenderVerdict.Spam -> verdict.cause == SpamCause.UNKNOWN
        is SenderVerdict.Messages -> verdict.reason == DecisionReason.MODE_OFF
    }

    private fun sortAgain(app: Context, joined: List<String>) {
        runCatching { ChatSorter.snapshots(app).invalidate() }
        val threads = joined.mapNotNull { address ->
            runCatching { app.getThreadId(address) }.getOrNull()?.takeIf { it > 0L }
        }
        runCatching { SpamSorting.requestThreads(app, threads) }
    }
}

/**
 * Provider truth (6.3, R3): a reply that uMessage did not send itself (a car kit, a watch, an assistant, another app)
 * still makes its recipient a member of "People you wrote to". The observer of [SystemContactChecker] hands every
 * changed message row here; an outgoing row to one recipient, dated within [RECENT_MS], joins that recipient through
 * [WroteToGroup]. Skipped: rows uMessage wrote itself ([OwnSentRows]: its replies joined at the send, its forwards and
 * carrier reports must not), forward texts ([ForwardFormat.isForward]), an MMS to several people (a group thread) and
 * old rows (a restore; the history check reads those).
 */
internal object SentRowWatch {
    /** D-9: at most this many rows wait; a bulk import past it is left to the history check. */
    private const val MAX_WAITING = 300
    private const val DELAY_MS = 2_000L
    private const val DRAIN_SECONDS = 10L
    private const val RECENT_MS = 10 * 60_000L
    private const val MAX_HANDLED = 200
    private const val MS_PER_SECOND = 1000L

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "sent-row-watch").apply { isDaemon = true }
    }

    /** Rows already handed to [WroteToGroup] ("sms:12", "mms:7"); a row changes several times while it is sent. */
    private val handled = LinkedHashSet<String>()

    private val waiting = AtomicInteger()

    /** A row is looked at a little after its change, when uMessage's own send has listed it in [OwnSentRows]. */
    @VisibleForTesting
    @Volatile
    internal var delayMs = DELAY_MS

    fun onChanged(context: Context, uri: Uri) {
        if (waiting.incrementAndGet() > MAX_WAITING) {
            waiting.decrementAndGet()
            return
        }
        val app = context.applicationContext
        val task = Runnable {
            try {
                runCatching { handle(app, uri) }
            } finally {
                waiting.decrementAndGet()
            }
        }
        if (runCatching { executor.schedule(task, delayMs, TimeUnit.MILLISECONDS) }.isFailure) waiting.decrementAndGet()
    }

    /** Blocking: joins the recipient of the row [uri] names when it is a new outgoing one-to-one row. */
    internal fun handle(context: Context, uri: Uri, now: Long = System.currentTimeMillis()) {
        val id = uri.lastPathSegment?.toLongOrNull() ?: return
        val isMms = uri.authority == Telephony.Mms.CONTENT_URI.authority
        val key = (if (isMms) "mms:" else "sms:") + id
        if (synchronized(handled) { key in handled } || (!isMms && OwnSentRows.contains(id))) return
        val recipients = runCatching { if (isMms) sentMms(context, id, now) else sentSms(context, id, now) }
            .getOrNull().orEmpty()
        if (recipients.isEmpty()) return
        synchronized(handled) {
            handled += key
            while (handled.size > MAX_HANDLED) handled.remove(handled.first())
        }
        WroteToGroup.join(context, recipients)
    }

    /**
     * The recipient of SMS [id] when it is outgoing, recent, not a forward and not written by uMessage itself (D-5:
     * its creator column, which survives a restart); else empty. All columns are read, as not every store has creator.
     */
    private fun sentSms(context: Context, id: Long, now: Long): List<String> = context.contentResolver.query(
        Telephony.Sms.CONTENT_URI,
        null,
        "${Telephony.Sms._ID} = ?",
        arrayOf(id.toString()),
        null,
    )?.use { cursor ->
        fun column(name: String) = cursor.getColumnIndexOrThrow(name)
        val usable = cursor.moveToFirst() && cursor.getInt(column(Telephony.Sms.TYPE)) in OUTGOING_SMS &&
            isRecent(cursor.getLong(column(Telephony.Sms.DATE)), now) && !isOwn(context, cursor) &&
            !ForwardFormat.isForward(cursor.getString(column(Telephony.Sms.BODY)).orEmpty())
        val address = if (usable) cursor.getString(column(Telephony.Sms.ADDRESS))?.trim().orEmpty() else ""
        if (usable && address.isNotEmpty() && MessagingUtils.ADDRESS_SEPARATOR !in address) listOf(address) else null
    }.orEmpty()

    /** The recipient of MMS [id] when it is sent or in the outbox, recent and to one person; else empty. */
    private fun sentMms(context: Context, id: Long, now: Long): List<String> {
        val usable = context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            null,
            "${Telephony.Mms._ID} = ?",
            arrayOf(id.toString()),
            null,
        )?.use { cursor ->
            cursor.moveToFirst() &&
                cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)) in OUTGOING_MMS &&
                isRecent(cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)) * MS_PER_SECOND, now) &&
                !isOwn(context, cursor)
        } ?: false
        if (!usable) return emptyList()
        val recipients = ArrayList<String>()
        context.contentResolver.query(
            Uri.parse("content://mms/$id/addr"),
            arrayOf(Telephony.Mms.Addr.ADDRESS),
            "${Telephony.Mms.Addr.TYPE} IN ($PDU_TO,$PDU_CC,$PDU_BCC)",
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.trim()?.takeIf { it.isNotEmpty() && it != MMS_NO_ADDRESS }?.let(recipients::add)
            }
        }
        // A group MMS thread makes no member (6.3).
        return recipients.distinct().takeIf { it.size == 1 }.orEmpty()
    }

    /** Tests only: waits until every row queued so far was looked at. */
    @VisibleForTesting
    internal fun drain() {
        executor.schedule(Runnable {}, 0L, TimeUnit.MILLISECONDS).get(DRAIN_SECONDS, TimeUnit.SECONDS)
    }

    /** Tests only: forgets the rows handled so far (row ids start again in every test's store). */
    @VisibleForTesting
    internal fun resetForTest() = synchronized(handled) { handled.clear() }

    /** D-5: the row's creator column names this app (SMS and MMS share the column name). */
    private fun isOwn(context: Context, cursor: Cursor): Boolean {
        val index = cursor.getColumnIndex(Telephony.Sms.CREATOR)
        return index >= 0 && cursor.getString(index) == context.packageName
    }

    private fun isRecent(date: Long, now: Long): Boolean = now - date in -RECENT_MS..RECENT_MS

    private val OUTGOING_SMS = setOf(
        Telephony.Sms.MESSAGE_TYPE_SENT,
        Telephony.Sms.MESSAGE_TYPE_OUTBOX,
        Telephony.Sms.MESSAGE_TYPE_QUEUED,
        Telephony.Sms.MESSAGE_TYPE_FAILED,
    )
    private val OUTGOING_MMS = setOf(Telephony.Mms.MESSAGE_BOX_SENT, Telephony.Mms.MESSAGE_BOX_OUTBOX)

    // PDU header field ids for the recipient lists (PduHeaders.TO / CC / BCC), as in SystemContactChecker.
    private const val PDU_TO = 151
    private const val PDU_CC = 130
    private const val PDU_BCC = 129
    private const val MMS_NO_ADDRESS = "insert-address-token"
}
