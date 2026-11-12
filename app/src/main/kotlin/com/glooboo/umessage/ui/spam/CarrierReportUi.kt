package com.glooboo.umessage.ui.spam

import android.content.Context
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.ConfirmDialog
import com.glooboo.umessage.ui.components.SettingsGroup
import com.glooboo.umessage.ui.components.SettingsRow
import com.glooboo.umessage.ui.components.TextInputDialog
import com.glooboo.umessage.ui.icons.Symbols
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * I32: the question before anything is sent. It says that this is an SMS, to which number, and that the carrier may
 * charge for it; nothing is sent until "Send" is tapped.
 */
@Composable
fun CarrierReportDialog(number: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.carrier_report_title),
        message = stringResource(R.string.carrier_report_body, number),
        confirmLabel = stringResource(R.string.carrier_report_send),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * Runs after the user confirmed: sends the newest message of the one selected sender, off the main thread, and says
 * on the snackbar whether it went out. Nothing about the text is logged.
 */
internal suspend fun reportToCarrier(
    context: Context,
    groups: List<SenderGroup>,
    selected: Set<String>,
    snackbar: SnackbarHostState,
) {
    val message = CarrierReport.pick(groups, selected)
    val sent = message != null && withContext(Dispatchers.IO) {
        runCatching { CarrierReport(context).send(message) }.getOrDefault(false)
    }
    val text = context.getString(if (sent) R.string.minor_sui_report_queued else R.string.carrier_report_failed)
    snackbar.showSnackbar(text, duration = SnackbarDuration.Short)
}

/** I32: the row on the Spam protection page where the carrier's spam number is entered. Empty until the user does. */
@Composable
fun CarrierReportGroup() {
    val context = LocalContext.current
    val report = remember(context) { CarrierReport(context) }
    var number by remember { mutableStateOf(report.number) }
    var editing by rememberSaveable { mutableStateOf(false) }
    SettingsGroup(stringResource(R.string.carrier_report_group)) {
        SettingsRow(
            title = stringResource(R.string.carrier_report_row_title),
            summary = stringResource(R.string.carrier_report_row_summary),
            value = number.ifEmpty { stringResource(R.string.carrier_report_not_set) },
            icon = Symbols.Warning,
            onClick = { editing = true },
            showChevron = true,
        )
    }
    if (editing) {
        val invalid = stringResource(R.string.carrier_report_invalid)
        TextInputDialog(
            title = stringResource(R.string.carrier_report_row_title),
            initial = number,
            supportingText = stringResource(R.string.carrier_report_hint),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            validate = { if (it.isEmpty() || CarrierReport.isValidNumber(it.trim())) null else invalid },
            onConfirm = {
                report.number = it
                number = report.number
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
}
