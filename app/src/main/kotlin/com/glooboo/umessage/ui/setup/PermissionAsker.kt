package com.glooboo.umessage.ui.setup

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.MutableState
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.glooboo.umessage.helpers.W3cPrefs
import com.glooboo.umessage.ui.onboarding.findActivity
import com.glooboo.umessage.ui.onboarding.openAppSettings

/**
 * True when a refusal means Android will not ask again. A refusal with a rationale still due is an ordinary "no". With
 * no rationale after it, the refusal is "don't ask again" when a rationale was due before the request, or when the
 * permission was asked for at least once before ([askedBefore], also in onboarding) and the answer came back at once
 * ([answeredAtOnce]: Android shows no dialog for a blocked permission, while a person needs a moment): that covers a
 * permission blocked before the screen opened. A dialog closed by Back or by a tap outside is none of these.
 */
internal fun isPermanentlyDenied(
    granted: Boolean,
    rationaleBefore: Boolean,
    rationaleAfter: Boolean,
    askedBefore: Boolean,
    answeredAtOnce: Boolean,
): Boolean = !granted && !rationaleAfter && (rationaleBefore || (askedBefore && answeredAtOnce))

/** An answer faster than this cannot come from a person reading the dialog: Android answered without showing it. */
internal const val INSTANT_ANSWER_MS = 400L

internal fun activityRationale(context: Context, permission: String): Boolean {
    val activity = context.findActivity() ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
}

/**
 * Asks for permissions and remembers (across a rotation too) the ones Android will not ask for again, see
 * [isPermanentlyDenied]. [rationale] is Android's "should show rationale" answer, replaceable in tests.
 */
internal class PermissionAsker(
    private val context: Context,
    private val permanentlyDenied: MutableState<Set<String>>,
    private val rationaleBefore: MutableState<Set<String>>,
    private val prefs: W3cPrefs = W3cPrefs.get(context),
    private val rationale: (String) -> Boolean = { activityRationale(context, it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var askedAt = 0L

    var launch: (Array<String>) -> Unit = {}

    val denied: Set<String> get() = permanentlyDenied.value

    fun request(permissions: List<String>) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return
        if (missing.all { it in denied }) {
            context.openAppSettings()
            return
        }
        rationaleBefore.value = missing.filter(rationale).toSet()
        askedAt = now()
        launch(missing.toTypedArray())
    }

    fun onResult(result: Map<String, Boolean>) {
        val asked = prefs.askedPermissions
        val atOnce = now() - askedAt < INSTANT_ANSWER_MS
        val blocked = result.filter { (perm, ok) ->
            isPermanentlyDenied(ok, perm in rationaleBefore.value, rationale(perm), perm in asked, atOnce)
        }
        permanentlyDenied.value = permanentlyDenied.value + blocked.keys
        prefs.markPermissionsAsked(result.keys)
    }
}
