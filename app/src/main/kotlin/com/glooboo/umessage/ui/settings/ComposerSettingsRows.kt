package com.glooboo.umessage.ui.settings

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.ComposerPrefs
import com.glooboo.umessage.helpers.SimNames
import com.glooboo.umessage.helpers.composerPrefs
import com.glooboo.umessage.ui.components.SettingsRow
import com.glooboo.umessage.ui.components.SettingsSwitchRow
import com.glooboo.umessage.ui.components.TextInputDialog
import com.glooboo.umessage.ui.components.showComposeDialog
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.thread.MessageSignature

/** The voice typing switch (I40) of the Typing group; it reads and writes [ComposerPrefs] itself. */
@Composable
internal fun ComposerTypingRows() {
    if (LocalInspectionMode.current) return
    val prefs = LocalContext.current.composerPrefs
    var voice by remember { mutableStateOf(prefs.voiceButton) }
    SettingsSwitchRow(
        title = stringResource(R.string.i40_setting_title),
        summary = stringResource(R.string.i40_setting_summary),
        icon = Symbols.Mic,
        checked = voice,
        onCheckedChange = {
            voice = it
            prefs.voiceButton = it
        },
    )
}

/** Rows of the Sending group: pictures without location (I09), signature (I60), sounds (I65), SIM names (I34). */
@Composable
internal fun ComposerSendingRows() {
    if (LocalInspectionMode.current) return
    val context = LocalContext.current
    val prefs = context.composerPrefs
    var removeLocation by remember { mutableStateOf(prefs.removePhotoLocation) }
    var signature by remember { mutableStateOf(prefs.signature) }
    var sounds by remember { mutableStateOf(prefs.chatSounds) }
    SettingsSwitchRow(
        title = stringResource(R.string.i09_setting_title),
        summary = stringResource(R.string.i09_setting_summary),
        icon = Symbols.PhotoCamera,
        checked = removeLocation,
        onCheckedChange = {
            removeLocation = it
            prefs.removePhotoLocation = it
        },
    )
    SettingsRow(
        title = stringResource(R.string.i60_setting_title),
        value = signature.ifEmpty { stringResource(R.string.i60_off) },
        icon = Symbols.Edit,
        onClick = {
            (context as? Activity)?.showSignatureDialog(signature) {
                signature = it
                prefs.signature = it
            }
        },
        showChevron = true,
    )
    SettingsSwitchRow(
        title = stringResource(R.string.i65_setting_title),
        summary = stringResource(R.string.i65_setting_summary),
        icon = Symbols.Notifications,
        checked = sounds,
        onCheckedChange = {
            sounds = it
            prefs.chatSounds = it
        },
    )
    SimNamesRow(prefs)
}

/** Shown only with two active SIMs, like every SIM control. */
@Composable
private fun SimNamesRow(prefs: ComposerPrefs) {
    val context = LocalContext.current
    val sims = remember { SimCards.load(context) }
    if (sims.size < 2) return
    SettingsRow(
        title = stringResource(R.string.i34_setting_title),
        summary = stringResource(R.string.i34_setting_summary),
        icon = Symbols.SimCard,
        onClick = {
            (context as? Activity)?.showComposeDialog { dismiss ->
                val names = SimNames(prefs)
                SimNamesDialog(
                    sims = sims,
                    names = names,
                    onSave = { sim, name -> names.set(sim.slot, sim.carrier, name.nickname, name.number) },
                    onDismiss = dismiss,
                )
            }
        },
        showChevron = true,
    )
}

private fun Activity.showSignatureDialog(current: String, onSaved: (String) -> Unit) {
    showComposeDialog { dismiss ->
        TextInputDialog(
            title = getString(R.string.i60_setting_title),
            initial = current,
            supportingText = getString(R.string.i60_dialog_note),
            onConfirm = {
                onSaved(MessageSignature.clean(it))
                dismiss()
            },
            onDismiss = dismiss,
        )
    }
}
