package com.glooboo.umessage.spam.engine

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Countries the owner's own numbers can plausibly be from: the country of every active SIM, of the network the phone
 * is on, and of the system language. A contact saved without a country code ("07712 345678") is read in the default
 * region, which changes with a foreign SIM or travel; the contact index also keys such a number in these regions, so it
 * still matches after the change. Upper-case ISO codes, never throws.
 */
@SuppressLint("MissingPermission")
fun plausibleRegions(context: Context): List<String> = runCatching {
    val telephony = context.getSystemService(TelephonyManager::class.java)
    val sims = runCatching {
        context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
            .mapNotNull { info -> subscriptionRegion(context, info.subscriptionId) }
    }.getOrDefault(emptyList())
    (sims + listOf(telephony?.simCountryIso, telephony?.networkCountryIso, Locale.getDefault().country))
        .mapNotNull { it?.trim()?.uppercase()?.ifEmpty { null } }
        .distinct()
}.getOrDefault(emptyList())
