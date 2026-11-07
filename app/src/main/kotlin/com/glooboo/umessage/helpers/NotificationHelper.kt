package com.glooboo.umessage.helpers

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telephony.SubscriptionManager
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.ensureBackgroundThread
import com.glooboo.umessage.R
import com.glooboo.umessage.activities.ThreadActivity
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.shortcutHelper
import com.glooboo.umessage.extensions.subscriptionManagerCompat
import com.glooboo.umessage.messaging.isShortCodeWithLetters
import com.glooboo.umessage.activities.CopyCodeActivity
import com.glooboo.umessage.activities.LinkOpenActivity
import com.glooboo.umessage.receivers.BlockSenderReceiver
import com.glooboo.umessage.spam.data.SpamSettingsStore
import com.glooboo.umessage.receivers.DeleteSmsReceiver
import com.glooboo.umessage.receivers.DirectReplyReceiver
import com.glooboo.umessage.receivers.MarkAsReadReceiver
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.conversations.initialsOf
import com.glooboo.umessage.ui.theme.currentColorScheme
import kotlin.math.roundToInt

class NotificationHelper(private val context: Context) {

    private val notificationManager = context.notificationManager
    private val soundUri get() = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    private val user = Person.Builder()
        .setName(context.getString(R.string.me))
        .build()

