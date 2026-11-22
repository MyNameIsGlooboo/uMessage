package com.glooboo.umessage.testing

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import org.robolectric.Shadows.shadowOf

/**
 * H-21: drives the process lifecycle that `AppReturnWatch` listens to, so a test sees the app go to the background
 * and come back to the front. Main thread only. It leaves the process in the background (CREATED), so a watch started
 * by a later test is not run at once.
 */
object ProcessReturn {
    private val registry get() = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry

    /** The app goes to the background and comes back once; the watches started before run their return. */
    fun comeBack() {
        // AppReturnWatch.start posts the observer to the main thread.
        shadowOf(Looper.getMainLooper()).idle()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
