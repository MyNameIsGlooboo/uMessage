package com.glooboo.umessage.helpers

import android.app.Activity
import com.google.android.play.core.review.ReviewManagerFactory

/** The Google Play build: Google's In-App Review card, shown by the Play Store app. Every failure is silent. */
object ReviewLauncher {
    const val AVAILABLE = true

    fun launch(activity: Activity) {
        runCatching {
            val manager = ReviewManagerFactory.create(activity)
            manager.requestReviewFlow().addOnCompleteListener { request ->
                runCatching {
                    if (request.isSuccessful && !activity.isFinishing && !activity.isDestroyed) {
                        manager.launchReviewFlow(activity, request.result)
                    }
                }
            }
        }
    }
}
