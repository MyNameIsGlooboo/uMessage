package com.glooboo.umessage.ui.thread

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SmsManager
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.subscriptionManagerCompat
import com.glooboo.umessage.helpers.DEFAULT_SIM_ASK
import com.glooboo.umessage.helpers.SimNames
import com.glooboo.umessage.helpers.composerPrefs
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.SIMCard

/**
 * Which SIM the thread sends from, on phones with more than one. The choice follows [chooseSimIndex]; picking a
 * SIM remembers it for every number in the conversation. The chip in the composer reads [ThreadViewModel.sims].
 */
class ThreadSimController(private val context: Context, private val vm: ThreadViewModel) {
    private val sims = ArrayList<SIMCard>()
    private var index = 0

    val choices: List<SIMCard> get() = sims
    val selectedIndex: Int get() = index

    /** The subscription to send with: the chosen SIM, else the phone's default for SMS. */
    val subscriptionId: Int
        get() = sims.getOrNull(index)?.subscriptionId ?: SmsManager.getDefaultSmsSubscriptionId()

    /** Send asks for the SIM each time when the app is set to ask and there is a choice. */
    fun mustAsk(): Boolean = context.config.defaultSimId == DEFAULT_SIM_ASK && sims.size > 1

    /** Reads the active SIMs and picks one for [recipients]; [lastMessage] is the thread's newest message. */
    @SuppressLint("MissingPermission")
    fun load(recipients: ThreadRecipients, lastMessage: Message?): Boolean {
        val active = context.subscriptionManagerCompat().activeSubscriptionInfoList ?: return false
        if (active.size <= 1) return false
        sims.clear()
        val names = SimNames(context.composerPrefs)
        active.forEachIndexed { i, info ->
            // I34: the nickname and number the user typed win over the carrier's name.
            val label = names.label(
                slot = info.simSlotIndex + 1,
                carrier = info.carrierName?.toString().orEmpty(),
                carrierLabel = info.displayName?.toString().orEmpty(),
                carrierNumber = info.number,
            )
            sims.add(SIMCard(i + 1, info.subscriptionId, label))
        }
        val numbers = recipients.addresses
        if (numbers.isEmpty()) return false

        index = chooseSimIndex(
            subscriptionIds = active.map { it.subscriptionId },
            userPreferredSimId = context.config.getUseSIMIdAtNumber(numbers.first()),
            lastReceivedSubscriptionId = lastMessage?.takeIf { it.isReceivedMessage() }?.subscriptionId,
            appDefaultId = context.config.defaultSimId,
            systemDefaultId = SmsManager.getDefaultSmsSubscriptionId(),
        )
        vm.sims = sims.toList()
        vm.simIndex = index.coerceIn(0, sims.lastIndex)
        return true
    }

    /** The user picked SIM [newIndex]: used from now on for every number in [recipients]. */
    fun select(newIndex: Int, recipients: ThreadRecipients): Boolean {
        val sim = sims.getOrNull(newIndex) ?: return false
        index = newIndex
        vm.simIndex = newIndex
        recipients.addresses.forEach { context.config.saveUseSIMIdAtNumber(it, sim.subscriptionId) }
        return true
    }
}
