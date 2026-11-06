package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.ForwardLogEntry
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.ForwardStatus
import com.glooboo.umessage.spam.ForwardSim
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.TextNormalizer
import com.glooboo.umessage.spam.engine.NumberNormalizer

/** Why a forward target cannot be used. */
enum class TargetError {
    /** Not a phone number (letters, too short or too long). */
    INVALID,

    /** The same number is already a target of this rule. */
    DUPLICATE,

    /** One of this phone's own numbers: the forward would come straight back. */
    OWN_NUMBER,

    /** The number this rule watches: the forward would trigger itself. */
    SAME_AS_SENDER,
}

/** Pure checks for forward targets. No Android dependencies. */
object ForwardTargets {
    private val phoneChars = Regex("[\\s\\-().]")
    private val digitsOnly = Regex("\\+?\\d+")

    /** Fewer digits than this is a short code or a typo, not a phone to forward to. */
    const val MIN_DIGITS = 7
    const val MAX_DIGITS = 15

    /** The number as it is stored and sent (separators removed), or null when it is not a phone number. */
    fun clean(raw: String): String? {
        // Persian and Arabic digits are read as ASCII digits.
        val cleaned = TextNormalizer.fold(raw.trim()).replace(phoneChars, "")
        if (!digitsOnly.matches(cleaned)) return null
        return cleaned.takeIf { it.count(Char::isDigit) in MIN_DIGITS..MAX_DIGITS }
    }

    private val localMobile = Regex("09\\d{9}")
    private val localNormalizer = NumberNormalizer { "IR" }

    /**
     * The target as it is stored (BR-36): a local mobile number in national format is saved with its country code
     * (national 0 replaced by the code), so the SMS also goes out when the SIM is abroad. Anything else is stored
     * as [clean] gives it.
     */
    fun stored(raw: String): String? {
        val cleaned = clean(raw) ?: return null
        return if (localMobile.matches(cleaned)) localNormalizer.normalize(cleaned) else cleaned
    }

    /** Digits only, for spotting the same number typed twice. */
    fun key(number: String): String = number.filter(Char::isDigit).takeLast(10)

    /**
     * One entry per field: null when fine or left empty. [ownNumbers] are this phone's own numbers and [watchedSender]
     * is the number a rule reacts to; forwarding to either would loop, so they are refused.
     */
    fun errors(
        fields: List<String>,
        ownNumbers: List<String> = emptyList(),
        watchedSender: String? = null,
    ): List<TargetError?> {
        val seen = mutableSetOf<String>()
        val own = ownNumbers.map(::key).filter { it.length >= MIN_KEY_DIGITS }.toSet()
        val watched = watchedSender?.let(::key)?.takeIf { it.length >= MIN_KEY_DIGITS }
        return fields.map { raw ->
            if (raw.isBlank()) return@map null
            val cleaned = clean(raw) ?: return@map TargetError.INVALID
            val k = key(cleaned)
            when {
                !seen.add(k) -> TargetError.DUPLICATE
                k in own -> TargetError.OWN_NUMBER
                k == watched -> TargetError.SAME_AS_SENDER
                else -> null
            }
        }
    }

    /** Comparing the last digits only makes sense for a real number, not a few digits of a prefix. */
    private const val MIN_KEY_DIGITS = 7
}

/**
 * State of the add/edit-forward sheet. Times are minutes after midnight. [targets] has one entry per field shown
 * (1 to [ForwardRule.MAX_TARGETS]). [confirmingCodes] shows the warning before verification codes are included.
 */
