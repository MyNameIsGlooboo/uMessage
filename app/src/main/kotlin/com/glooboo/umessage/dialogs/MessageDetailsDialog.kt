package com.glooboo.umessage.dialogs

import android.annotation.SuppressLint
import android.telephony.SubscriptionInfo
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.subscriptionManagerCompat
import com.glooboo.umessage.helpers.DateStyle
import com.glooboo.umessage.helpers.w3cPrefs
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.ui.components.InfoDialog
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.components.listSeparator
import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.components.showComposeDialog
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.log.ForwardLogText
import com.glooboo.umessage.ui.spam.SpamRepositoryProvider
import com.glooboo.umessage.ui.thread.MessageDecisions
import com.glooboo.umessage.ui.thread.messageDetailsTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.helpers.ensureBackgroundThread
import java.time.ZoneId
import java.util.Locale

/** One row of the dialog; [copyValue] makes it copy on a tap. */
private data class Property(val label: Int, val value: String, val copyValue: String? = null)

/**
 * "Sara (555-0100)", or the number alone when there is no name. CL-10: the number in the shared display form, in the
 * app's digits; each number is isolated so it reads in order in right-to-left text. The copied value stays raw.
 */
internal fun formatContactInfo(name: String, phoneNumber: String, display: (String) -> String = { it }): String {
    val number = isolateNumbers(display(phoneNumber))
    return if (name != phoneNumber) "${isolateNumbers(display(name))} ($number)" else number
}

/** TH-16: a label over its value; a copyable row has a copy icon and copies the whole value on a tap. */
@Composable
private fun PropertyRow(property: Property, onCopy: (String) -> Unit) {
    val copy = property.copyValue
    val label = stringResource(property.label)
    val copyLabel = stringResource(R.string.thread_r2_details_copy, label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (copy != null) {
                    Modifier.clickable(onClickLabel = copyLabel, role = Role.Button) {
                        onCopy(copy)
                    }
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(property.value, style = MaterialTheme.typography.bodyLarge)
        }
        if (copy != null) {
            Icon(
                Symbols.ContentCopy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(20.dp),
            )
        }
    }
}

/**
 * Sender or receiver, SIM, time, type and, for a received message, the filter's decision with its reason, the rule
 * behind it and any forwards (TH-13). The sender and time rows copy their value on a tap (TH-16). The call signature
 * is unchanged; the decision log is read in the background before the dialog opens.
 */
class MessageDetailsDialog(val activity: BaseSimpleActivity, val message: Message) {
    init {
        ensureBackgroundThread {
            val properties = runCatching { buildProperties() }.getOrElse { listOf(senderProperty()) }
            activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) show(properties) }
        }
    }

    private fun buildProperties(): List<Property> {
        @SuppressLint("MissingPermission")
        val availableSIMs = activity.subscriptionManagerCompat().activeSubscriptionInfoList.orEmpty()
        return buildList {
            add(senderProperty())
            if (availableSIMs.count() > 1) {
                add(Property(R.string.message_details_sim, message.getSIM(availableSIMs)))
            }
            val time = sentOrReceivedAt()
            add(Property(message.getSentOrReceivedAtLabel(), time, copyValue = time))
            val type = if (message.isMMS) {
                R.string.thread_review_details_type_mms
            } else {
                R.string.thread_review_details_type_sms
            }
            add(Property(R.string.thread_review_details_type, activity.getString(type)))
            // Only allowed (or restored) messages live in the Telephony provider; blocked ones are kept apart.
            if (message.isReceivedMessage()) addAll(decisionProperties())
        }
    }

    /** Decision, reason, rule and forwards from the decision log, when it still has this message (one week). */
    private fun decisionProperties(): List<Property> {
        val sender = message.senderPhoneNumber
        val entries = runCatching { UMessage.log(activity).entries() }.getOrDefault(emptyList())
        val found = MessageDecisions.find(entries, sender, message.millis())
        val decision = found.decision
        val rules: List<SpamRule> by lazy {
            runCatching { runBlocking { SpamRepositoryProvider.get(activity).rules.first() } }.getOrDefault(emptyList())
        }
        return buildList {
            val decisionText = if (decision?.decision == "BLOCK") {
                R.string.thread_r2_details_decision_restored
            } else {
                R.string.thread_review_details_decision_allowed
            }
            add(Property(R.string.thread_review_details_decision, activity.getString(decisionText)))
            if (decision != null) {
                MessageDecisions.reasonLabel(decision.reason)?.let {
                    add(Property(R.string.thread_r2_details_reason, activity.getString(it)))
                }
                MessageDecisions.ruleLabel(decision, sender, rules)?.let {
                    add(Property(R.string.thread_r2_details_rule, it))
                }
            }
            found.forwards.forEach {
                val text = ForwardLogText.sentence(activity.resources, it.reason)
                add(Property(R.string.thread_r2_details_forwarded, text))
            }
        }
    }

    private fun show(properties: List<Property>) {
        activity.showComposeDialog { dismiss ->
            InfoDialog(onDismiss = dismiss, title = stringResource(R.string.message_details)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    properties.forEach { property -> PropertyRow(property) { activity.copyToClipboard(it) } }
                }
            }
        }
    }

    private fun senderProperty(): Property {
        val numbers = if (message.isReceivedMessage()) {
            listOf(message.senderPhoneNumber)
        } else {
            message.participants.mapNotNull { it.phoneNumbers.firstOrNull()?.value }
        }
        return Property(
            message.getSenderOrReceiverLabel(),
            message.getSenderOrReceiverPhoneNumbers(),
            copyValue = numbers.joinToString(", "),
        )
    }

    private fun Message.getSenderOrReceiverLabel(): Int {
        return if (isReceivedMessage()) {
            R.string.message_details_sender
        } else {
            R.string.message_details_receiver
        }
    }

    private fun Message.getSenderOrReceiverPhoneNumbers(): String {
        return if (isReceivedMessage()) {
            formatContactInfo(senderName, senderPhoneNumber) { displayNumber(activity, it) }
        } else {
            participants.joinToString(listSeparator(activity.uiLocale)) {
                formatContactInfo(it.name, it.phoneNumbers.first().value) { number -> displayNumber(activity, number) }
            }
        }
    }

    private fun Message.getSIM(availableSIMs: List<SubscriptionInfo>): String {
        val sim = availableSIMs.firstOrNull { it.subscriptionId == subscriptionId }
            ?: return activity.getString(org.fossify.commons.R.string.unknown)
        // TH-15: the slot number the thread shows, then the carrier name.
        return activity.getString(
            R.string.thread_review_sim_slot_name,
            sim.simSlotIndex + 1,
            sim.displayName.toString(),
        )
    }

    private fun Message.getSentOrReceivedAtLabel(): Int {
        return if (isReceivedMessage()) {
            R.string.message_details_received_at
        } else {
            R.string.message_details_sent_at
        }
    }

    /** B4, TH-14: a four-digit year, the app's date style (Gregorian, Jalali or both) and Persian digits in Persian. */
    private fun sentOrReceivedAt(): String {
        val style = activity.w3cPrefs.dateStyle
        return messageDetailsTime(
            epochMillis = message.millis(),
            zone = ZoneId.systemDefault(),
            use24Hour = DateFormat.is24HourFormat(activity),
            locale = activity.resources.configuration.locales[0] ?: Locale.getDefault(),
            jalali = style != DateStyle.GREGORIAN,
            both = style == DateStyle.BOTH,
        )
    }
}