    @SuppressLint("NewApi")
    fun showMessageNotification(
        messageId: Long,
        isMms: Boolean,
        address: String,
        body: String,
        threadId: Long,
        bitmap: Bitmap?,
        sender: String?,
        alertOnlyOnce: Boolean = false,
        subscriptionId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
        notify: NotifyContext = NotifyContext(),
        offerSmartReplies: Boolean = true,
    ) {
        if (notify.mode == NotifyMode.NONE) return
        // N05: a muted chat (codes included) goes to the quiet channel, whatever else it has set.
        val muted = MutePrefs.get(context).isMuted(threadId)
        // B06: a hidden chat shows "New message" and nothing else.
        if (HiddenChats.get(context).isMarked(threadId)) {
            HiddenChatNotification(context) { id, name -> createChannel(id, name) }.post(threadId, muted, alertOnlyOnce)
            return
        }
        val hasCustomNotifications =
            context.config.customNotifications.contains(threadId.toString())
        val otpCode = OtpCodeExtractor.extract(body)
        // I03: an unknown sender's message goes to a low-importance channel. A code, or a conversation with its own
        // notification settings, keeps its channel.
        val silent = muted || (notify.mode == NotifyMode.SILENT && !hasCustomNotifications && otpCode == null)
        // ID-26: codes get their own channel (own sound and vibration in Android settings). A conversation with its
        // own notification settings keeps them. Per-SIM channel only on dual-SIM phones; single-SIM keeps the original.
        val simChannel =
            if (hasCustomNotifications || otpCode != null || silent) null else getSimChannel(subscriptionId)
        val notificationChannelId = when {
            muted -> MutedChannel.ensure(context)
            hasCustomNotifications -> threadId.toString()
            otpCode != null -> ensureCodeChannel(context)
            silent -> ensureSilentChannel(context)
            simChannel != null -> simChannel.first
            else -> NOTIFICATION_CHANNEL_ID
        }
        if (!hasCustomNotifications && otpCode == null && !silent) {
            createChannel(
                notificationChannelId,
                simChannel?.second ?: context.getString(R.string.channel_received_sms)
            )
        }

        val notificationId = threadId.hashCode()
        val contentIntent = Intent(context, ThreadActivity::class.java).apply {
            putExtra(THREAD_ID, threadId)
        }
        val contentPendingIntent =
            PendingIntent.getActivity(
                context,
                notificationId,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        val markAsReadIntent = Intent(context, MarkAsReadReceiver::class.java).apply {
            action = MARK_AS_READ
            putExtra(THREAD_ID, threadId)
        }
        val markAsReadPendingIntent =
            PendingIntent.getBroadcast(
                context,
                notificationId,
                markAsReadIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        val deleteSmsIntent = Intent(context, DeleteSmsReceiver::class.java).apply {
            putExtra(THREAD_ID, threadId)
            putExtra(MESSAGE_ID, messageId)
            putExtra(IS_MMS, isMms)
        }
        val deleteSmsPendingIntent =
            PendingIntent.getBroadcast(
                context,
                notificationId,
                deleteSmsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        var replyAction: NotificationCompat.Action? = null
        val isNoReplySms = isShortCodeWithLetters(address)
        if (!isNoReplySms) {
            val replyLabel = context.getString(R.string.reply)
            // Suggested replies for this message are the chips under it; with none, the saved quick replies.
            val chips = if (offerSmartReplies) {
                val gate = NotificationSmartReplies.Gate(
                    isMms = isMms, isGroup = notify.isGroup, knownSender = notify.knownSender, hiddenChat = false,
                )
                NotificationSmartReplies(context).replies(address, body, gate, messageId)
            } else {
                emptyList()
            }
            val remoteInput = RemoteInput.Builder(REPLY)
                .setLabel(replyLabel)
                .setChoices(chips.map { it as CharSequence }.toTypedArray().takeIf { it.isNotEmpty() }
                    ?: NotificationQuickReplies.choices(context))
                .build()

            val replyIntent = Intent(context, DirectReplyReceiver::class.java).apply {
                putExtra(THREAD_ID, threadId)
                putExtra(THREAD_NUMBER, address)
            }

            val replyPendingIntent =
                PendingIntent.getBroadcast(
                    context.applicationContext,
                    notificationId,
                    replyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
            replyAction = NotificationCompat.Action.Builder(
                R.drawable.ic_send_vector,
                replyLabel,
                replyPendingIntent
            )
                .addRemoteInput(remoteInput)
                .setAuthenticationRequired(true)
                .build()
        }

        val largeIcon = bitmap ?: sender?.let { avatarBitmap(context, it) }
        val lockScreen = context.config.lockScreenVisibilitySetting
        val hideCode = otpCode != null && runCatching { context.w3aPrefs.hideCodesOnLockScreen }.getOrDefault(false)
        // M1: with Android's default lock-screen setting ("show all notification content") the private notification
        // is drawn on the lock screen too, so hiding the code only in the public version hides nothing. The posted
        // text itself carries the code as bullets; "Copy code" (behind the unlock) and the thread have the real one.
        val shownBody = if (hideCode) CodeMasker.mask(body) else body
        // An unsaved sender is titled as the inbox writes it ("555-2468", Persian digits in Persian). Display only:
        // the reply, the avatar and the shortcut keep the stored number.
        val shownSender = sender?.let { displayNumber(context, it) }
        val builder = NotificationCompat.Builder(context, notificationChannelId).apply {
            // The notification carries the message: it is what the shade shows once the phone is unlocked. What a
            // secure lock screen shows is decided by the visibility and the public version below.
            setLargeIcon(largeIcon)
            setStyle(getMessagesStyle(address, shownBody, notificationId, shownSender, largeIcon, notify.isGroup))
            setContentTitle(shownSender)
            setContentText(shownBody)

            color = currentColorScheme(context).primary.toArgb()
            setSmallIcon(R.drawable.ic_messenger)
            setContentIntent(contentPendingIntent)
            priority = if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MAX
            setDefaults(Notification.DEFAULT_LIGHTS)
            setCategory(Notification.CATEGORY_MESSAGE)
            if (lockScreen == LOCK_SCREEN_SENDER_MESSAGE && !hideCode) {
                setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            } else {
                setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                // "Hide codes on lock screen" (ID-25): with "sender and message" the lock screen still shows the
                // text, but with the code as bullets. The code and the "Copy code" action wait behind the unlock.
                val shown = if (lockScreen == LOCK_SCREEN_SENDER_MESSAGE) shownBody else null
                setPublicVersion(buildPublicVersion(notificationChannelId, shownSender, lockScreen, shown))
            }
            setAutoCancel(true)
            setOnlyAlertOnce(alertOnlyOnce)
            if (!silent) setSound(soundUri, AudioManager.STREAM_NOTIFICATION)
        }

        // Reply, "Copy code" and Delete live on the private notification, which a secure lock screen replaces by
        // the public version (no actions) unless the user picked "sender and message". They also ask the
        // system to unlock the phone first (Android 12+), so a locked phone can't reply, copy the code or delete.
        if (replyAction != null) {
            builder.addAction(replyAction)
        }

        if (otpCode != null) {
            // N6: an invisible activity of its own task copies the code without opening the app (or its PIN
            // screen). An activity, not a broadcast: MIUI / HyperOS drop clipboard writes from the background.
            val copyPendingIntent = PendingIntent.getActivity(
                context,
                notificationId xor COPY_CODE_REQUEST_SALT,
                CopyCodeActivity.intent(context, otpCode),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    org.fossify.commons.R.drawable.ic_copy_vector,
                    context.getString(R.string.onb_notif_copy_code),
                    copyPendingIntent
                )
                    .setAuthenticationRequired(true)
                    .build()
            )
            // The system adds its own "copy" chips for codes it spots (often two identical ones on top of
            // ours). Keep only our action, which also follows the lock-screen rule above.
            builder.setAllowSystemGeneratedContextualActions(false)
            FocusNotification.apply(context, builder, otpCode, shownSender ?: address, hideCode)
        }

        // I06: unknown single senders can be blocked from here. I67: known senders get the first link of the text
        // when the user turned that on. A message with a code keeps Reply, Copy code and Mark as read (Android shows
        // three). Both wait behind the unlock.
        var extraActions = 0
        if (otpCode == null && !notify.isGroup) {
            if (notify.unknownSender && address.isNotBlank()) {
                builder.addAction(blockAction(address, threadId, notificationId))
                extraActions++
            } else if (notify.knownSender) {
                openLinkAction(body, notificationId)?.let {
                    builder.addAction(it)
                    extraActions++
                }
            }
        }

        builder.addAction(
            org.fossify.commons.R.drawable.ic_check_vector,
            context.getString(R.string.mark_as_read),
            markAsReadPendingIntent
        )
            .setChannelId(notificationChannelId)
        if (isNoReplySms) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    org.fossify.commons.R.drawable.ic_delete_vector,
                    context.getString(org.fossify.commons.R.string.delete),
                    deleteSmsPendingIntent
                )
                    .setAuthenticationRequired(true)
                    .build()
            ).setChannelId(notificationChannelId)
        }

        BubbleSupport.attach(context, builder, threadId, shownSender ?: address, silent || isNoReplySms)
        var shortcut = context.shortcutHelper.getShortcut(threadId)
        if (shortcut == null) {
            ensureBackgroundThread {
                shortcut = context.shortcutHelper.createOrUpdateShortcut(threadId)
                builder.setShortcutInfo(shortcut)
                notificationManager.notify(notificationId, builder.build())
                ScreenWake.maybeWake(context, silent, alertOnlyOnce)
                context.shortcutHelper.reportReceiveMessageUsage(threadId)
            }
        } else {
            builder.setShortcutInfo(shortcut)
            notificationManager.notify(notificationId, builder.build())
            ScreenWake.maybeWake(context, silent, alertOnlyOnce)
            ensureBackgroundThread {
                context.shortcutHelper.reportReceiveMessageUsage(threadId)
            }
        }
    }

