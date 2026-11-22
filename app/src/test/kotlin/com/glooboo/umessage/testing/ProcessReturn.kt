package com.glooboo.umessage.testing

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import org.robolectric.Shadows.shadowOf

/**
 * H-21: drives the process lifecycle that `AppReturnWatch` listens to, so a test sees the app go to the background
 * ([leave]) and come back to the front ([comeBack]). Main thread only. A test that comes back calls [leave] in its
 * tear-down, so a watch started by a later test is not run at once.
 */
object ProcessReturn {
    private val registry get() = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry

    /** The app goes to the background (from any state). */
    fun leave() {
        idleMain()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        idleMain()
    }

    /** The app comes back to the front: only the start; the watches started before run their return. */
    fun comeBack() {
        // AppReturnWatch.start posts the observer to the main thread.
        idleMain()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        idleMain()
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()
}
