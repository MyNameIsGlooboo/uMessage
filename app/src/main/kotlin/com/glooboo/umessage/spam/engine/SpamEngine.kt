package com.glooboo.umessage.spam.engine

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SubscriptionManager
import kotlinx.coroutines.runBlocking
import com.glooboo.umessage.helpers.W3aPrefs
import com.glooboo.umessage.spam.ForwardRepository
import com.glooboo.umessage.spam.data.DefaultForwardRepository
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.DecisionRuleText
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.SpamLogEntry
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.data.AndroidSpamPlatform
import com.glooboo.umessage.spam.data.BlockedNotifier
import com.glooboo.umessage.spam.data.DefaultSpamRepository
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.data.SpamSettingsStore

/**
 * Wiring and the fail-open entry points used by the receivers. The singletons below hold no Context directly:
 * Android access goes through the application context captured by [AndroidSpamPlatform] and the environment
 * classes, behind interfaces.
 */
object SpamEngine {
    @Volatile private var repository: DefaultSpamRepository? = null
    @Volatile private var filter: DefaultSpamFilter? = null
    @Volatile private var settingsStore: SpamSettingsStore? = null
    @Volatile private var forwardPlanner: ForwardPlanner? = null
    @Volatile private var checker: SystemContactChecker? = null

    fun install() {
        UMessage.filterFactory = { filter(it) }
        UMessage.repositoryFactory = { repository(it) }
    }

    fun repository(context: Context): DefaultSpamRepository = repository ?: synchronized(this) {
        repository ?: run {
            val app = context.applicationContext
            DefaultSpamRepository(
                platform = AndroidSpamPlatform(app),
                db = SpamDatabase.getInstance(app),
                settingsStore = settingsStore(app),
                normalizer = normalizer(app),
                regionForSubscription = { subscriptionRegion(app, it) },
                blockedFromMessageNote = { com.glooboo.umessage.ui.spam.RuleNotes.BLOCKED_FROM_MESSAGE },
                blockedFromKeywordNote = { com.glooboo.umessage.ui.spam.RuleNotes.BLOCKED_FROM_KEYWORD },
                allowedFromKeywordNote = { com.glooboo.umessage.ui.spam.RuleNotes.ALLOWED_FROM_KEYWORD },
            ).also { repository = it }
        }
    }

    fun filter(context: Context): DefaultSpamFilter = filter ?: synchronized(this) {
        filter ?: run {
            val app = context.applicationContext
            val repo = repository(app)
            val normalizer = normalizer(app)
            DefaultSpamFilter(
                rules = repo::currentRules,
                settings = repo::currentSettings,
                contacts = contactChecker(app),
                normalizer = normalizer,
                legacy = FossifyLegacyBlocklist(app),
                regionForSubscription = { subscriptionRegion(app, it) },
            ).also { filter = it }
        }
    }

    /**
     * The one contact checker of the process: it registers content observers and keeps the address book and the sent
     * history in memory, so the filter and the helpers below share it instead of each making (and leaking) their own.
     */
    fun contactChecker(context: Context): SystemContactChecker = checker ?: synchronized(this) {
        checker ?: run {
            val app = context.applicationContext
            SystemContactChecker(app, normalizer(app)).also { checker = it }
        }
    }

    /** Stops the shared checker's observers and forgets it (and the filter that holds it); a test starts clean. */
    internal fun resetContactChecker() = synchronized(this) {
        checker?.close()
        checker = null
        filter = null
    }

    /** A thin wrapper over the database's DAO, made on each call so it always uses the current database instance. */
    fun forwardRepository(context: Context): ForwardRepository =
        DefaultForwardRepository(SpamDatabase.getInstance(context.applicationContext).forwardDao())

    /** The forward rules' matcher: the same number, sender id, keyword and pattern reading as the spam filter. */
    fun forwardPlanner(context: Context): ForwardPlanner = forwardPlanner ?: synchronized(this) {
        forwardPlanner ?: run {
            val app = context.applicationContext
            ForwardPlanner(
                matcher = RuleMatcher(normalizer(app), regionForSubscription = { subscriptionRegion(app, it) }),
                simInSlot = { slot -> subscriptionInSlot(app, slot) },
                codeMask = { W3aPrefs.get(app).forwardCodeMasker() },
            ).also { forwardPlanner = it }
        }
    }

