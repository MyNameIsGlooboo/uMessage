package com.glooboo.umessage.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.glooboo.umessage.ui.onboarding.OnboardingActivity
import com.glooboo.umessage.ui.onboarding.OnboardingState

/**
 * Routes to onboarding or the list and finishes at once. No longer the launcher entry: the `.Green` launcher alias
 * opens MainActivity directly (one activity start less), which keeps the splash until its list is ready. Kept
 * because commons enables and disables launcher components by the `SplashActivity` class name. Fossify's
 * BaseSplashActivity is not used: it shows an anti-fake-app dialog for non-Fossify builds. The lint check is
 * suppressed because nothing here draws a launch screen of its own.
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val next = if (OnboardingState.isCompleted(this)) MainActivity::class.java else OnboardingActivity::class.java
        startActivity(Intent(this, next))
        finish()
    }
}
