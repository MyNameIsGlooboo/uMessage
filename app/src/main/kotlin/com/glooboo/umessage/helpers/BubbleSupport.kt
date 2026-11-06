package com.glooboo.umessage.helpers

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import com.glooboo.umessage.activities.BubbleThreadActivity
import org.fossify.commons.extensions.baseConfig

/** What [BubbleSupport] looks at for one notification. */
data class BubbleState(
    val enabled: Boolean,
    val sdk: Int,
    val appLockOn: Boolean,
    val hidden: Boolean,
    /** A quiet notification (muted chat, silent unknown sender) or a sender that cannot be answered. */
    val quietOrNoReply: Boolean,
)

/**
 * I53: lets a conversation notification float as an Android bubble that opens the thread. Off until the user turns it
 * on, not offered below Android 11, never while the app lock is on (the bubble would show a thread behind the lock)
 * and never for hidden chats.
 */
object BubbleSupport {
    private const val BUBBLE_HEIGHT_DP = 600
    private const val REQUEST_SALT = 0x0B0B1E

    /** True when the setting can be offered: Android 11 or newer. */
    fun supported(sdk: Int): Boolean = sdk >= Build.VERSION_CODES.R

    /** True when the setting can be switched on right now. */
    fun available(sdk: Int, appLockOn: Boolean): Boolean = supported(sdk) && !appLockOn

    fun allowed(state: BubbleState): Boolean = state.enabled && available(state.sdk, state.appLockOn) &&
        !state.hidden && !state.quietOrNoReply

    /** Adds bubble data to [builder] when [allowed]. The builder must also get the conversation shortcut. */
    fun attach(
        context: Context,
        builder: NotificationCompat.Builder,
        threadId: Long,
        title: String,
        quietOrNoReply: Boolean,
    ) {
        val state = BubbleState(
            enabled = context.notifyPrefs.chatBubbles,
            sdk = Build.VERSION.SDK_INT,
            appLockOn = context.baseConfig.isAppPasswordProtectionOn,
            hidden = false,
            quietOrNoReply = quietOrNoReply,
        )
        if (!allowed(state)) return
        val intent = Intent(context, BubbleThreadActivity::class.java).putExtra(THREAD_ID, threadId)
        val pending = PendingIntent.getActivity(
            context,
            threadId.hashCode() xor REQUEST_SALT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val icon = IconCompat.createWithAdaptiveBitmap(avatarBitmap(context, title, adaptive = true))
        builder.setBubbleMetadata(
            NotificationCompat.BubbleMetadata.Builder(pending, icon)
                .setDesiredHeight(BUBBLE_HEIGHT_DP)
                .setAutoExpandBubble(false)
                .setSuppressNotification(false)
                .build()
        )
    }
}
