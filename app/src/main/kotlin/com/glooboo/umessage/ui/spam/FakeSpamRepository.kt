package com.glooboo.umessage.ui.spam

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ForwardLogEntry
import com.glooboo.umessage.spam.ForwardLogRepository
import com.glooboo.umessage.spam.ForwardStatus
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RestoreOutcome
import com.glooboo.umessage.spam.RestoreReceipt
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.SpamStats

/** In-memory repository for @Preview and as the debug fallback until the real engine is installed. */
@Suppress("TooManyFunctions") // One override per SpamRepository call; splitting a fake would only hide that.
class FakeSpamRepository(
    initialBlocked: List<BlockedMessage> = SampleData.blocked(),
    initialRules: List<SpamRule> = SampleData.rules(),
    initialSettings: SpamSettings = SpamSettings(),
) : SpamRepository {
    private val blockedState = MutableStateFlow(initialBlocked)
    private val rulesState = MutableStateFlow(initialRules)
    private val settingsState = MutableStateFlow(initialSettings)
    private val restored = MutableStateFlow(3)
    private var nextId = 1000L

    override val blocked: Flow<List<BlockedMessage>> = blockedState
    override val rules: Flow<List<SpamRule>> = rulesState
    override val settings: Flow<SpamSettings> = settingsState
    override val stats: Flow<SpamStats> = blockedState.map { list ->
        val weekAgo = System.currentTimeMillis() - 7L * DAY
        SpamStats(
            blockedTotal = list.size + 40,
            blockedLast7Days = list.count { it.receivedAt >= weekAgo },
            restoredTotal = restored.value,
        )
    }

    override suspend fun addRule(rule: SpamRule): Long {
        val id = nextId++
        rulesState.update { it + rule.copy(id = id) }
        return id
    }

    override suspend fun updateRule(rule: SpamRule): Long {
        val current = rulesState.value.firstOrNull { it.id == rule.id } ?: throw NoSuchElementException()
        rulesState.update { list -> list.map { if (it.id == rule.id) rule.copy(createdAt = current.createdAt) else it } }
        return rule.id
    }

    override suspend fun removeRule(id: Long) = rulesState.update { list -> list.filterNot { it.id == id } }

    override suspend fun updateSettings(transform: (SpamSettings) -> SpamSettings) =
        settingsState.update(transform)

    override suspend fun storeBlocked(message: BlockedMessage): Long {
        val id = nextId++
        blockedState.update { it + message.copy(id = id) }
        return id
    }

    override suspend fun markNotSpam(blockedId: Long): RestoreOutcome = restore(blockedId).outcome

    override suspend fun restore(blockedId: Long): RestoreReceipt {
        val message = blockedState.value.firstOrNull { it.id == blockedId }
            ?: return RestoreReceipt(null, RestoreOutcome.NOT_FOUND)
        restored.update { it + 1 }
        blockedState.update { list -> list.filterNot { it.id == blockedId } }
        return RestoreReceipt(message, message.expectedRestore)
    }

    override suspend fun undoRestore(receipt: RestoreReceipt): Boolean {
        val message = receipt.message ?: return false
        restored.update { it - 1 }
        blockedState.update { list -> if (list.any { it.id == message.id }) list else list + message }
        return true
    }

    override suspend fun blockSender(blockedId: Long) {
        val message = blockedState.value.firstOrNull { it.id == blockedId } ?: return
        addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = message.address))
    }

    override suspend fun deleteBlocked(ids: List<Long>) =
        blockedState.update { list -> list.filterNot { it.id in ids } }

    override suspend fun purgeExpired() = Unit

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}

/** Deterministic sample content shared by previews and the fake. */
object SampleData {
    private const val HOUR = 60L * 60 * 1000
    private const val DAY = 24 * HOUR

    fun blocked(now: Long = System.currentTimeMillis()): List<BlockedMessage> = listOf(
        BlockedMessage(1, "+14155550133", "You have won a free gift card! Claim now at example.test/claim", 1, false, now - 2 * HOUR, DecisionReason.UNKNOWN_SENDER),
        BlockedMessage(2, "+14155550133", "Last chance: your reward expires tonight.", 1, false, now - 5 * HOUR, DecisionReason.UNKNOWN_SENDER),
        BlockedMessage(3, "+14155550133", "Congratulations, you are selected.", 1, false, now - 1 * DAY, DecisionReason.UNKNOWN_SENDER),
        BlockedMessage(4, "VM-HDFCBK", "123456 is your one time password. Do not share it with anyone.", 2, false, now - 3 * HOUR, DecisionReason.UNKNOWN_SENDER),
        BlockedMessage(5, "AX-PROMOS", "Flat 70% off today only. Shop now.", 2, false, now - 2 * DAY, DecisionReason.BLOCK_RULE, 7),
        BlockedMessage(6, "+919876543210", "Your parcel is held, pay a fee at example.test/pay", 1, false, now - 9 * DAY, DecisionReason.BLOCK_RULE, 8),
    )

    fun rules(): List<SpamRule> = listOf(
        SpamRule(1, ListKind.ALLOW, RuleType.SENDER_ID, "VM-HDFCBK", null, "My bank"),
        SpamRule(2, ListKind.ALLOW, RuleType.NUMBER, "+14155550100", 1, ""),
        SpamRule(3, ListKind.ALLOW, RuleType.KEYWORD, "delivery code", null, ""),
        SpamRule(7, ListKind.BLOCK, RuleType.SENDER_ID, "AX-PROMOS", null, "Promotions"),
        SpamRule(8, ListKind.BLOCK, RuleType.PREFIX, "+91140", null, "Telemarketers"),
        SpamRule(9, ListKind.BLOCK, RuleType.KEYWORD, "free gift", 2, ""),
        SpamRule(10, ListKind.BLOCK, RuleType.REGEX, "win\\s+\\d+", null, "Lottery text"),
    )
}

/** In-memory forward log for @Preview and tests of the Forwarded tab. */
class FakeForwardLogRepository(initial: List<ForwardLogEntry> = SampleForwardLog.entries()) : ForwardLogRepository {
    private val state = MutableStateFlow(initial)

    override val entries: Flow<List<ForwardLogEntry>> = state

    override fun failedSince(since: Long): Flow<Int> =
        state.map { list -> list.count { it.status == ForwardStatus.FAILED && it.updatedAt > since } }
}

/** Deterministic forward log content for previews. */
object SampleForwardLog {
    private const val HOUR = 60L * 60 * 1000
    private const val BANK = "BankX"
    private const val FRIEND = "+14155550100"
    private const val TARGET_A = "+14155550199"
    private const val TARGET_B = "+14155550177"
    private const val SIM_2 = 2

    fun entries(now: Long = System.currentTimeMillis()): List<ForwardLogEntry> = listOf(
        entry(id = 3, at = now - 1 * HOUR, sender = BANK, target = TARGET_A, sim = SIM_2, failed = true),
        entry(id = 2, at = now - 5 * HOUR, sender = BANK, target = TARGET_B, sim = SIM_2),
        entry(id = 1, at = now - 30 * HOUR, sender = FRIEND, target = TARGET_B, sim = null),
    )

    private fun entry(id: Long, at: Long, sender: String, target: String, sim: Int?, failed: Boolean = false) =
        ForwardLogEntry(
            id, at, at, 1, sender, at, false, target, sim,
            if (failed) ForwardStatus.FAILED else ForwardStatus.SENT,
            if (failed) "RADIO_FAILED" else null,
            1,
        )
}
