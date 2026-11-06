package com.glooboo.umessage.spam.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.net.Uri
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.SimpleContactsHelper
import com.glooboo.umessage.extensions.config
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

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

    private class SentAddresses(val normalized: MutableSet<String>, val raw: MutableSet<String>)

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
            if (uri != null && messageRowId(uri) != null) {
                changedMessages.add(uri)
            } else {
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
     * FOUND when an outgoing SMS (sent, outbox, queued, failed) or a recent sent MMS went to [address]. The addresses are
     * read once with an indexed query on the message type and kept until the message store changes, so a later message
     * is answered from memory. UNKNOWN when the SMS store cannot be read, so the filter delivers rather than guess.
     */
    override fun sentTo(address: String): ContactLookup {
        if (address.isBlank() || normalizer.isSenderId(address)) return ContactLookup.NOT_FOUND
        val sent = sentAddresses() ?: return ContactLookup.UNKNOWN
        if (normalizer.normalize(address) in sent.normalized) return ContactLookup.FOUND
        // The normalized forms can differ when a number was typed without its country code in another region; the
        // platform comparison (same trailing digits, compatible prefixes) catches those.
        val loose = sent.raw.any { PhoneNumberUtils.compare(it, address) }
        return if (loose) ContactLookup.FOUND else ContactLookup.NOT_FOUND
    }

    private fun sentAddresses(): SentAddresses? = synchronized(sentLock) {
        val cached = sentCache
        if (cached != null && applyChangedMessages(cached)) return cached
        changedMessages.clear()
        val raw = loadSentSmsAddresses() ?: return null
        raw += loadSentMmsAddresses()
        return SentAddresses(raw.mapTo(HashSet()) { normalizer.normalize(it) }, raw).also { sentCache = it }
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
                sent.raw += address
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
        if (numbers != null && numbers.contains(address)) return ContactLookup.FOUND
        if (numbers != null) {
            // The address book was read and does not have it; only the app's private contacts are left. The system
            // PhoneLookup is not asked here: it matches on the last digits, so it took a stranger from another
            // country whose number ends like a contact's for that contact (DL-24).
            return if (inPrivateContacts(address)) ContactLookup.FOUND else ContactLookup.NOT_FOUND
        }
        val detail = runCatching {
            appContext.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use { privateCursor ->
                SimpleContactsHelper(appContext).existsSync(address, privateCursor)
            }
        }.getOrNull()
        return when {
            detail == ContactLookupResult.Found -> ContactLookup.FOUND
            detail == ContactLookupResult.NotFound -> ContactLookup.NOT_FOUND
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
                val index = ContactNumberIndex<Unit>(normalizer)
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
            val result = ContactNumberIndex<Unit>(normalizer)
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

class FossifyLegacyBlocklist(private val context: Context) : LegacyBlocklist {
    override fun isNumberBlocked(address: String) = context.isNumberBlocked(address)
    override fun blockedKeywords(): Collection<String> = context.config.blockedKeywords
    override fun blockUnknownNumbers() = context.baseConfig.blockUnknownNumbers
}
