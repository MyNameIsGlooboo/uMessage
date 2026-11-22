package com.glooboo.umessage.ui.spam

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.AllowGroupMember
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.DecisionRuleText
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.ui.components.ruleValueText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The answer shown under the Test button: what it says and, for a rule block, which rule. */
private data class TestOutcome(val verdict: TestVerdict, val ruleLabel: String?)

/** The rule under the Test sheet's answer; F-2: a prefix or number rule reads as written in right-to-left text. */
internal fun testRuleText(context: Context, ruleId: Long?, rules: List<SpamRule>): String? =
    ruleId?.let { id -> rules.firstOrNull { it.id == id } }
        ?.let { ruleValueText(context, it.type, DecisionRuleText.label(it)) }

/**
 * One answer of the test sheet; [group] names the group rule of [Kind.GROUP]. The answers are about the sender
 * (provider truth): a word or a code allows or blocks the sender from then on.
 */
internal data class TestVerdict(val kind: Kind, val group: AllowGroup? = null) {
    enum class Kind {
        HELD_BY_RULE, HELD_UNKNOWN_WITH_LINK, HELD_UNKNOWN, BLOCKED_WORD,
        ALLOWED_BY_RULE, ALLOWED_CONTACT, ALLOWED_CODE, ALLOWED_MODE_OFF,
        ALLOWED_UNSURE, ALLOWED_OTHER, ALLOWED_WORD, GROUP,
    }

    val held: Boolean get() = kind in HELD

    companion object {
        private val HELD = setOf(Kind.HELD_BY_RULE, Kind.HELD_UNKNOWN_WITH_LINK, Kind.HELD_UNKNOWN, Kind.BLOCKED_WORD)
        private val TEXT_TYPES = setOf(RuleType.KEYWORD, RuleType.REGEX, RuleType.DOMAIN)

        /**
         * Provider truth: the answer for the filter's probe (it writes nothing). A rule that decided and is a word,
         * pattern or website rule allows or blocks the sender from then on; a group member is named with its group,
         * looked up in [members] among the groups switched on in [settings].
         */
        fun of(decision: FilterDecision, message: IncomingMessage, context: TestContext): TestVerdict =
            when (decision) {
                is FilterDecision.Block -> TestVerdict(
                    when {
                        decision.reason != DecisionReason.BLOCK_RULE && BlockedHints.hasLink(message.body) ->
                            Kind.HELD_UNKNOWN_WITH_LINK
                        decision.reason != DecisionReason.BLOCK_RULE -> Kind.HELD_UNKNOWN
                        byWord(decision.ruleId, context) -> Kind.BLOCKED_WORD
                        else -> Kind.HELD_BY_RULE
                    },
                )
                is FilterDecision.Allow -> ofAllow(decision, message, context)
            }

        private fun ofAllow(decision: FilterDecision.Allow, message: IncomingMessage, context: TestContext) =
            when (decision.reason) {
                DecisionReason.ALLOW_RULE ->
                    TestVerdict(if (byWord(decision.ruleId, context)) Kind.ALLOWED_WORD else Kind.ALLOWED_BY_RULE)
                DecisionReason.GROUP_MEMBER ->
                    memberGroupOf(context.members, context.settings, message.address)
                        ?.let { TestVerdict(Kind.GROUP, it) } ?: TestVerdict(Kind.ALLOWED_OTHER)
                DecisionReason.OTP_EXEMPTION -> TestVerdict(Kind.ALLOWED_CODE)
                DecisionReason.CONTACT -> TestVerdict(Kind.ALLOWED_CONTACT)
                DecisionReason.MODE_OFF -> TestVerdict(Kind.ALLOWED_MODE_OFF)
                DecisionReason.CONTACTS_UNAVAILABLE -> TestVerdict(Kind.ALLOWED_UNSURE)
                else -> TestVerdict(Kind.ALLOWED_OTHER)
            }

        /** The rule [ruleId] is a word, pattern or website rule: the filter made the fact from the text. */
        private fun byWord(ruleId: Long?, context: TestContext): Boolean {
            val rule = ruleId?.let { id -> context.rules.firstOrNull { it.id == id } } ?: return false
            return rule.type in TEXT_TYPES
        }
    }
}

/** What the provider-truth answer reads besides the decision: the rules, the group members and the switches. */
internal data class TestContext(
    val rules: List<SpamRule>,
    val members: List<AllowGroupMember> = emptyList(),
    val settings: SpamSettings = SpamSettings(),
)

/**
 * The group, switched on, that has [address] as a member, the code group first. A member is the typed sender when the
 * two are the same name (case aside) or the same number (the last [SAME_NUMBER_DIGITS] digits, so "+1 555 000 1234"
 * and "5550001234" match). Only for the test sheet's wording; the filter matches members by every number reading.
 */
