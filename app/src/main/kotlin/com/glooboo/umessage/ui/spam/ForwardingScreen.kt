package com.glooboo.umessage.ui.spam

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.navPrefs
import com.glooboo.umessage.ui.components.UMessageFab
import com.glooboo.umessage.ui.components.UMessageScaffold
import com.glooboo.umessage.ui.components.UMessageTopBar
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.lock.LockActivity

/**
 * The forwarding screen's state holder: the forward rules view model, the app-lock check that changing a rule needs,
 * and whether "Pause all forwarding" is on (a rule list that silently forwards nothing would mislead).
 */
@Composable
fun ForwardingRoute(
    onBack: () -> Unit,
    onOpenAppLockSettings: () -> Unit,
    onOpenOptions: () -> Unit,
    viewModel: ForwardRulesViewModel = viewModel(factory = ForwardRulesViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val paused by remember(context) { context.navPrefs.forwardPausedFlow }
        .collectAsStateWithLifecycle(initialValue = context.navPrefs.forwardPaused)

    // Changing forward rules with the app lock on: the lock screen confirms it is the owner first.
    val verifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onVerified(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(state.prompt, state.verifying) {
        if (state.prompt == ForwardGatePrompt.VERIFY && !state.verifying) {
            viewModel.onVerifyStarted()
            verifyLauncher.launch(Intent(context, LockActivity::class.java).putExtra(LockActivity.EXTRA_CONFIRM, true))
        }
    }
    val actions = rememberForwardActions(viewModel, onOpenAppLockSettings)
    val snackbar = remember { SnackbarHostState() }
    ForwardingScreen(state, actions, snackbar, paused = paused, onBack = onBack, onOpenOptions = onOpenOptions)
}

/** The callbacks of the list and the editor; the no-lock note can open the App lock settings. */
@Composable
private fun rememberForwardActions(viewModel: ForwardRulesViewModel, onOpenAppLockSettings: () -> Unit) =
    remember(viewModel, onOpenAppLockSettings) {
        ForwardActions(
            onAdd = viewModel::openAdd,
            onEdit = viewModel::openEdit,
            onToggle = viewModel::setEnabled,
            onDeleteFromForm = viewModel::deleteFromForm,
            onUndoDelete = viewModel::undoDelete,
            onDeletedShown = viewModel::deletedShown,
            onAskTest = viewModel::askTest,
            onConfirmTest = viewModel::confirmTest,
            onCancelTest = viewModel::cancelTest,
            onDismissForm = viewModel::dismissForm,
            onSenderType = viewModel::setSenderType,
            onSenderPattern = viewModel::setSenderPattern,
            onBodyType = viewModel::setBodyType,
            onBodyPattern = viewModel::setBodyPattern,
            onUseWindow = viewModel::setUseWindow,
            onWindowStart = viewModel::setWindowStart,
            onWindowEnd = viewModel::setWindowEnd,
            onScope = viewModel::setScope,
            onSendFrom = viewModel::setSendFrom,
            onNote = viewModel::setNote,
            onTarget = viewModel::setTarget,
            onAddTarget = viewModel::addTarget,
            onRemoveTarget = viewModel::removeTarget,
            onIncludeCodes = viewModel::setIncludeCodes,
            onConfirmCodes = viewModel::confirmIncludeCodes,
            onCancelCodes = viewModel::cancelIncludeCodes,
            onSubmit = viewModel::submit,
            onNoLockContinue = { viewModel.onNoLockNote(proceed = true) },
            onNoLockSetUp = {
                viewModel.onNoLockNote(proceed = false)
                onOpenAppLockSettings()
            },
            onDismissChangeFailed = viewModel::dismissChangeFailed,
            onConfirmBroad = viewModel::confirmBroad,
            onCancelBroad = viewModel::cancelBroad,
            onAddForText = viewModel::openAddForText,
            onForwardedTabAnswer = viewModel::answerForwardedTab,
        )
    }

/**
 * "Forwarding": the forward rules (or the empty state with its two starts), an "Add forward" button, and the options
 * button (once a rule exists) that opens the forwarding switches in Settings. While forwarding is paused a card says
 * so above the rules.
 */
@Composable
fun ForwardingScreen(
    state: ForwardUiState,
    actions: ForwardActions,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    onBack: () -> Unit = {},
    onOpenOptions: () -> Unit = {},
) {
    UMessageScaffold(
        modifier = modifier,
        topBar = {
            UMessageTopBar(title = stringResource(R.string.r3fwd_title), onBack = onBack) {
                // The options matter once there is a rule to forward (or forwarding is paused), not on the empty start.
                if (state.rules.isNotEmpty() || paused) {
                    IconButton(onClick = onOpenOptions, modifier = Modifier.testTag("forwarding_options")) {
                        Icon(Symbols.Tune, contentDescription = stringResource(R.string.r3fwd_options))
                    }
                }
            }
        },
        snackbarHostState = snackbarHostState,
        floatingActionButton = {
            UMessageFab(text = stringResource(R.string.forward_add), icon = Symbols.Add, onClick = actions.onAdd)
        },
    ) { inner ->
        Column(Modifier.padding(top = inner.calculateTopPadding()).fillMaxSize()) {
            if (paused) PausedCard(onOpenOptions)
            ForwardRulesContent(state, actions, inner.calculateBottomPadding())
        }
    }
    ForwardDialogs(state, actions, snackbarHostState)
}

/** "Pause all forwarding" is on: the rules below are kept but do nothing until it is turned off in the options. */
@Composable
private fun PausedCard(onOpenOptions: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .testTag("forwarding_paused"),
        colors = CardDefaults.cardColors(
            containerColor = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Symbols.Pause, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.r3fwd_paused_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.r3fwd_paused_body), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onOpenOptions, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.r3fwd_paused_action))
                }
            }
        }
    }
}
