package com.glooboo.umessage.ui.spam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.SpamRepository

/** An active SIM as shown in chips. [slot] is 1-based. */
data class SimInfo(val subscriptionId: Int, val slot: Int, val carrier: String)

/** Reads active SIMs. Returns an empty list when permission is missing or the platform refuses. */
object SimDirectory {
    @SuppressLint("MissingPermission")
    fun load(context: Context): List<SimInfo> {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return emptyList()
        return try {
            val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
            manager.activeSubscriptionInfoList.orEmpty()
                .map { SimInfo(it.subscriptionId, it.simSlotIndex + 1, it.carrierName?.toString().orEmpty()) }
                .sortedBy { it.slot }
        } catch (_: SecurityException) {
            emptyList()
        }
    }
}

/**
 * Resolves the repository from the service locator. In debuggable builds, when no engine is installed yet,
 * falls back to [FakeSpamRepository] so the screens stay usable. Release builds never use the fake.
 */
object SpamRepositoryProvider {
    private val fake by lazy { FakeSpamRepository() }

    fun get(context: Context): SpamRepository = try {
        UMessage.repository(context)
    } catch (e: IllegalStateException) {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable) fake else throw e
    }
}
