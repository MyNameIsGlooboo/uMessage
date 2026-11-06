package com.glooboo.umessage.helpers

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONException
import org.json.JSONObject

/**
 * I27: asks Xiaomi's Focus notification (HyperOS and late MIUI) to show a verification code. Experimental and
 * private to Xiaomi: the phone may ignore it, and then the normal notification, with its "Copy code" action, is all
 * the user sees. Only on Xiaomi, Redmi and POCO phones, only for a code, only when the user turned it on and never
 * when the code is hidden on the lock screen. Android 16 Live Updates are not used: they need an ongoing
 * notification without the conversation style, which would trade the conversation for a sticky code.
 */
object FocusNotification {
    private const val TAG = "FocusNotification"
    private const val EXTRA_PARAM = "miui.focus.param"
    private const val BUSINESS = "umessage_code"
    private const val PROTOCOL = 1
    private const val BASE_INFO_TYPE = 1

    fun applies(enabled: Boolean, oem: Oem, hasCode: Boolean, codeHidden: Boolean): Boolean =
        enabled && oem == Oem.XIAOMI && hasCode && !codeHidden

    /** The Focus parameter JSON: the code as title and ticker, the sender as the second line. */
    fun paramJson(code: String, sender: String): String {
        val baseInfo = JSONObject()
            .put("type", BASE_INFO_TYPE)
            .put("title", code)
            .put("content", sender)
        val param = JSONObject()
            .put("protocol", PROTOCOL)
            .put("business", BUSINESS)
            .put("enableFloat", true)
            .put("updatable", true)
            .put("ticker", code)
            .put("baseInfo", baseInfo)
        return JSONObject().put("param_v2", param).toString()
    }

    /** Adds the Focus extras to [builder] when [applies]; any failure leaves the normal notification. */
    fun apply(
        context: Context,
        builder: NotificationCompat.Builder,
        code: String?,
        sender: String,
        codeHidden: Boolean,
    ) {
        val on = context.notifyPrefs.focusCodes
        if (code == null || !applies(on, OemHelper.detect(), hasCode = true, codeHidden = codeHidden)) return
        try {
            builder.addExtras(Bundle().apply { putString(EXTRA_PARAM, paramJson(code, sender)) })
        } catch (e: JSONException) {
            Log.w(TAG, "Could not build the Focus notification", e)
        }
    }
}
