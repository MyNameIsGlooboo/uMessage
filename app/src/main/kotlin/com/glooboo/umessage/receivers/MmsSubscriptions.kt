package com.glooboo.umessage.receivers

import android.content.Context
import android.content.Intent
import android.provider.Telephony.Mms
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import java.util.concurrent.ConcurrentHashMap

/** Extra the SMS/WAP push broadcasts carry the receiving SIM in; current and legacy names. */
private const val EXTRA_SUBSCRIPTION_INDEX = "android.telephony.extra.SUBSCRIPTION_INDEX"
private const val EXTRA_SUBSCRIPTION_LEGACY = "subscription"

/**
 * Subscription id of the SIM an SMS_DELIVER / WAP_PUSH_DELIVER broadcast arrived on, or
 * [SubscriptionManager.INVALID_SUBSCRIPTION_ID]. Prefers SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX (a literal
 * here because the constant is API 30+) and falls back to the legacy "subscription" extra some OEM builds still use.
 */
fun Intent.receivingSubscriptionId(): Int {
    val legacy = getIntExtra(EXTRA_SUBSCRIPTION_LEGACY, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
    return getIntExtra(EXTRA_SUBSCRIPTION_INDEX, legacy)
}

/**
 * Which SIM an incoming MMS arrived on, for the content filter that runs after download. mmslib does not pass the
 * subscription to [MmsReceiver]'s hooks, so this remembers it per sender when the WAP push comes in, and falls back
 * to the sub_id of the sender's pending notification-ind row in the provider (survives a process restart).
 */
internal object MmsSubscriptions {
    private const val TTL_MS = 60 * 60 * 1000L
    private const val MAX_ROWS = 10
    private const val PDU_FROM = 137 // PduHeaders.FROM, the addr row type of the sender
    private const val PDU_NOTIFICATION_IND = 130 // PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND

    private data class Seen(val subscriptionId: Int, val at: Long)

    private val bySender = ConcurrentHashMap<String, Seen>()

    fun remember(address: String, subscriptionId: Int, now: Long = System.currentTimeMillis()) {
        if (address.isBlank() || subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return
        bySender.entries.removeAll { now - it.value.at > TTL_MS }
        bySender[address] = Seen(subscriptionId, now)
    }

    fun find(context: Context, address: String): Int {
        if (address.isBlank()) return SubscriptionManager.INVALID_SUBSCRIPTION_ID
        val now = System.currentTimeMillis()
        val remembered = bySender.entries
            .filter { now - it.value.at <= TTL_MS && PhoneNumberUtils.compare(it.key, address) }
            .maxByOrNull { it.value.at }
        return remembered?.value?.subscriptionId ?: findInProvider(context, address)
    }

    private fun findInProvider(context: Context, address: String): Int {
        return try {
            val projection = arrayOf(Mms._ID, Mms.SUBSCRIPTION_ID)
            val selection = "${Mms.MESSAGE_TYPE} = ?"
            val args = arrayOf(PDU_NOTIFICATION_IND.toString())
            context.contentResolver.query(Mms.CONTENT_URI, projection, selection, args, "${Mms.DATE} DESC")?.use {
                var rows = 0
                while (it.moveToNext() && rows++ < MAX_ROWS) {
                    val id = it.getLong(0)
                    if (senderOf(context, id)?.let { from -> PhoneNumberUtils.compare(from, address) } == true) {
                        return it.getInt(1)
                    }
                }
                null
            } ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
        } catch (_: Exception) {
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
    }

    private fun senderOf(context: Context, mmsId: Long): String? {
        val uri = Mms.CONTENT_URI.buildUpon().appendPath(mmsId.toString()).appendPath("addr").build()
        return context.contentResolver.query(
            uri, arrayOf(Mms.Addr.ADDRESS), "${Mms.Addr.TYPE} = ?", arrayOf(PDU_FROM.toString()), null
        )?.use { if (it.moveToFirst()) it.getString(0)?.substringBefore("/TYPE=") else null }
    }
}
