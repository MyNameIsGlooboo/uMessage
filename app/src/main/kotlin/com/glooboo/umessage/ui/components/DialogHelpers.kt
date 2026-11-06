package com.glooboo.umessage.ui.components

import android.app.Activity
import com.glooboo.umessage.R
import org.fossify.commons.extensions.getPhoneNumberTypeText
import org.fossify.commons.models.PhoneNumber
import androidx.compose.runtime.remember

/**
 * Yes/no question for non-Compose callers. [onConfirm] runs after the dialog is gone; dismissing or the negative
 * button only closes it. [destructive] colours the confirm button as a warning.
 */
fun Activity.showConfirmDialog(
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    title: String? = null,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
) {
    showComposeDialog { dismiss ->
        ConfirmDialog(
            message = message,
            confirmLabel = confirmLabel,
            dismissLabel = dismissLabel,
            title = title,
            destructive = destructive,
            onConfirm = {
                dismiss()
                onConfirm()
            },
            onDismiss = dismiss,
        )
    }
}

/** One option of [showChoiceDialog]; [value] is handed back to the callback. */
data class ChoiceItem<T>(val title: String, val value: T)

/** Single-choice list for non-Compose callers: picking a row closes the dialog and reports its value. */
fun <T> Activity.showChoiceDialog(
    items: List<ChoiceItem<T>>,
    title: String? = null,
    selected: T? = null,
    onChoose: (T) -> Unit,
) {
    showComposeDialog { dismiss ->
        ChoiceDialog(
            options = items.map { it.title },
            selectedIndex = remember { items.indexOfFirst { it.value == selected } },
            title = title,
            onSelect = { index ->
                dismiss()
                onChoose(items[index].value)
            },
            onDismiss = dismiss,
        )
    }
}

/** Plain Yes / No question (the old confirmation dialog's defaults); [onConfirm] runs after the dialog is gone. */
fun Activity.askYesNo(message: String, onConfirm: () -> Unit) {
    showConfirmDialog(
        message = message,
        confirmLabel = getString(R.string.dialogs_yes),
        dismissLabel = getString(R.string.dialogs_no),
        onConfirm = onConfirm,
    )
}

/**
 * Lets the user pick one of a contact's numbers: a single number is returned right away, with [respectPrimary] the
 * primary number wins, otherwise a list of "number (type)" rows is shown.
 */
fun Activity.pickPhoneNumber(
    phoneNumbers: List<PhoneNumber>,
    respectPrimary: Boolean = false,
    callback: (PhoneNumber) -> Unit,
) {
    when {
        phoneNumbers.size == 1 -> callback(phoneNumbers.first())
        phoneNumbers.size > 1 -> {
            if (respectPrimary) {
                phoneNumbers.find { it.isPrimary }?.let {
                    callback(it)
                    return
                }
            }
            val items = phoneNumbers.map { number ->
                ChoiceItem(getString(R.string.ui_number_with_type, number.value, getPhoneNumberTypeText(number.type, number.label)), number)
            }
            showChoiceDialog(items, onChoose = callback)
        }
    }
}