    /** The active SIM in 1-based [slot], or null (empty slot, no permission). */
    @SuppressLint("MissingPermission")
    private fun subscriptionInSlot(context: Context, slot: Int): Int? = runCatching {
        context.getSystemService(SubscriptionManager::class.java)
            ?.getActiveSubscriptionInfoForSimSlotIndex(slot - 1)?.subscriptionId
    }.getOrNull()

    private fun settingsStore(context: Context): SpamSettingsStore = settingsStore ?: synchronized(this) {
        settingsStore ?: SpamSettingsStore(context).also { settingsStore = it }
    }

    private fun normalizer(context: Context): NumberNormalizer {
        val app = context.applicationContext
        return NumberNormalizer { defaultRegion(app) }
    }

    /** Whether the user has written to [address] before: such a number is known, like a contact. Never throws. */
    fun hasWrittenTo(context: Context, address: String): Boolean = runCatching {
        contactChecker(context).sentTo(address) == ContactLookup.FOUND
    }.getOrDefault(false)

    /** Returns the Block decision, or null when allowed or when the filter itself fails (never drop on error). */
    fun block(context: Context, message: IncomingMessage, source: String? = null): FilterDecision.Block? =
        evaluate(context, message, source) as? FilterDecision.Block

    /**
     * Full decision, logged to [SpamLog]. Null when the filter itself failed, including with an Error such as
     * ExceptionInInitializerError or OutOfMemoryError (callers must then deliver).
     */
    fun evaluate(context: Context, message: IncomingMessage, source: String? = null): FilterDecision? {
        val where = source ?: if (message.isMms) "MMS" else "SMS"
        val decision = try {
            UMessage.filter(context).evaluate(message)
        } catch (t: Throwable) {
            record(context, message, "ERROR", t.javaClass.simpleName, where)
            return null
        }
        // DecisionReason.CONTACTS_UNAVAILABLE lands here too, so a missing contacts permission is visible in the log.
        val ruleId = when (decision) {
            is FilterDecision.Block -> decision.ruleId
            is FilterDecision.Allow -> decision.ruleId
        }
        record(
            context, message, if (decision is FilterDecision.Block) "BLOCK" else "ALLOW", decision.reason.name, where,
            ruleId, ruleId?.let { ruleLabelOf(context, it) }.orEmpty(),
        )
        return decision
    }

    /** The short label (the value) of rule [id] for the decision log; empty when the rule can't be read. */
    private fun ruleLabelOf(context: Context, id: Long): String = try {
        repository(context).currentRules().firstOrNull { it.id == id }?.let { DecisionRuleText.label(it) }.orEmpty()
    } catch (_: Throwable) {
        ""
    }

