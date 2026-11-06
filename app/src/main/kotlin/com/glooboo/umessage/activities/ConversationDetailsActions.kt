package com.glooboo.umessage.activities

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.provider.Settings
import com.glooboo.umessage.extensions.getContactFromAddress
import com.glooboo.umessage.extensions.startContactDetailsIntent
import com.glooboo.umessage.helpers.SEARCHED_MESSAGE_ID
import com.glooboo.umessage.helpers.SEARCHED_MESSAGE_IS_MMS
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.ForwardingAvailability
import com.glooboo.umessage.ui.spam.ForwardingActivity
import org.fossify.commons.extensions.launchActivityIntent
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.KEY_PHONE
import org.fossify.commons.models.SimpleContact

/*
 * What the Conversation details page opens or changes outside itself: other screens, the address book and the chat's
 * own notification channel. Kept apart from [ConversationDetailsActivity] so the activity holds only its state.
 */

/** CL-23: opens chat [threadId] at [message], replacing a chat already open below this page so Back does not stack. */
internal fun Activity.openThreadAt(threadId: Long, message: Message) {
    Intent(this, ThreadActivity::class.java).apply {
        putExtra(THREAD_ID, threadId)
        putExtra(SEARCHED_MESSAGE_ID, message.id)
        putExtra(SEARCHED_MESSAGE_IS_MMS, message.isMMS)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(this)
    }
}

/**
 * ID-50: the forwarding screen, with a rule sheet for [sender] filled in.
 */
internal fun Activity.openForwardRulesFor(sender: String) {
    if (!ForwardingAvailability.isAvailable(this)) return
    startActivity(ForwardingActivity.intent(this, sender))
}

/** The system "add or edit contact" screen with [number] filled in. */
internal fun Activity.addNumberToContacts(number: String) {
    if (number.isBlank()) return
    Intent().apply {
        action = Intent.ACTION_INSERT_OR_EDIT
        type = "vnd.android.cursor.item/contact"
        putExtra(KEY_PHONE, number)
        launchActivityIntent(this)
    }
}

/** The address-book entry of a group member, when the member's first number is saved. */
internal fun Activity.openContactOf(contact: SimpleContact) {
    val address = contact.phoneNumbers.firstOrNull()?.normalizedNumber ?: return
    getContactFromAddress(address) { simpleContact ->
        if (simpleContact != null) {
            startContactDetailsIntent(simpleContact)
        }
    }
}

/** Android's settings page for the notification channel of chat [threadId]. */
internal fun Activity.openThreadChannelSettings(threadId: Long) {
    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        putExtra(Settings.EXTRA_CHANNEL_ID, threadId.toString())
        startActivity(this)
    }
}

/** A notification channel of its own for chat [threadId] ("Custom notifications"), named [name]. */
internal fun Context.createThreadNotificationChannel(threadId: Long, name: String?) {
    val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setLegacyStreamType(AudioManager.STREAM_NOTIFICATION)
        .build()

    NotificationChannel(threadId.toString(), name, NotificationManager.IMPORTANCE_HIGH).apply {
        setBypassDnd(false)
        enableLights(true)
        setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            audioAttributes,
        )
        enableVibration(true)
        notificationManager.createNotificationChannel(this)
    }
}

internal fun Context.removeThreadNotificationChannel(threadId: Long) {
    notificationManager.deleteNotificationChannel(threadId.toString())
}