    private fun blockAction(address: String, threadId: Long, notificationId: Int): NotificationCompat.Action {
        val pending = PendingIntent.getBroadcast(
            context,
            notificationId xor BLOCK_REQUEST_SALT,
            BlockSenderReceiver.blockIntent(context, address, threadId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(0, context.getString(R.string.notif_block_action), pending)
            .setAuthenticationRequired(true)
            .build()
    }

    private fun openLinkAction(body: String, notificationId: Int): NotificationCompat.Action? {
        if (!runCatching { SpamSettingsStore(context).get().openLinkAction }.getOrDefault(false)) return null
        val link = firstWebLink(body) ?: return null
        val pending = PendingIntent.getActivity(
            context,
            notificationId xor OPEN_LINK_REQUEST_SALT,
            LinkOpenActivity.intent(context, link),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(0, context.getString(R.string.notif_open_link_action), pending)
            .setAuthenticationRequired(true)
            .build()
    }

    @SuppressLint("NewApi")
    /** [reason] replaces the generic "has not been sent" text when the cause is known. */
    fun showSendingFailedNotification(recipientName: String, threadId: Long, reason: String? = null) {
        // B06: a hidden chat's recipient is not named.
        val recipient =
            if (HiddenChats.get(context).isMarked(threadId)) context.getString(R.string.app_name) else recipientName
        val hasCustomNotifications =
            context.config.customNotifications.contains(threadId.toString())
        val notificationChannelId =
            if (hasCustomNotifications) threadId.toString() else NOTIFICATION_CHANNEL_ID
        if (!hasCustomNotifications) {
            createChannel(notificationChannelId, context.getString(R.string.message_not_sent_short))
        }

        // One per chat: repeated failures replace each other, and opening the chat or a successful retry removes it.
        val notificationId = NotificationIds.failedSend(threadId)
        val intent = Intent(context, ThreadActivity::class.java).apply {
            putExtra(THREAD_ID, threadId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val summaryText = reason ?: String.format(context.getString(R.string.message_sending_error), recipient)
        val largeIcon = avatarBitmap(context, recipient)
        val builder = NotificationCompat.Builder(context, notificationChannelId)
            .setContentTitle(context.getString(R.string.message_not_sent_short))
            .setContentText(summaryText)
            .setColor(currentColorScheme(context).primary.toArgb())
            .setSmallIcon(R.drawable.ic_messenger)
            .setLargeIcon(largeIcon)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summaryText))
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(Notification.DEFAULT_LIGHTS)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setChannelId(notificationChannelId)

        notificationManager.notify(notificationId, builder.build())
    }

    /**
     * Says that a scheduled message to [recipientName] went out [lateMinutes] late, and why. When exact alarms are
     * not allowed ([exactAlarmsAllowed] false) tapping it opens the "Alarms & reminders" setting, otherwise the chat.
     */
    @SuppressLint("NewApi")
    fun showScheduledSentLateNotification(
        recipientName: String, threadId: Long, lateMinutes: Long, exactAlarmsAllowed: Boolean,
    ) {
        NotificationChannel(
            SCHEDULED_LATE_CHANNEL_ID,
            context.getString(R.string.scheduled_sent_late_channel),
            // F3: a message that went out late is a quiet notice, not an alert.
            NotificationManager.IMPORTANCE_LOW
        ).also { notificationManager.createNotificationChannel(it) }

        val notificationId = NotificationIds.sentLate(threadId)
        val canAskForExact = !exactAlarmsAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val intent = if (canAskForExact) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(context, ThreadActivity::class.java).putExtra(THREAD_ID, threadId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = context.getString(
            if (canAskForExact) R.string.scheduled_sent_late_exact_off else R.string.scheduled_sent_late_idle,
            recipientName,
            lateMinutes.toInt()
        )
        val builder = NotificationCompat.Builder(context, SCHEDULED_LATE_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.scheduled_sent_late_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(currentColorScheme(context).primary.toArgb())
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
        notificationManager.notify(notificationId, builder.build())
    }

    /**
     * Says that the daily forward limit ([limit]) stopped a forward. Tapping it opens the Forwarded tab. Shown once a
     * day by the caller.
     */
    @SuppressLint("NewApi")
    fun showForwardLimitNotification(limit: Int) {
        NotificationChannel(
            FORWARD_LIMIT_CHANNEL_ID,
            context.getString(R.string.w3a_forward_limit_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        ).also { notificationManager.createNotificationChannel(it) }

        val text = context.resources.getQuantityString(R.plurals.w3a_forward_limit_text, limit, limit)
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            FORWARD_LIMIT_NOTIFICATION_ID,
            MainTabLinks.intent(context, MainTabLinks.TAB_FORWARDED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, FORWARD_LIMIT_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.w3a_forward_limit_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(currentColorScheme(context).primary.toArgb())
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentIntent(contentPendingIntent)
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
        notificationManager.notify(FORWARD_LIMIT_NOTIFICATION_ID, builder.build())
    }

    /** Channel id and display name for the SIM that received the message, or null if not dual-SIM. */
    private fun getSimChannel(subscriptionId: Int): Pair<String, String>? {
        if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return null
        val canReadSims = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!canReadSims) return null
        return try {
            val subs = context.subscriptionManagerCompat().activeSubscriptionInfoList.orEmpty()
            if (subs.size < 2) return null
            val info = subs.firstOrNull { it.subscriptionId == subscriptionId } ?: return null
            deleteLegacySimChannels()
            val slot = info.simSlotIndex + 1
            val carrier = info.carrierName?.toString().orEmpty().trim()
            val name = if (carrier.isEmpty()) {
                context.getString(R.string.onb_channel_sim_nocarrier, slot)
            } else {
                context.getString(R.string.onb_channel_sim, slot, carrier)
            }
            // Keyed by the physical slot: the subscription id changes whenever a SIM is swapped or re-inserted,
            // which used to pile up duplicate channels and lose the user's channel settings.
            "$SIM_CHANNEL_PREFIX${info.simSlotIndex}" to name
        } catch (_: Exception) {
            null
        }
    }

    /** Removes the old per-subscription-id SIM channels ("…_sim_<subId>"), replaced by per-slot ones. */
    private fun deleteLegacySimChannels() {
        if (legacySimChannelsDeleted) return
        notificationManager.notificationChannels
            .filter { it.id.startsWith(LEGACY_SIM_CHANNEL_PREFIX) }
            .forEach { notificationManager.deleteNotificationChannel(it.id) }
        legacySimChannelsDeleted = true
    }

    /**
     * What a secure lock screen shows instead of the message: "New message" under the sender's name with
     * [LOCK_SCREEN_SENDER], or the app name over "New message" with [LOCK_SCREEN_NOTHING]. Never empty.
     */
    private fun buildPublicVersion(
        channelId: String,
        sender: String?,
        lockScreen: Int,
        /** The message text with its code hidden, for "sender and message"; null = the generic "New message". */
        shownText: String? = null,
    ): Notification {
        val title = if ((lockScreen == LOCK_SCREEN_SENDER || shownText != null) && !sender.isNullOrBlank()) {
            sender
        } else {
            context.getString(R.string.app_name)
        }
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_messenger)
            .setColor(currentColorScheme(context).primary.toArgb())
            .setContentTitle(title)
            .setContentText(shownText ?: context.getString(R.string.new_message))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
    }

    private fun createChannel(id: String, name: String) {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setLegacyStreamType(AudioManager.STREAM_NOTIFICATION)
            .build()

        val importance = IMPORTANCE_HIGH
        NotificationChannel(id, name, importance).apply {
            setBypassDnd(false)
            enableLights(true)
            setSound(soundUri, audioAttributes)
            enableVibration(true)
            notificationManager.createNotificationChannel(this)
        }
    }

    private fun getMessagesStyle(
        address: String,
        body: String,
        notificationId: Int,
        name: String?,
        icon: Bitmap?,
        isGroup: Boolean,
    ): NotificationCompat.MessagingStyle {
        // A null name is the user's own reply, which MessagingStyle files under `user`. Everyone else gets the
        // app's avatar (or contact photo) as the Person icon: conversation notifications draw the sender's
        // Person icon, not the large icon, and fall back to a system letter tile without one.
        // N08: the name must never be blank: Android then files the message under `user` ("Me") and takes "Me" for
        // the title and the collapsed "name: text" line. It is the same text as the content title, so a one-to-one
        // notification never shows the number in two spellings.
        val sender = if (name != null) {
            Person.Builder()
                .setName(name)
                .setKey(address)
                .setIcon(IconCompat.createWithBitmap(icon ?: avatarBitmap(context, name)))
                .build()
        } else {
            null
        }

        return NotificationCompat.MessagingStyle(user).also { style ->
            style.isGroupConversation = isGroup
            getOldMessages(notificationId).forEach {
                style.addMessage(it)
            }
            val newMessage =
                NotificationCompat.MessagingStyle.Message(body, System.currentTimeMillis(), sender)
            style.addMessage(newMessage)
        }
    }

    private fun getOldMessages(notificationId: Int): List<NotificationCompat.MessagingStyle.Message> {
        val currentNotification =
            notificationManager.activeNotifications.find { it.id == notificationId }
        return if (currentNotification != null) {
            val activeStyle =
                NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(
                    currentNotification.notification
                )
            activeStyle?.messages.orEmpty()
        } else {
            emptyList()
        }
    }
}

/**
 * The conversation list's avatar as a bitmap: up to two initials, or the person silhouette for number-only
 * senders, on the same tone picked from the title (primary, tertiary or surface variant container).
 *
 * The plain form is a circle on a transparent square, sized like a notification large icon. The [adaptive] form
 * fills the whole square and keeps the content inside the adaptive-icon safe zone (72 of 108 dp), for
 * [IconCompat.createWithAdaptiveBitmap] (conversation shortcuts, which the system masks itself).
 */
internal fun avatarBitmap(context: Context, title: String, adaptive: Boolean = false): Bitmap {
    val size = if (adaptive) {
        (ADAPTIVE_AVATAR_DP * context.resources.displayMetrics.density).roundToInt()
    } else {
        context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
    }
    // Diameter of the visible circle: the whole square, or the safe zone of an adaptive icon.
    val visible = if (adaptive) size * ADAPTIVE_SAFE_FRACTION else size.toFloat()
    val scheme = currentColorScheme(context)
    val (container, content) = when (title.hashCode().mod(AVATAR_TONES)) {
        0 -> scheme.primaryContainer to scheme.onPrimaryContainer
        1 -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        else -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = container.toArgb() }
    if (adaptive) {
        canvas.drawColor(container.toArgb())
    } else {
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
    }
    val initials = initialsOf(title)
    val icon = if (initials.isEmpty()) AppCompatResources.getDrawable(context, R.drawable.ic_avatar_person) else null
    if (icon != null) {
        val inset = ((size - visible * AVATAR_ICON_FRACTION) / 2).roundToInt()
        icon.mutate().setTint(content.toArgb())
        icon.setBounds(inset, inset, size - inset, size - inset)
        icon.draw(canvas)
    } else {
        paint.color = content.toArgb()
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = visible * AVATAR_TEXT_FRACTION
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        canvas.drawText(initials, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2, paint)
    }
    return bitmap
}

private const val COPY_CODE_REQUEST_SALT = 0x0C0DE

/** ID-26: channel of messages with a verification code. */
const val CODE_CHANNEL_ID = "verification_codes"

/** Short double buzz: buzz, pause, buzz (ms). */
private const val CODE_BUZZ_MS = 180L
private const val CODE_PAUSE_MS = 120L
private val CODE_VIBRATION = longArrayOf(0L, CODE_BUZZ_MS, CODE_PAUSE_MS, CODE_BUZZ_MS)

/**
 * ID-26: the channel for messages that carry a verification code, so its sound and vibration can differ from other
 * messages (set in Android's channel settings, opened from Settings > Notifications > Code alert). High importance
 * with a short double vibration. Returns the channel id.
 */
fun ensureCodeChannel(context: Context): String {
    val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    val name = context.getString(R.string.settings_f_channel_codes)
    NotificationChannel(CODE_CHANNEL_ID, name, IMPORTANCE_HIGH).apply {
        description = context.getString(R.string.settings_f_channel_codes_desc)
        setBypassDnd(false)
        enableLights(true)
        setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), audioAttributes)
        enableVibration(true)
        vibrationPattern = CODE_VIBRATION
        context.notificationManager.createNotificationChannel(this)
    }
    return CODE_CHANNEL_ID
}

private const val SILENT_CHANNEL_ID = "unknown_senders_silent"
private const val BLOCK_REQUEST_SALT = 0x0B10C
private const val OPEN_LINK_REQUEST_SALT = 0x0090E

/** I03: low-importance channel of messages from unknown senders set to notify silently. Returns its id. */
fun ensureSilentChannel(context: Context): String {
    NotificationChannel(
        SILENT_CHANNEL_ID,
        context.getString(R.string.unotify_channel_silent),
        NotificationManager.IMPORTANCE_LOW
    ).also { context.notificationManager.createNotificationChannel(it) }
    return SILENT_CHANNEL_ID
}
private const val SCHEDULED_LATE_CHANNEL_ID = "scheduled_sent_late"
private const val FORWARD_LIMIT_CHANNEL_ID = "forward_daily_limit"
private const val FORWARD_LIMIT_NOTIFICATION_ID = 0x0F0D
private const val AVATAR_TONES = 3
private const val ADAPTIVE_AVATAR_DP = 108
private const val ADAPTIVE_SAFE_FRACTION = 72f / 108f

/** Same proportions as the list avatar: a 0.55 silhouette and titleMedium (16sp) initials on 48dp. */
private const val AVATAR_ICON_FRACTION = 0.55f
private const val AVATAR_TEXT_FRACTION = 1f / 3f
private const val SIM_CHANNEL_PREFIX = "${NOTIFICATION_CHANNEL_ID}_slot_"
private const val LEGACY_SIM_CHANNEL_PREFIX = "${NOTIFICATION_CHANNEL_ID}_sim_"

/** Once per process is enough: nothing creates the legacy channels any more. */
@Volatile
private var legacySimChannelsDeleted = false