    private fun record(
        context: Context,
        message: IncomingMessage,
        decision: String,
        reason: String,
        source: String,
        ruleId: Long? = null,
        ruleLabel: String = "",
    ) {
        try {
            UMessage.log(context).record(
                SpamLogEntry(
                    System.currentTimeMillis(), message.address, message.subscriptionId, decision, reason, source,
                    ruleId, ruleLabel,
                )
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * Write-ahead store of a blocked message. Returns false when storing failed for any reason (Errors included),
     * so the caller delivers it instead. An MMS with a blank body is stored with the placeholder text.
     */
    fun storeBlocked(context: Context, message: IncomingMessage, decision: FilterDecision.Block): Boolean =
        storeBlockedId(context, message, decision) != null

    /**
     * [storeBlocked] returning the row id (null when storing failed). For an MMS, [media] / [mediaFile] say what
     * of its content is kept (default: nothing).
     */
    fun storeBlockedId(
        context: Context,
        message: IncomingMessage,
        decision: FilterDecision.Block,
        media: MmsMedia = if (message.isMms) MmsMedia.MISSING else MmsMedia.NONE,
        mediaFile: String? = null,
        onDuplicate: (() -> Unit)? = null,
    ): Long? {
        return try {
            val (id, duplicate) = runBlocking {
                val repo = UMessage.repository(context)
                repo.storeBlockedChecked(
                    BlockedMessage(
                        address = message.address,
                        body = message.body,
                        subscriptionId = message.subscriptionId,
                        isMms = message.isMms,
                        receivedAt = message.receivedAt,
                        reason = decision.reason,
                        ruleId = decision.ruleId,
                        media = media,
                        mediaFile = mediaFile,
                    )
                )
            }
            if (duplicate) onDuplicate?.invoke()
            blockSenderOfKeyword(context, id, decision)
            purgeExpired(context)
            id
        } catch (t: Throwable) {
            record(context, message, "ERROR", "STORE_FAILED_" + t.javaClass.simpleName, "STORE")
            null
        }
    }

    /**
     * A keyword or regex block rule blocks the sender too, once the message is really stored (so probes that only
     * evaluate never add rules). A contact, a number the user wrote to, or a failed lookup keeps the sender unblocked.
     * Never throws.
     */
    private fun blockSenderOfKeyword(context: Context, blockedId: Long, decision: FilterDecision.Block) {
        if (decision.reason != DecisionReason.BLOCK_RULE || decision.ruleId == null) return
        runCatching {
            val app = context.applicationContext
            val repo = UMessage.repository(app) as? DefaultSpamRepository ?: return
            val contacts = contactChecker(app)
            runBlocking {
                repo.blockSenderOfKeywordMatch(blockedId, decision.ruleId) { address ->
                    contacts.canReadContacts() &&
                        contacts.lookup(address) == ContactLookup.NOT_FOUND &&
                        contacts.sentTo(address) == ContactLookup.NOT_FOUND
                }
            }
        }
    }

    /**
     * A keyword or regex allow rule allows the sender too, once the message is really delivered (so probes that only
     * evaluate never add rules). Does nothing for any other decision, including an allow by a sender rule or a
     * contact. A contact, a number the user wrote to, a sender that already has a rule, or a failed lookup leaves the
     * rules alone. Never throws.
     */
    fun allowSenderOfKeyword(context: Context, message: IncomingMessage, decision: FilterDecision?) {
        val allow = decision as? FilterDecision.Allow ?: return
        if (allow.reason != DecisionReason.ALLOW_RULE || allow.ruleId == null) return
        runCatching {
            val app = context.applicationContext
            val repo = UMessage.repository(app) as? DefaultSpamRepository ?: return
            val contacts = contactChecker(app)
            runBlocking {
                repo.allowSenderOfKeywordMatch(message.address, allow.ruleId) { address ->
                    contacts.canReadContacts() &&
                        contacts.lookup(address) == ContactLookup.NOT_FOUND &&
                        contacts.sentTo(address) == ContactLookup.NOT_FOUND
                }
            }
        }
    }

    /**
     * Records what the download of a blocked MMS produced (see [DefaultSpamRepository.attachDownloadedMedia]).
     * False when the row is gone or recording failed; the caller then deletes [mediaFile].
     */
    fun attachDownloadedMedia(context: Context, blockedId: Long, mediaFile: String?, text: String?): Boolean = try {
        (UMessage.repository(context) as? DefaultSpamRepository)?.attachDownloadedMedia(blockedId, mediaFile, text) == true
    } catch (_: Throwable) {
        false
    }

    /**
     * Deletes expired blocked messages, at most once per [com.glooboo.umessage.spam.data.PurgePolicy.INTERVAL_MS].
     * Called from the receivers and app start; the UI calls the repository directly for an immediate purge.
     */
    fun purgeExpired(context: Context, now: Long = System.currentTimeMillis()) {
        try {
            val app = context.applicationContext
            val store = settingsStore(app)
            if (!store.claimPurge(now)) return
            // The decision log has its own, shorter retention; it goes first so a failing repository purge
            // can't keep old sender numbers around.
            runCatching { UMessage.log(app).purgeExpired() }
            // spam-14: a purge that failed gives the claim back, so the next message retries it, not 6 hours later.
            try {
                runBlocking { repository(app).purgeExpired() }
            } catch (t: Throwable) {
                store.releasePurge()
                throw t
            }
        } catch (_: Throwable) {
        }
    }
}
