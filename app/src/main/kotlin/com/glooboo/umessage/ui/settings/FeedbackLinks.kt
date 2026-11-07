package com.glooboo.umessage.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** The "Rate uMessage" and "Send feedback" rows hand over to other apps. uMessage itself sends nothing. */
object FeedbackLinks {
    /** The public contact address named in PRIVACY.md. */
    const val FEEDBACK_EMAIL = "Glooboo@proton.me"
    const val ISSUES_URL = "https://github.com/MyNameIsGlooboo/uMessage/issues"

    /** The subject carries only the version, so the developer knows which build the note is about. */
    fun feedbackSubject(versionName: String): String = "uMessage feedback ($versionName)"

    /** An email to the developer: the address and subject are set, the body stays empty for the user to write. */
    fun feedbackIntent(versionName: String): Intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$FEEDBACK_EMAIL"))
        .putExtra(Intent.EXTRA_SUBJECT, feedbackSubject(versionName))

    fun issuesIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(ISSUES_URL))

    fun marketIntent(packageName: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))

    fun storeWebIntent(packageName: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))

    /** Runs the attempts in order until one returns true. False when none did. */
    fun firstThatWorks(attempts: List<() -> Boolean>): Boolean = attempts.any { it() }

    private fun Context.launch(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** The Play Store app first, the store web page if no Store handles the link. False when both fail. */
    fun rate(context: Context): Boolean = firstThatWorks(
        listOf(
            { context.launch(marketIntent(context.packageName)) },
            { context.launch(storeWebIntent(context.packageName)) },
        ),
    )

    /** An email app first, the issue tracker in the browser second. False when both fail (show the address). */
    fun sendFeedback(context: Context, versionName: String): Boolean = firstThatWorks(
        listOf(
            { context.launch(feedbackIntent(versionName)) },
            { context.launch(issuesIntent()) },
        ),
    )
}
