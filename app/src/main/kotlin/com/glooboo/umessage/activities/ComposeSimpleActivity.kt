package com.glooboo.umessage.activities

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.glooboo.umessage.ui.theme.applyUMessageWindow

/**
 * Base for screens drawn entirely in Compose that still need [SimpleActivity] services (permissions, app lock,
 * recents icon). It opts out of the commons colour theming: the window, system bars and content all come from
 * [UMessageTheme], exactly like the Compose-only activities, so every screen looks the same.
 */
abstract class ComposeSimpleActivity : SimpleActivity() {
    init {
        useDynamicTheme = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyUMessageWindow()
    }

    protected fun setUMessageContent(content: @Composable () -> Unit) {
        setContent { UMessageTheme { content() } }
    }
}
