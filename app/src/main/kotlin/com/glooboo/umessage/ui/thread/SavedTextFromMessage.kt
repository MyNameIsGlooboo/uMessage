package com.glooboo.umessage.ui.thread

import android.content.Context
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.SavedTextAdd
import com.glooboo.umessage.helpers.SavedTexts
import com.glooboo.umessage.helpers.composerPrefs
import org.fossify.commons.extensions.toast

/** "Save as saved text" on a selected message (I10): adds its text to the list and says how that went. */
fun saveAsSavedText(context: Context, text: String) {
    val message = when (SavedTexts(context.composerPrefs).add(title = "", text = text)) {
        SavedTextAdd.Added -> R.string.n06_saved_toast
        SavedTextAdd.Duplicate -> R.string.n06_save_duplicate
        SavedTextAdd.Full -> R.string.n06_save_full
        SavedTextAdd.Empty -> return
    }
    context.toast(message)
}