data class ForwardForm(
    val editing: ForwardRule? = null,
    val senderType: RuleType? = RuleType.NUMBER,
    val senderPattern: String = "",
    val bodyType: RuleType? = null,
    val bodyPattern: String = "",
    val useWindow: Boolean = false,
    val windowStart: Int = 9 * 60,
    val windowEnd: Int = 17 * 60,
    val subscriptionId: Int? = null,
    val targets: List<String> = listOf(""),
    /** This phone's own numbers, when Android shows them; a target equal to one is refused. */
    val ownNumbers: List<String> = emptyList(),
    val sendFrom: ForwardSim = ForwardSim.SAME,
    val includeCodes: Boolean = false,
    val enabled: Boolean = true,
    val note: String = "",
    val confirmingCodes: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    /** The very-many-messages warning is open, and whether it was already accepted for this form. */
    val confirmingBroad: Boolean = false,
    val broadAccepted: Boolean = false,
    /** The "Send test" SMS (ID-07); back to IDLE when a number changes. */
    val test: ForwardTestState = ForwardTestState.IDLE,
) {
    val isEdit: Boolean get() = editing != null

    /** A test can go out once every number is a valid target and no test is on its way. */
    val canSendTest: Boolean
        get() = !noTargets && targetErrors.all { it == null } && cleanTargets.isNotEmpty() &&
            test != ForwardTestState.SENDING && test != ForwardTestState.CONFIRMING

    /** The failed check of the sender value, with the detail (e.g. which character is not allowed). */
    val senderInvalid: RuleValidation.Invalid?
        get() = senderType?.let { type ->
            RuleValidator.validate(ListKind.BLOCK, type, senderPattern) as? RuleValidation.Invalid
        }
    val bodyInvalid: RuleValidation.Invalid?
        get() = bodyType?.let { type ->
            RuleValidator.validate(ListKind.BLOCK, type, bodyPattern) as? RuleValidation.Invalid
        }
    val senderError: RuleError? get() = senderInvalid?.error
    val bodyError: RuleError? get() = bodyInvalid?.error

    /** Neither a sender nor a text condition: a rule must say which messages it forwards. */
    val noCondition: Boolean get() = senderType == null && bodyType == null

    val targetErrors: List<TargetError?>
        get() = ForwardTargets.errors(
            targets,
            ownNumbers,
            watchedSender = senderPattern.takeIf { senderType == RuleType.NUMBER && senderError == null },
        )
    val cleanTargets: List<String>
        get() = targets.mapNotNull { ForwardTargets.stored(it) }.distinctBy { ForwardTargets.key(it) }
    val noTargets: Boolean get() = targets.none { it.isNotBlank() }

    val canAddTarget: Boolean get() = targets.size < ForwardRule.MAX_TARGETS

    val isValid: Boolean
        get() = !noCondition && senderError == null && bodyError == null && !noTargets &&
            targetErrors.all { it == null }

    val canSubmit: Boolean get() = isValid && !saving

    /**
     * The rule would forward very many messages: no sender limit and a very common text, or a sender prefix of only a
     * few digits with no text. Saving it asks for a second confirmation. (A rule with no condition at all is refused.)
     */
    val isBroad: Boolean
        get() {
            if (!isValid) return false
            if (senderType == null && bodyType != null) {
                val pattern = cleaned(bodyType, bodyPattern)
                return RuleWarning.BROAD in RuleValidator.warnings(ListKind.BLOCK, bodyType, pattern, emptyList(), null)
            }
            return senderType == RuleType.PREFIX && bodyType == null &&
                cleaned(RuleType.PREFIX, senderPattern).count(Char::isDigit) <= MIN_SENDER_PREFIX_DIGITS
        }

    /** SMS sent per forwarded message, at the least: one per target number (long and Persian texts take more parts). */
    val minSmsPerForward: Int get() = cleanTargets.size.coerceAtLeast(1)

    /** The rule this form saves (only meaningful when [isValid]). */
    fun toRule(now: Long = System.currentTimeMillis()): ForwardRule = ForwardRule(
        id = editing?.id ?: 0,
        senderType = senderType,
        senderPattern = senderType?.let { cleaned(it, senderPattern) }.orEmpty(),
        bodyType = bodyType,
        bodyPattern = bodyType?.let { cleaned(it, bodyPattern) }.orEmpty(),
        windowStart = if (useWindow) windowStart else null,
        windowEnd = if (useWindow) windowEnd else null,
        subscriptionId = subscriptionId,
        targets = cleanTargets,
        sendFrom = sendFrom,
        includeCodes = includeCodes,
        enabled = editing?.enabled ?: enabled,
        note = note.trim(),
        createdAt = editing?.createdAt ?: now,
    )

    private fun cleaned(type: RuleType, pattern: String): String =
        (RuleValidator.validate(ListKind.BLOCK, type, pattern) as? RuleValidation.Valid)?.pattern ?: pattern.trim()

    companion object {
        /** A sender prefix this short (like "+9") matches a whole country's numbers. */
        private const val MIN_SENDER_PREFIX_DIGITS = 3

        fun of(rule: ForwardRule) = ForwardForm(
            editing = rule,
            senderType = rule.senderType,
            senderPattern = rule.senderPattern,
            bodyType = rule.bodyType,
            bodyPattern = rule.bodyPattern,
            useWindow = rule.hasWindow,
            windowStart = rule.windowStart ?: 9 * 60,
            windowEnd = rule.windowEnd ?: 17 * 60,
            subscriptionId = rule.subscriptionId,
            targets = rule.targets.ifEmpty { listOf("") },
            sendFrom = rule.sendFrom,
            includeCodes = rule.includeCodes,
            enabled = rule.enabled,
            note = rule.note,
        )
    }
}

/** A change to the forward rules that must pass the app lock first. */
sealed interface ForwardChange {
    data class Save(val rule: ForwardRule) : ForwardChange
    data class SetEnabled(val rule: ForwardRule, val enabled: Boolean) : ForwardChange
    data class Delete(val rule: ForwardRule) : ForwardChange
}

/** What the screen asks before a [ForwardChange] goes through. */
enum class ForwardGatePrompt {
    /** Nothing to ask: the change is applied. */
    NONE,

    /** The app lock is on: confirm with its PIN, pattern or biometrics. */
    VERIFY,

