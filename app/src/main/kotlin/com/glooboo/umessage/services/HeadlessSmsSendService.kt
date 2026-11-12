package com.glooboo.umessage.services

import android.app.Service
import android.content.Intent
import com.klinker.android.send_message.Settings
import org.fossify.commons.helpers.ensureBackgroundThread
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.subscriptionManagerCompat
import com.glooboo.umessage.messaging.notifySendFailedInBackground
import com.glooboo.umessage.messaging.sendMessageCompat

class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        // sms:, smsto:, mms: and mmsto: are all opaque URIs; the decoded scheme-specific part holds the recipients.
        val addresses = parseRecipients(intent?.data?.schemeSpecificPart)
        if (text.isNullOrEmpty() || addresses.isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // The send writes to the provider and may read attachments: keep it off the main thread. The started
        // service keeps the process alive until stopSelf.
        ensureBackgroundThread {
            try {
                send(text, addresses)
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Another app (the dialer's "respond via message") asked for this send, so the user is not in uMessage and a
     * failure toast is easily missed: a failed send also posts the "message not sent" notification.
     */
    internal fun send(text: String, addresses: List<String>) {
        var failed = false
        try {
            val subId = sendSubscriptionId(addresses.first())
            sendMessageCompat(text, addresses, subId, emptyList(), onFailure = { failed = true })
        } catch (_: Exception) {
            failed = true
        }
        if (failed) {
            runCatching { notifySendFailedInBackground(addresses) }
        }
    }

    /** The SIM saved for the first recipient if it is active, else the app default SIM, else the system default. */
    @Suppress("MissingPermission") // a SecurityException is caught by runCatching below
    private fun sendSubscriptionId(firstAddress: String): Int {
        val defaultSimId = config.defaultSimId
        val fallback = if (defaultSimId >= 0) defaultSimId else Settings.DEFAULT_SUBSCRIPTION_ID
        val saved = config.getUseSIMIdAtNumberOrNull(firstAddress) ?: return fallback
        return runCatching {
            val activeIds = subscriptionManagerCompat().activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }
            if (saved in activeIds) saved else fallback
        }.getOrDefault(fallback)
    }
}

/** Recipients from the scheme-specific part of an sms/smsto/mms/mmsto URI ("+1555,+1666?body=hi"). */
internal fun parseRecipients(schemeSpecificPart: String?): List<String> {
    return schemeSpecificPart.orEmpty()
        .removePrefix("//")
        .substringBefore('?')
        .split(',', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}
