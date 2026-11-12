package com.glooboo.umessage.ui.spam

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * The phone numbers of this phone's own SIMs, as far as Android tells us (often it does not). Used to refuse a forward
 * target that is the phone itself, which would loop. Nothing is asked of the user and no permission is added to the
 * manifest (spamui-17): every source is tried and one that Android refuses is skipped, so the list is simply shorter,
 * or empty, when the phone keeps its numbers to itself. The default SMS app may read them through READ_SMS.
 */
object ForwardOwnNumbers {
    @SuppressLint("MissingPermission")
    fun load(context: Context): List<String> {
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val subscriptions = guarded { manager.activeSubscriptionInfoList.orEmpty() }.orEmpty()
        val numbers = subscriptions.mapNotNull { info -> numberOf(manager, info) }
        val line = guarded { context.getSystemService(TelephonyManager::class.java)?.line1Number }
        return (numbers + listOfNotNull(line)).filter { it.isNotBlank() }.distinct()
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun numberOf(manager: SubscriptionManager, info: SubscriptionInfo): String? {
        val modern = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            guarded { manager.getPhoneNumber(info.subscriptionId) }
        } else {
            null
        }
        return modern?.takeIf { it.isNotBlank() } ?: info.number?.takeIf { it.isNotBlank() }
    }

    /** Runs [read]; null when Android refuses (no permission) or fails. */
    private fun <T> guarded(read: () -> T): T? = try {
        read()
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}
