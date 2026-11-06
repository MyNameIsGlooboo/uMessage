package com.glooboo.umessage.ui.spam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/**
 * The phone numbers of this phone's own SIMs, as far as Android tells us (often it does not). Used to refuse a forward
 * target that is the phone itself, which would loop. Empty when permission is missing or the platform returns nothing.
 */
object ForwardOwnNumbers {
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun load(context: Context): List<String> {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return emptyList()
        return try {
            val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
            manager.activeSubscriptionInfoList.orEmpty().mapNotNull { info -> info.number?.takeIf { it.isNotBlank() } }
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }
    }
}
