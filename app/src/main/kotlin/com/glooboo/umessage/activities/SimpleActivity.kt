package com.glooboo.umessage.activities

import android.content.Context
import org.fossify.commons.activities.BaseSimpleActivity
import com.glooboo.umessage.ui.settings.AppLocale
import com.glooboo.umessage.R

open class SimpleActivity : BaseSimpleActivity() {
    /** I1: puts the in-app language on this screen on Android 8 to 12 (a no-op on 13+). */
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun getAppIconIDs() = arrayListOf(R.mipmap.ic_launcher)

    override fun getAppLauncherName() = getString(R.string.app_launcher_name)

    override fun getRepositoryName() = "Messages"
}
