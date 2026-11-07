package com.glooboo.umessage.ui.spam

import android.content.Context
import com.glooboo.umessage.ui.settings.AppLocale
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.glooboo.umessage.ui.theme.applyUMessageWindow

/** The system block list (numbers blocked for SMS and calls), replacing the Fossify Commons screen. */
class BlockedNumbersActivity : ComponentActivity() {
    /** I1: puts the in-app language on this screen on Android 8 to 12 (a no-op on 13+). */
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        applyUMessageWindow()
        super.onCreate(savedInstanceState)
        setContent {
            UMessageTheme {
                BlockedNumbersRoute(onBack = ::finish)
            }
        }
    }
}
