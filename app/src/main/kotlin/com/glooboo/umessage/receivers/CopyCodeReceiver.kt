package com.glooboo.umessage.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.glooboo.umessage.activities.CopyCode
import com.glooboo.umessage.activities.CopyCodeActivity

/**
 * N6: the old notification "Copy code" action, a broadcast. New notifications start [CopyCodeActivity] instead, since
 * MIUI / HyperOS block clipboard writes from the background. Kept, registered, only for notifications posted before
 * that change: they survive an app update and their action still points here. Safe to remove a release or two later.
 */
class CopyCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COPY_CODE) return
        CopyCode.copy(context, intent.getStringExtra(EXTRA_CODE).orEmpty())
    }

    companion object {
        const val ACTION_COPY_CODE = "com.glooboo.spamless.action.COPY_CODE_BROADCAST"
        const val EXTRA_CODE = "extra_code"

        /** The intent the old notification action sent; explicit, so only this app's receiver gets the code. */
        fun intent(context: Context, code: String): Intent =
            Intent(context, CopyCodeReceiver::class.java).setAction(ACTION_COPY_CODE).putExtra(EXTRA_CODE, code)
    }
}
