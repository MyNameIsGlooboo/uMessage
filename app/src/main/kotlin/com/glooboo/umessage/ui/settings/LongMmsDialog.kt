package com.glooboo.umessage.ui.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.Config
import com.glooboo.umessage.messaging.LongTextMms
import com.glooboo.umessage.ui.components.ChoiceDialog
import com.glooboo.umessage.ui.components.localizeDigits
import java.util.Locale

/**
 * Q9: "Send long texts as picture message (MMS)". One radio per [LongTextMms.CHOICES]: "Never (default)" first, then
 * "After 2 messages" and so on, with the digits of [locale]. [selected] is the stored value; [onChoose] gets the chosen
 * one (a [LongTextMms.CHOICES] value).
 */
@Composable
internal fun LongMmsDialog(selected: Int, locale: Locale, onChoose: (Int) -> Unit, onDismiss: () -> Unit) {
    val never = stringResource(R.string.long_mms_choice_never)
    val options = LongTextMms.CHOICES.map { after ->
        if (after == LongTextMms.NEVER) {
            never
        } else {
            localizeDigits(stringResource(R.string.long_mms_after, after), locale)
        }
    }
    ChoiceDialog(
        title = stringResource(R.string.long_mms_title),
        options = options,
        selectedIndex = LongTextMms.CHOICES.indexOf(LongTextMms.sanitize(selected)),
        onSelect = { index -> onChoose(LongTextMms.CHOICES[index]) },
        onDismiss = onDismiss,
    )
}

/** The value shown under the setting: "Never" or "After 3 messages", with the digits of [locale]. */
internal fun longMmsLabel(context: Context, after: Int, locale: Locale): String =
    if (after == LongTextMms.NEVER) {
        context.getString(R.string.long_mms_value_never)
    } else {
        localizeDigits(context.getString(R.string.long_mms_after, after), locale)
    }

/** The dialog wired to the stored setting: a choice is saved, the dialog closes and [onChange] refreshes the page. */
@Composable
internal fun LongMmsSettingDialog(config: Config, locale: Locale, dismiss: () -> Unit, onChange: () -> Unit) {
    LongMmsDialog(
        selected = config.sendLongAsMmsAfterParts,
        locale = locale,
        onChoose = {
            dismiss()
            config.sendLongAsMmsAfterParts = it
            onChange()
        },
        onDismiss = dismiss,
    )
}
