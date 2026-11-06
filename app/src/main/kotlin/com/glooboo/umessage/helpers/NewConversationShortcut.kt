package com.glooboo.umessage.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import androidx.appcompat.content.res.AppCompatResources
import com.glooboo.umessage.R
import com.glooboo.umessage.activities.NewConversationActivity
import com.glooboo.umessage.extensions.config
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.convertToBitmap

/** The launcher's "New conversation" shortcut, drawn in the app icon's colour. */
object NewConversationShortcut {

    const val ID = "new_conversation"

    /** Adds or redraws the shortcut when the icon colour changed since it was last drawn. */
    @SuppressLint("NewApi")
    fun updateIfNeeded(context: Context) {
        val config = context.config
        val appIconColor = config.appIconColor
        if (config.lastHandledShortcutColor == appIconColor) return
        val manager = context.getSystemService(ShortcutManager::class.java)
        try {
            // Added or updated, not set: setting the list removed every conversation shortcut (MC-28).
            manager.addDynamicShortcuts(listOf(build(context, appIconColor)))
            config.lastHandledShortcutColor = appIconColor
        } catch (_: Exception) {
        }
    }

    @SuppressLint("NewApi")
    private fun build(context: Context, appIconColor: Int): ShortcutInfo {
        val label = context.getString(R.string.new_conversation)
        val drawable = AppCompatResources.getDrawable(context, org.fossify.commons.R.drawable.shortcut_plus)
        (drawable as LayerDrawable).findDrawableByLayerId(
            org.fossify.commons.R.id.shortcut_plus_background,
        ).applyColorFilter(appIconColor)

        val intent = Intent(context, NewConversationActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        return ShortcutInfo.Builder(context, ID)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(Icon.createWithBitmap(drawable.convertToBitmap()))
            .setIntent(intent)
            .setRank(0)
            .build()
    }
}
