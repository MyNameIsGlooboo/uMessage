package com.glooboo.umessage.helpers

/**
 * Tap to copy: whether a tap on a message notification copies the code instead of opening the chat. Pure, so the rule
 * can be tested without a notification.
 *
 * Only a notification of the "Verification codes" channel does it. A message goes there when it holds a code
 * ([OtpCodes]) and its chat is neither muted nor set up with notification settings of its own; any other message,
 * also one that merely contains a code but sits in another channel, keeps "tap opens the chat".
 */
internal object CodeTap {

    /** Whether the notification of a message with [code] is posted to the "Verification codes" channel. */
    fun inCodeChannel(code: String?, muted: Boolean, hasCustomNotifications: Boolean): Boolean =
        code != null && !muted && !hasCustomNotifications

    /** Whether a tap copies [code]: the setting is on ([enabled]) and the notification is in the code channel. */
    fun copiesOnTap(code: String?, enabled: Boolean, inCodeChannel: Boolean): Boolean =
        enabled && inCodeChannel && !code.isNullOrEmpty()
}
