package com.glooboo.umessage.helpers

import android.os.StrictMode
import com.glooboo.umessage.BuildConfig

/** I28: StrictMode in debug builds only. It logs (never crashes) disk and network use on the main thread and leaks. */
object DebugStrictMode {
    fun install() {
        if (!BuildConfig.DEBUG) return
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        val vm = StrictMode.VmPolicy.Builder()
            .detectLeakedClosableObjects()
            .detectLeakedSqlLiteObjects()
            .penaltyLog()
            .build()
        StrictMode.setVmPolicy(vm)
    }
}
