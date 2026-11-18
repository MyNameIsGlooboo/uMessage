package com.glooboo.umessage.ui.setup

import android.content.Context
import android.content.Intent
import com.glooboo.umessage.activities.SettingsActivity
import com.glooboo.umessage.ui.settings.SettingsPage

/** Opens Settings straight on the Setup and permissions page; Back returns to the caller. */
fun Context.setupScreenIntent(): Intent =
    Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, SettingsPage.Setup.name)