internal fun memberGroupOf(members: List<AllowGroupMember>, settings: SpamSettings, address: String): AllowGroup? =
    AllowGroup.entries.filter(settings::groupOn).firstOrNull { group ->
        members.any { it.group == group && (sameSender(it.address, address) || sameSender(it.senderKey, address)) }
    }

private const val SAME_NUMBER_DIGITS = 10
private const val MIN_NUMBER_DIGITS = 7

private fun sameSender(a: String, b: String): Boolean {
    if (a.isBlank() || b.isBlank()) return false
    if (a.trim().equals(b.trim(), ignoreCase = true)) return true
    val da = a.filter(Char::isDigit)
    val db = b.filter(Char::isDigit)
    val numbers = da.length >= MIN_NUMBER_DIGITS && db.length >= MIN_NUMBER_DIGITS &&
        a.none(Char::isLetter) && b.none(Char::isLetter)
    return numbers && da.takeLast(SAME_NUMBER_DIGITS) == db.takeLast(SAME_NUMBER_DIGITS)
}

private const val MAX_TEST_TEXT = 1000
private const val MAX_SENDER = 64

/**
 * ID-52, "Test a message": paste a text and a sender and see what the real filter would do. It asks the installed
 * [com.glooboo.umessage.spam.SpamFilter] directly (`UMessage.filter(context).evaluate`, a probe), which is
 * read-only: a probe writes no decision-log row and no fact. Nothing typed is stored or sent. Spam rules cover
 * both SIMs (7.2), so there is no SIM field; the first SIM only says how a national number is read.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val simId = sims.firstOrNull()?.subscriptionId ?: -1
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
            Button(
                onClick = {
                    running = true
                    failed = false
                    val message = IncomingMessage(address = sender.trim(), body = text, subscriptionId = simId)
                    scope.launch {
                        val decision = withContext(Dispatchers.IO) {
                            runCatching { UMessage.filter(context).evaluate(message) }.getOrNull()
                        }
                        val verdict = decision?.let { testVerdict(context, it, message, rules) }
                        running = false
                        if (decision == null || verdict == null) {
                            outcome = null
                            failed = true
                        } else {
                            val ruleId = (decision as? FilterDecision.Block)?.ruleId
                            outcome = TestOutcome(verdict, testRuleText(context, ruleId, rules))
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
                        Text(
                            verdictSentence(LocalContext.current.resources, result.verdict),
                            style = MaterialTheme.typography.bodyLarge,
                        )
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

/** The answer for [decision]; it reads the group members and switches too. */
private suspend fun testVerdict(
    context: android.content.Context,
    decision: FilterDecision,
    message: IncomingMessage,
    rules: List<SpamRule>,
): TestVerdict? {
    val repository = runCatching { UMessage.repository(context) }.getOrNull() ?: return null
    val members = runCatching { repository.groupMembers.first() }.getOrDefault(emptyList())
    val settings = runCatching { repository.settings.first() }.getOrDefault(SpamSettings())
    return TestVerdict.of(decision, message, TestContext(rules, members, settings))
}

/** The sentence of an answer. [TestVerdict.Kind.GROUP] takes the group's name: use [verdictSentence]. */
internal fun verdictText(verdict: TestVerdict): Int = when (verdict.kind) {
    TestVerdict.Kind.HELD_BY_RULE -> R.string.w3b_verdict_held_rule
    TestVerdict.Kind.HELD_UNKNOWN_WITH_LINK -> R.string.w3b_verdict_held_link
    TestVerdict.Kind.HELD_UNKNOWN -> R.string.w3b_verdict_held_unknown
    TestVerdict.Kind.BLOCKED_WORD -> R.string.pt_verdict_blocked_word
    TestVerdict.Kind.ALLOWED_BY_RULE -> R.string.w3b_verdict_allowed_rule
    TestVerdict.Kind.ALLOWED_CONTACT -> R.string.w3b_verdict_allowed_contact
    TestVerdict.Kind.ALLOWED_CODE -> R.string.w3b_verdict_allowed_code
    TestVerdict.Kind.ALLOWED_MODE_OFF -> R.string.w3b_verdict_allowed_off
    TestVerdict.Kind.ALLOWED_UNSURE -> R.string.w3b_verdict_allowed_unsure
    TestVerdict.Kind.ALLOWED_OTHER -> R.string.w3b_verdict_allowed_other
    TestVerdict.Kind.ALLOWED_WORD -> R.string.pt_verdict_allowed_word
    TestVerdict.Kind.GROUP -> R.string.pt_verdict_group
}

/** The answer as one sentence, the group's name filled in. */
internal fun verdictSentence(resources: android.content.res.Resources, verdict: TestVerdict): String {
    val group = verdict.group
    return if (verdict.kind == TestVerdict.Kind.GROUP && group != null) {
        resources.getString(R.string.pt_verdict_group, resources.getString(groupTitle(group)))
    } else {
        resources.getString(verdictText(verdict))
    }
}
