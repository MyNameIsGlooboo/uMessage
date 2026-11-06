package com.glooboo.umessage.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * I31: whether a received bank text gets a small card with its amount, balance and card digits (see BankAlertParser).
 * On by default; in its own preferences file (`Config` keeps its preferences private).
 */
class BankCardPrefs private constructor(private val prefs: SharedPreferences) {

    var cardsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    companion object {
        private const val PREFS_NAME = "spamless_bank_cards"
        private const val KEY_ENABLED = "cards_enabled"

        fun get(context: Context): BankCardPrefs =
            BankCardPrefs(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
