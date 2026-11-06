package com.glooboo.umessage.ui.spam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.glooboo.umessage.ui.theme.applyUMessageWindow

/** The system block list (numbers blocked for SMS and calls), replacing the Fossify Commons screen. */
class BlockedNumbersActivity : ComponentActivity() {
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
