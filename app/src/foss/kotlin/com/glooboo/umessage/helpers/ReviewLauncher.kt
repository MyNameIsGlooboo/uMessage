package com.glooboo.umessage.helpers

import android.app.Activity

/** The foss build has no review card and no Google Play code. */
@Suppress("UNUSED_PARAMETER", "UnusedParameter")
object ReviewLauncher {
    const val AVAILABLE = false

    fun launch(activity: Activity) = Unit
}
