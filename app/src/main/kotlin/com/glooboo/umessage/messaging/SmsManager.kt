package com.glooboo.umessage.messaging

import android.content.Context
import android.telephony.SmsManager
import com.klinker.android.send_message.Settings
import org.fossify.commons.helpers.isSPlus

/**
 * A fresh SmsManager for [subId] on every call (no shared cache, so concurrent sends on different SIMs can't get
 * each other's manager). [Settings.DEFAULT_SUBSCRIPTION_ID] means the system default SIM.
 */
@Suppress("DEPRECATION")
fun getSmsManager(context: Context, subId: Int): SmsManager {
    val useDefault = subId == Settings.DEFAULT_SUBSCRIPTION_ID
    return try {
        if (isSPlus()) {
            val manager = context.getSystemService(SmsManager::class.java)
            if (useDefault) manager else manager.createForSubscriptionId(subId)
        } else {
            if (useDefault) SmsManager.getDefault() else SmsManager.getSmsManagerForSubscriptionId(subId)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        SmsManager.getDefault()
    }
}
