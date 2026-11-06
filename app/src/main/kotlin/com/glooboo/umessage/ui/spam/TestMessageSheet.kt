package com.glooboo.umessage.ui.spam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.DecisionRuleText
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.UMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The answer shown under the Test button: the verdict and, for a rule block, which rule. */
private data class TestOutcome(val verdict: Verdict, val ruleLabel: String?)

private const val MAX_TEST_TEXT = 1000
private const val MAX_SENDER = 64

/**
 * ID-52, "Test a message": paste a text, a sender and a SIM and see what the real filter would do. It asks the
 * installed [com.glooboo.umessage.spam.SpamFilter] directly (`UMessage.filter(context).evaluate`), which is read-only:
 * only `SpamEngine.evaluate` writes to the decision log, and that is not used here. Nothing typed is stored or sent.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TestMessageSheet(
    sims: List<SimInfo>,
    rules: List<SpamRule>,
    onCreateRule: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sender by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var simId by rememberSaveable { mutableStateOf(sims.firstOrNull()?.subscriptionId ?: -1) }
    var outcome by remember { mutableStateOf<TestOutcome?>(null) }
    var failed by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.w3b_test_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.w3b_test_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = sender,
                onValueChange = { sender = it.take(MAX_SENDER); outcome = null; failed = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.w3b_test_sender)) },
                placeholder = { Text(stringResource(R.string.w3b_test_sender_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                    autoCorrectEnabled = false,
                ),
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_TEST_TEXT); outcome = null; failed = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.w3b_test_text)) },
                minLines = 3,
                maxLines = 6,
            )
            if (sims.size >= 2) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.w3b_test_sim), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sims.forEach { sim ->
                            RuleChoiceChip(
                                selected = simId == sim.subscriptionId,
                                onClick = { simId = sim.subscriptionId; outcome = null; failed = false },
                                label = stringResource(R.string.spam_sim_label, sim.slot),
                            )
                        }
                    }
                }
            }
            Button(
                onClick = {
                    running = true
                    failed = false
                    val message = IncomingMessage(address = sender.trim(), body = text, subscriptionId = simId)
                    scope.launch {
                        val decision = withContext(Dispatchers.IO) {
                            runCatching { UMessage.filter(context).evaluate(message) }.getOrNull()
                        }
                        running = false
                        if (decision == null) {
                            outcome = null
                            failed = true
                        } else {
                            val ruleId = (decision as? FilterDecision.Block)?.ruleId
                            outcome = TestOutcome(
                                Verdict.of(decision, message.body),
                                ruleId?.let { id -> rules.firstOrNull { it.id == id } }
                                    ?.let { DecisionRuleText.label(it) },
                            )
                        }
                    }
                },
                enabled = !running && (sender.isNotBlank() || text.isNotBlank()),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.w3b_test_run)) }

            if (failed) {
                Text(
                    stringResource(R.string.w3b_test_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            outcome?.let { result ->
                val colors = MaterialTheme.colorScheme
                Card(
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    colors = CardDefaults.cardColors(
                        containerColor =
                            if (result.verdict.held) colors.tertiaryContainer else colors.secondaryContainer,
                        contentColor =
                            if (result.verdict.held) colors.onTertiaryContainer else colors.onSecondaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(verdictText(result.verdict)), style = MaterialTheme.typography.bodyLarge)
                        result.ruleLabel?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.w3b_test_close))
                    }
                    OutlinedButton(onClick = onCreateRule, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.w3b_test_create_rule))
                    }
                }
            }
        }
    }
}

internal fun verdictText(verdict: Verdict): Int = when (verdict) {
    Verdict.HELD_BY_RULE -> R.string.w3b_verdict_held_rule
    Verdict.HELD_UNKNOWN_WITH_LINK -> R.string.w3b_verdict_held_link
    Verdict.HELD_UNKNOWN -> R.string.w3b_verdict_held_unknown
    Verdict.ALLOWED_BY_RULE -> R.string.w3b_verdict_allowed_rule
    Verdict.ALLOWED_CONTACT -> R.string.w3b_verdict_allowed_contact
    Verdict.ALLOWED_KNOWN_CONVERSATION -> R.string.w3b_verdict_allowed_known
    Verdict.ALLOWED_CODE -> R.string.w3b_verdict_allowed_code
    Verdict.ALLOWED_MODE_OFF -> R.string.w3b_verdict_allowed_off
    Verdict.ALLOWED_UNSURE -> R.string.w3b_verdict_allowed_unsure
    Verdict.ALLOWED_OTHER -> R.string.w3b_verdict_allowed_other
}