    /** No app lock: explain once that one protects forwarding, then go on. */
    NO_LOCK_NOTE,
}

/** Where the forward screen reads the app-lock state. */
interface ForwardLockGate {
    fun lockEnabled(): Boolean
    fun noLockNoteShown(): Boolean
    fun markNoLockNoteShown()
}

/**
 * The rule for asking: with the app lock on, creating, editing, enabling and deleting a forward rule ask for it,
 * except within [GRACE_MS] of the last confirmation (several changes in a row ask once). Turning a rule off never
 * asks: it can only stop messages from leaving the phone. Without an app lock the explanation is shown once.
 */
object ForwardGatePolicy {
    const val GRACE_MS = 60_000L

    /**
     * Bug 7: the user left the editor to set up the app lock ("Set up app lock" in the no-lock note) and came back
     * with it on. The PIN was just chosen (and typed to unlock), so the next change within this time does not ask for
     * it again. Before the lock existed the change would have gone through with only the note, so this weakens nothing.
     */
    const val SETUP_GRACE_MS = 10 * 60_000L

    fun needsCheck(change: ForwardChange): Boolean = !(change is ForwardChange.SetEnabled && !change.enabled)

    /**
     * "Pause all forwarding": resuming ([paused] false) starts sending again, so with the app lock on it asks for the
     * lock like turning a rule on does. Pausing only stops messages from leaving the phone and never asks.
     */
    fun pauseChangeNeedsCheck(paused: Boolean, lockEnabled: Boolean): Boolean = !paused && lockEnabled

    /** [lockSetUpAt]: when the user chose to set up the app lock from the no-lock note, null when not. */
    fun prompt(
        change: ForwardChange,
        gate: ForwardLockGate,
        lastVerifiedAt: Long?,
        now: Long,
        lockSetUpAt: Long? = null,
    ): ForwardGatePrompt = when {
        !needsCheck(change) -> ForwardGatePrompt.NONE
        gate.lockEnabled() ->
            if (within(lastVerifiedAt, now, GRACE_MS) || within(lockSetUpAt, now, SETUP_GRACE_MS)) {
                ForwardGatePrompt.NONE
            } else {
                ForwardGatePrompt.VERIFY
            }
        !gate.noLockNoteShown() -> ForwardGatePrompt.NO_LOCK_NOTE
        else -> ForwardGatePrompt.NONE
    }

    private fun within(since: Long?, now: Long, window: Long) = since != null && now >= since && now - since < window
}

/** ID-04: what a forward rule card says about the rule's forwards. */
data class ForwardRuleStats(
    val lastAt: Long,
    val lastStatus: ForwardStatus,
    /** Forwards sent in the last seven days (failed and skipped ones not counted). */
    val week: Int,
) {
    companion object {
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
        private val COUNTED = setOf(ForwardStatus.SENT, ForwardStatus.PENDING)

        /** Per rule id, from the forward log ([entries] in any order). Rules with no forward are left out. */
        fun of(entries: List<ForwardLogEntry>, now: Long): Map<Long, ForwardRuleStats> =
            entries.groupBy { it.ruleId }.mapValues { (_, rows) ->
                val last = rows.maxWith(compareBy<ForwardLogEntry> { it.at }.thenBy { it.id })
                ForwardRuleStats(
                    lastAt = last.at,
                    lastStatus = last.status,
                    week = rows.count { it.at >= now - WEEK_MS && it.status in COUNTED },
                )
            }
    }
}

/** BR-41: how many SMS parts a text takes. */
object SmsParts {
    private const val GSM_SINGLE = 160
    private const val GSM_PART = 153
    private const val UNICODE_SINGLE = 70
    private const val UNICODE_PART = 67

    /** Parts of a text of [length] characters; [unicode] for Persian and other non-GSM text. */
    fun count(length: Int, unicode: Boolean): Int {
        if (length <= 0) return 1
        val single = if (unicode) UNICODE_SINGLE else GSM_SINGLE
        val part = if (unicode) UNICODE_PART else GSM_PART
        return if (length <= single) 1 else (length + part - 1) / part
    }

    /** The example lengths the editor's cost note uses. */
    const val EXAMPLE_PERSIAN = 150
    const val EXAMPLE_ENGLISH = 300
}

/** ID-07: where the editor's one test SMS stands. */
enum class ForwardTestState { IDLE, CONFIRMING, SENDING, SENT, FAILED }

/** "09:05" style time for minutes after midnight, with the digits of [locale]. */
fun formatMinutes(minutes: Int, locale: java.util.Locale = java.util.Locale.getDefault()): String {
    val m = ((minutes % ForwardRuleMinutes.DAY) + ForwardRuleMinutes.DAY) % ForwardRuleMinutes.DAY
    return "%02d:%02d".format(locale, m / 60, m % 60)
}

internal object ForwardRuleMinutes {
    const val DAY = 24 * 60
}
