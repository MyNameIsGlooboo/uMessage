package com.glooboo.umessage.spam.data

import android.provider.Telephony
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.AllowGroupMember
import com.glooboo.umessage.spam.BlockReceipt
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.MMS_PLACEHOLDER_BODY
import com.glooboo.umessage.spam.MemberSource
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.RegexGuard
import com.glooboo.umessage.spam.RestoreOutcome
import com.glooboo.umessage.spam.RestoreReceipt
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.SpamStats
import com.glooboo.umessage.spam.engine.DomainMatch
import com.glooboo.umessage.spam.engine.GroupMembership
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.NumberReaders
import com.glooboo.umessage.spam.engine.RuleMatcher
import com.glooboo.umessage.spam.engine.SenderAddress
import com.glooboo.umessage.spam.engine.SenderFact
import com.glooboo.umessage.ui.spam.RuleNotes

class DefaultSpamRepository(
    private val platform: SpamPlatform,
    private val db: SpamDatabase,
    private val settingsStore: SpamSettingsStore,
    private val normalizer: NumberNormalizer,
    /** Country of a SIM, used to normalize numbers of rules scoped to that SIM. Null = default region. */
    private val regionForSubscription: (Int) -> String? = { null },
    /** The note on a rule added by "Block sender" on the Blocked screen, in the user's language. */
    private val blockedFromMessageNote: () -> String = { "" },
    /** The note on a rule added when a keyword or regex rule blocks a message and so blocks its sender too. */
    private val blockedFromKeywordNote: () -> String = { "" },
    /** The note on a rule added when a keyword or regex ALLOW rule allows a message and so allows its sender too. */
    private val allowedFromKeywordNote: () -> String = { "" },
    /** Provider truth (R6, S8): the countries of every SIM, the one region list senders are read with. */
    simRegions: () -> List<String> = { emptyList() },
    /** A-7: Android's own block list and the old Fossify number list; such a sender joins no group. */
    private val legacyBlocked: (String) -> Boolean = { false },
) : SpamRepository {

    private val dao get() = db.spamDao()

    /** The one reading of a sender for rules made from it and for group members (R6, S8). */
    private val readers = NumberReaders(normalizer, simRegions)

    private val groups = AllowGroups(db, readers)

    /** Matches the sender rules (number, sender name, prefix) of a sender, in every reading of [readers]. */
    private val senderMatcher = RuleMatcher(normalizer)

    /** D3 A: who is in which group rule, for the filter (check 8). */
    val groupMembership: GroupMembership get() = groups

    /** Serializes restores so a double tap cannot deliver the same message twice. */
    private val restoreLock = Mutex()

    /**
     * Kept MMS files of restores that can still be undone ([restore] until [finishRestore]). No row names them any
     * more, so the sweep must be told to keep them. Memory only: after a process death they are swept like any
     * other leftover, and the restore stays done.
     */
    private val heldMedia: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override val blocked: Flow<List<BlockedMessage>> =
        dao.observeBlocked().map { list -> list.map { it.toModel() } }

    override val rules: Flow<List<SpamRule>> =
        dao.observeRules().map { list -> list.map { it.toRule() } }

    override val settings: Flow<SpamSettings> = settingsStore.observe()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val stats: Flow<SpamStats> = combine(
        dao.observeEventCount(SpamEventType.BLOCKED),
        weekWindowStart().flatMapLatest { since -> dao.observeEventCountSince(SpamEventType.BLOCKED, since) },
        dao.observeEventCount(SpamEventType.RESTORED),
    ) { total, week, restored -> SpamStats(total, week, restored) }

    /** Start of the "last 7 days" window, moved forward regularly so a long-lived screen does not go stale. */
    private fun weekWindowStart(): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis() - WEEK_MS)
            delay(STATS_WINDOW_REFRESH_MS)
        }
    }

    fun currentRules(): List<SpamRule> = dao.getRules().map { it.toRule() }

    fun currentSettings(): SpamSettings = settingsStore.get()

    override suspend fun addRule(rule: SpamRule): Long = withContext(Dispatchers.IO) {
        if (rule.type == RuleType.REGEX) {
            RegexGuard.validate(rule.pattern)?.let { throw IllegalArgumentException("Rejected pattern: $it") }
        }
        db.runInTransaction<Long> {
            insertRule(rule).also { id -> if (rule.kind == ListKind.BLOCK) takeOutOfAutomatic(id) }
        }
    }

    /**
     * Spec 2.2: a block rule the user adds for an exact number or sender name (also on the rules screen) takes the
     * sender out of the group rules and, with ProviderTruthFlag on, removes its automatic allow rules, as
     * [blockAddress] does.
     * With the flag off, 0.4.14's "allow wins" stays (no group has members then).
     */
    private fun takeOutOfAutomatic(blockRuleId: Long) {
        val rule = dao.getRule(blockRuleId) ?: return
        if (rule.type != RuleType.NUMBER && rule.type != RuleType.SENDER_ID) return
        groups.leave(rule.pattern)
        if (ProviderTruthFlag.enabled) automaticAllowsOf(rule.pattern).forEach { dao.deleteRule(it.id) }
    }

    override suspend fun updateRule(rule: SpamRule): Long = withContext(Dispatchers.IO) {
        if (rule.type == RuleType.REGEX) {
            RegexGuard.validate(rule.pattern)?.let { throw IllegalArgumentException("Rejected pattern: $it") }
        }
        db.runInTransaction<Long> {
            val current = dao.getRule(rule.id) ?: throw NoSuchElementException("No rule ${rule.id}")
            val pattern = cleanPattern(rule)
            val other = dao.findRule(rule.kind, rule.type, pattern)
            if (other != null && other.id != rule.id) return@runInTransaction other.id
            // The counters belong to the engine: an edit from a screen that read the rule earlier must not reset them.
            // Since schema 9 every spam rule covers both SIMs (7.2): a SIM chosen on an older form is not kept.
            dao.updateRule(
                SpamRuleEntity.from(
                    rule.copy(
                        pattern = pattern, subscriptionId = null, createdAt = current.createdAt,
                        hitCount = current.hitCount, lastHit = current.lastHit,
                    )
                )
            )
            rule.id
        }
    }

    override suspend fun removeRule(id: Long) = withContext(Dispatchers.IO) { dao.deleteRule(id) }

    override suspend fun removeRules(ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        db.runInTransaction { ids.chunked(SQL_CHUNK).forEach(dao::deleteRules) }
    }

    override suspend fun restoreRules(rules: List<SpamRule>) = withContext(Dispatchers.IO) {
        db.runInTransaction {
            rules.forEach { rule ->
                if (rule.type == RuleType.REGEX && RegexGuard.validate(rule.pattern) != null) return@forEach
                val pattern = cleanPattern(rule)
                if (dao.findRule(rule.kind, rule.type, pattern) != null) return@forEach
                // Room ids are AUTOINCREMENT, so a deleted rule's id is never handed to another rule.
                val id = if (rule.id > 0 && dao.getRule(rule.id) == null) rule.id else 0
                dao.insertRule(SpamRuleEntity.from(rule.copy(id = id, pattern = pattern, subscriptionId = null)))
            }
        }
    }

    override suspend fun updateSettings(transform: (SpamSettings) -> SpamSettings) =
        withContext(Dispatchers.IO) { settingsStore.update(transform) }

    override suspend fun storeBlocked(message: BlockedMessage): Long = storeBlockedChecked(message).first

    override suspend fun storeBlockedChecked(
        message: BlockedMessage,
    ): Pair<Long, Boolean> = withContext(Dispatchers.IO) {
        val capped = message.copy(
            id = 0,
            address = BlockedLimits.limitAddress(message.address),
            body = BlockedLimits.limitBody(if (message.isMms && message.body.isBlank()) MMS_PLACEHOLDER_BODY else message.body),
            // A newly held message has not been looked at.
            unread = true,
        )
        var duplicate = false
        val id = db.runInTransaction<Long> {
            val existing = dao.findDuplicateBlocked(
                capped.address, capped.body, capped.isMms,
                capped.receivedAt - BlockedLimits.DUPLICATE_WINDOW_MS,
                capped.receivedAt + BlockedLimits.DUPLICATE_WINDOW_MS,
            )
            if (existing != null) {
                duplicate = true
                existing
            } else {
                val rowId = dao.insertBlocked(BlockedMessageEntity.from(capped))
                dao.insertEvent(SpamEventEntity(type = SpamEventType.BLOCKED, timestamp = System.currentTimeMillis()))
                // spam-23: a rule that blocked a new message counts it (one cheap UPDATE in this transaction, which
                // already runs off the main thread). A duplicate copy is not a new hit; a deleted rule matches no row.
                if (capped.reason == DecisionReason.BLOCK_RULE && capped.ruleId != null) {
                    dao.recordRuleHit(capped.ruleId, System.currentTimeMillis())
                }
                rowId
            }
        }
        if (duplicate) {
            // The carrier sent the same MMS twice; the first copy keeps its own content.
            capped.mediaFile?.let { platform.deleteMedia(listOf(it)) }
        } else if (settingsStore.get().notifyOnBlock) {
            runCatching { platform.notifyBlocked(capped.copy(id = id)) }
        }
        id to duplicate
    }

    /**
     * Records the result of the download uMessage started for a blocked MMS: [mediaFile] is the kept content (null
     * when the download failed) and [text] its text. Returns false when the row is gone (restored or deleted while
     * downloading); the caller then deletes [mediaFile]. Blocking; called from a receiver's worker thread.
     */
    fun attachDownloadedMedia(blockedId: Long, mediaFile: String?, text: String?): Boolean =
        db.runInTransaction<Boolean> {
            val row = dao.getBlocked(blockedId) ?: return@runInTransaction false
            val body = text?.takeIf { it.isNotBlank() }?.let(BlockedLimits::limitBody) ?: row.body
            val media = if (mediaFile != null) MmsMedia.STORED else MmsMedia.MISSING
            dao.updateMedia(blockedId, media, mediaFile, body) > 0
        }

    /**
     * Claims the blocked row and hands its content to the pending_sms write-ahead queue in one transaction, then
     * delivers it. A second call finds no row and does nothing; a crash after the claim is recovered by the
     * pending replay, so the message is neither lost nor inserted twice.
     *
     * Rules are not touched (T12): restoring one message must not whitelist the sender, because an allow rule beats
     * every block rule and a spoofed sender id would then get through anything. "Always allow" is a separate choice
     * the Blocked screen offers afterwards.
     */
    override suspend fun markNotSpam(blockedId: Long): RestoreOutcome =
        restore(blockedId).also { finishRestore(it) }.outcome

    /** [markNotSpam] without deleting kept MMS files yet, so [undoRestore] can still put the message back whole. */
    override suspend fun restore(blockedId: Long): RestoreReceipt = withContext(Dispatchers.IO) {
        restoreLock.withLock {
            val stored = dao.getBlocked(blockedId)?.toModel()
                ?: return@withLock RestoreReceipt(null, RestoreOutcome.NOT_FOUND)
            val receipt = if (stored.hasStoredMedia) restoreKeptMms(stored) else restoreText(blockedId)
            if (receipt.outcome != RestoreOutcome.FAILED) {
                receipt.message?.mediaFile?.let { heldMedia += it }
                runCatching { platform.cancelBlockedNotifications(listOf(blockedId)) }
            }
            receipt
        }
    }

    /**
     * Inbox first, then Blocked: the message is taken out of the inbox and only then put back, so a failure leaves it
     * in the inbox (where the restore put it) and never nowhere. The blocked row gets its old id and kept files back.
     */
    override suspend fun undoRestore(receipt: RestoreReceipt): Boolean = withContext(Dispatchers.IO) {
        val message = receipt.message
        val undoable = receipt.outcome != RestoreOutcome.FAILED && receipt.outcome != RestoreOutcome.NOT_FOUND
        if (message == null || !undoable) return@withContext false
        restoreLock.withLock {
            if (!takeOutOfInbox(receipt, message)) return@withLock false
            db.runInTransaction {
                if (dao.getBlocked(message.id) == null) dao.insertBlocked(BlockedMessageEntity.from(message))
                if (receipt.eventId > 0) dao.deleteEvent(receipt.eventId)
            }
            message.mediaFile?.let { heldMedia -= it }
            true
        }
    }

    /**
     * Where the restore put the message: nowhere for an MMS of which nothing was kept, the pending queue when the
     * inbox insert failed (and the replay has not delivered it yet), else the system row it became.
     */
    private fun takeOutOfInbox(receipt: RestoreReceipt, message: BlockedMessage): Boolean = when {
        receipt.outcome == RestoreOutcome.NOTHING -> true
        receipt.pendingId > 0 && dao.deletePending(receipt.pendingId) > 0 -> true
        receipt.inboxId > 0 -> runCatching { platform.removeRestored(receipt.inboxId, message.hasStoredMedia) }
            .getOrDefault(false)
        // spam-17: the delivery found the message already in the inbox and wrote no row, so there is nothing to take
        // out and the undo can put the blocked row back.
        receipt.pendingId <= 0 -> true
        else -> false
    }

    /**
     * The undo window is over: the kept MMS files the inbox copy no longer needs go. A file a blocked row names again
     * (the restore was undone) is left alone.
     */
    override suspend fun finishRestore(receipt: RestoreReceipt) = withContext(Dispatchers.IO) {
        val file = receipt.message?.mediaFile
        if (receipt.outcome == RestoreOutcome.FAILED || file == null) return@withContext
        restoreLock.withLock {
            heldMedia -= file
            if (file !in dao.allMediaFiles()) platform.deleteMedia(listOf(file))
        }
    }

    /**
     * A kept MMS goes into the inbox first and leaves Blocked only after that worked, so a failure loses nothing
     * (the row and its files stay, and the user is told). The lock keeps a double tap from inserting it twice. The
     * files stay until [finishRestore].
     */
    private fun restoreKeptMms(message: BlockedMessage): RestoreReceipt {
        val inboxId = runCatching { platform.deliverRestoredMms(message) }.getOrNull()
            ?: return RestoreReceipt(message, RestoreOutcome.FAILED)
        var eventId = 0L
        db.runInTransaction {
            if (dao.getBlocked(message.id) != null) {
                dao.deleteBlocked(listOf(message.id))
                val event = SpamEventEntity(type = SpamEventType.RESTORED, timestamp = System.currentTimeMillis())
                eventId = dao.insertEvent(event)
            }
        }
        return RestoreReceipt(message, RestoreOutcome.RESTORED, inboxId = inboxId, eventId = eventId)
    }

    /**
     * An SMS, or an MMS whose content was not kept: its text (if any) is restored as an SMS. When the inbox insert
     * fails the receipt names the pending row instead, which the replay delivers later.
     */
    private fun restoreText(blockedId: Long): RestoreReceipt {
        val now = System.currentTimeMillis()
        var pending: PendingSmsEntity? = null
        var message: BlockedMessage? = null
        var eventId = 0L
        db.runInTransaction {
            val row = dao.getBlocked(blockedId)?.toModel() ?: return@runInTransaction
            dao.deleteBlocked(listOf(blockedId))
            eventId = dao.insertEvent(SpamEventEntity(type = SpamEventType.RESTORED, timestamp = now))
            message = row

            // An MMS of which nothing was kept has nothing to restore; one with text gets that text as an SMS.
            if (row.canRestoreContent) {
                val entity = PendingSmsEntity(
                    address = row.address,
                    subject = "",
                    body = row.body,
                    date = row.receivedAt,
                    subscriptionId = row.subscriptionId,
                    status = Telephony.Sms.STATUS_NONE,
                    createdAt = now,
                    approved = true,
                )
                pending = entity.copy(id = dao.insertPending(entity))
            }
        }
        val restored = message ?: return RestoreReceipt(null, RestoreOutcome.NOT_FOUND)
        val receipt = RestoreReceipt(restored, restored.expectedRestore, eventId = eventId)
        val queued = pending ?: return receipt
        PendingSmsQueue.lease(queued.id, now)
        val inboxId = runCatching { platform.deliverRestored(queued) }.getOrNull()
        return if (inboxId != null) receipt.copy(inboxId = inboxId) else receipt.copy(pendingId = queued.id)
    }

    /**
     * Adds a block rule for the sender and removes their allow rules. Their messages already in Blocked stay there
     * (SU-18): the user may still want to read or restore them, and deleting is its own action.
     */
    override suspend fun blockSender(blockedId: Long) = withContext(Dispatchers.IO) {
        val message = dao.getBlocked(blockedId) ?: return@withContext
        // A-8: the same reading of the sender as [blockAddress]: every allow rule naming it goes, and its memberships.
        db.runInTransaction<BlockReceipt?> { blockInTransaction(message.address, blockedFromMessageNote()) }
        Unit
    }

    /**
     * After a keyword or regex BLOCK rule [ruleId] blocked the stored message [blockedId], also blocks its sender:
     * adds a block rule for the number or sender id and removes their allow rules. Does nothing when the rule is not a
     * block rule of those two types, when [mayBlock] says no for the address (a contact, a number the user wrote to,
     * or a lookup that failed), or when the sender already has a block rule. Never throws. True when a rule was added.
     */
    suspend fun blockSenderOfKeywordMatch(blockedId: Long, ruleId: Long?, mayBlock: (String) -> Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val matched = ruleId?.let { id -> dao.getRules().firstOrNull { it.id == id } }
                val message = dao.getBlocked(blockedId)
                val eligible = message != null && matched != null && isKeywordRule(matched, ListKind.BLOCK)
                if (message == null || !eligible || !mayBlock(message.address)) return@runCatching false
                val type = ruleTypeFor(message.address)
                val pattern = normalizer.normalize(message.address)
                db.runInTransaction<Boolean> {
                    val rules = dao.getRules().filter { it.type == type && it.pattern == pattern }
                    if (rules.any { it.kind == ListKind.BLOCK }) return@runInTransaction false
                    rules.filter { it.kind == ListKind.ALLOW }.forEach { dao.deleteRule(it.id) }
                    val note = blockedFromKeywordNote()
                    insertRule(SpamRule(kind = ListKind.BLOCK, type = type, pattern = pattern, note = note))
                    true
                }
            }.getOrDefault(false)
        }

    /**
     * After a keyword or regex ALLOW rule [ruleId] allowed a delivered message from [address], also allows its sender:
     * adds an allow rule for the number or sender id. The mirror of [blockSenderOfKeywordMatch], but it never removes
     * a rule: a sender that already has any rule (an explicit block wins and stays; an explicit allow is enough) is
     * left alone. Does nothing when the rule is not an allow rule of those two types, when the address is blank, or
     * when [mayAllow] says no (a contact, a number the user wrote to, or a lookup that failed). Never throws. True
     * when a rule was added.
     */
    suspend fun allowSenderOfKeywordMatch(address: String, ruleId: Long?, mayAllow: (String) -> Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val matched = ruleId?.let { id -> dao.getRules().firstOrNull { it.id == id } }
                val pattern = normalizer.normalize(address)
                val eligible = matched != null && isKeywordRule(matched, ListKind.ALLOW) && pattern.isNotEmpty()
                if (!eligible || !mayAllow(address)) return@runCatching false
                val type = ruleTypeFor(address)
                db.runInTransaction<Boolean> {
                    if (dao.getRules().any { it.type == type && it.pattern == pattern }) return@runInTransaction false
                    val note = allowedFromKeywordNote()
                    insertRule(SpamRule(kind = ListKind.ALLOW, type = type, pattern = pattern, note = note))
                    true
                }
            }.getOrDefault(false)
        }

    private fun isKeywordRule(rule: SpamRuleEntity, kind: ListKind): Boolean =
        rule.kind == kind && (rule.type == RuleType.KEYWORD || rule.type == RuleType.REGEX)

    /**
     * Provider truth (R1, facts first): writes [fact] for the sender of [address] before its text is stored. Every
     * write is idempotent, so the replay of the pending queue can write it again. Blocking; never throws. False when
     * nothing could be written (the caller retries once, then fails open).
     *
     * - [SenderFact.AutoAllow] (H4 A): an allow rule for the number or sender name, with the "allowed from a keyword"
     *   note, not acknowledged, so the chat shows its line with Block and Keep. A block still wins over it.
     * - [SenderFact.AutoBlock] (M-SP1, also the old Fossify word list): a block rule with the "blocked from a keyword"
     *   note; the sender's automatic allow rules and group memberships go in the same transaction.
     * - [SenderFact.JoinCodeGroup]: the sender joins "Senders of a verification code" (D3 A).
     */
    fun writeSenderFact(address: String, fact: SenderFact): Boolean = runCatching {
        if (SenderAddress.isUnreadable(address)) return@runCatching false
        val type = ruleTypeFor(address)
        val pattern = readers.rulePattern(address)
        if (pattern.isEmpty()) return@runCatching false
        val onBlockList = isOnLegacyBlockList(address)
        db.runInTransaction<Boolean> {
            // A-7: a block rule added since the verdict (or the Android block list) wins: nothing is allowed. The fact
            // is settled; the place read afterwards is Spam.
            val blocked = fact !is SenderFact.AutoBlock && (onBlockList || hasBlockRule(address))
            when {
                blocked -> true
                fact is SenderFact.AutoAllow -> allowAutomatically(type, pattern)
                fact is SenderFact.AutoBlock -> blockAutomatically(address, type, pattern)
                else -> groups.join(AllowGroup.CODE, address, MemberSource.LIVE)
            }
        }
    }.getOrDefault(false)

    /** A-11: counts one more text decided by the word rule [ruleId] on the new path (step D calls it once stored). */
    fun recordWordRuleHit(ruleId: Long, at: Long = System.currentTimeMillis()): Boolean =
        runCatching { dao.recordRuleHit(ruleId, at) > 0 }.getOrDefault(false)

    private fun isOnLegacyBlockList(address: String): Boolean =
        runCatching { legacyBlocked(address) }.getOrDefault(false)

    /** H4 A: an allow rule with the "allowed from a keyword" note, unless the sender has an allow rule already. */
    private fun allowAutomatically(type: RuleType, pattern: String): Boolean {
        val allowed = dao.getRules().any { it.kind == ListKind.ALLOW && it.type == type && it.pattern == pattern }
        if (!allowed) {
            insertRule(SpamRule(kind = ListKind.ALLOW, type = type, pattern = pattern, note = allowedFromKeywordNote()))
        }
        return true
    }

    /** M-SP1: a block rule with the "blocked from a keyword" note; automatic allows and memberships go. */
    private fun blockAutomatically(address: String, type: RuleType, pattern: String): Boolean {
        automaticAllowsOf(address).forEach { dao.deleteRule(it.id) }
        groups.leave(address)
        insertRule(SpamRule(kind = ListKind.BLOCK, type = type, pattern = pattern, note = blockedFromKeywordNote()))
        return true
    }

    /** Allow rules the filter made (H4) that name exactly the sender of [address]. */
    private fun automaticAllowsOf(address: String): List<SpamRuleEntity> =
        exactRulesOf(address).filter { it.kind == ListKind.ALLOW && RuleNotes.isMadeByFilter(it.note) }

    /** Rules of type NUMBER or SENDER_ID that name the sender of [address] in any reading of the region list. */
    private fun exactRulesOf(address: String): List<SpamRuleEntity> =
        senderRulesOf(address).filter { it.type == RuleType.NUMBER || it.type == RuleType.SENDER_ID }

    /** Rules of a sender type (number, sender name, prefix) that name [address] in any reading of the region list. */
    private fun senderRulesOf(address: String): List<SpamRuleEntity> {
        val probe = IncomingMessage(address = address, body = "", subscriptionId = NO_SIM)
        val numbers = readers.readers()
        return dao.getRules().filter { it.type in SENDER_TYPES && senderMatcher.matches(it.toRule(), probe, numbers) }
    }

    /** Whether a block rule names the sender (number, sender name or prefix). */
    private fun hasBlockRule(address: String): Boolean = senderRulesOf(address).any { it.kind == ListKind.BLOCK }

    override val groupMembers: Flow<List<AllowGroupMember>> =
        dao.observeMembers().map { list -> list.map { it.toModel() } }

    override suspend fun joinGroup(group: AllowGroup, address: String, source: MemberSource): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val onBlockList = isOnLegacyBlockList(address)
                db.runInTransaction<Boolean> {
                    // A reply to a blocked sender, or a blocked sender found by the history check, changes nothing.
                    !onBlockList && !hasBlockRule(address) && groups.join(group, address, source)
                }
            }.getOrDefault(false)
        }

    override suspend fun removeGroupMembers(members: List<AllowGroupMember>): List<AllowGroupMember> =
        withContext(Dispatchers.IO) {
            db.runInTransaction<List<AllowGroupMember>> {
                members.filter { dao.deleteMember(it.group, it.senderKey) > 0 }
            }
        }

    override suspend fun restoreGroupMembers(members: List<AllowGroupMember>) = withContext(Dispatchers.IO) {
        if (members.isEmpty()) return@withContext
        db.runInTransaction { members.forEach { dao.putMember(AllowGroupMemberEntity.from(it)) } }
    }

    /**
     * A-9: members of a settings backup, in one transaction. Each keeps its join time, source and Keep state from the
     * file; the key is made on this phone. Never for an unreadable or blocked sender, never twice in a group.
     */
    override suspend fun importGroupMembers(members: List<AllowGroupMember>): Int = withContext(Dispatchers.IO) {
        val readable = members.filterNot { SenderAddress.isUnreadable(it.address) || isOnLegacyBlockList(it.address) }
        if (readable.isEmpty()) return@withContext 0
        db.runInTransaction<Int> {
            readable.count { member ->
                val key = readers.senderKey(member.address)
                val known = groups.membersOf(member.address).any { it.group == member.group }
                val usable = key.isNotEmpty() && !known && !hasBlockRule(member.address)
                if (usable) dao.insertMember(AllowGroupMemberEntity.from(member.copy(senderKey = key)))
                usable
            }
        }
    }

    override suspend fun acknowledgeSender(address: String) = withContext(Dispatchers.IO) {
        db.runInTransaction {
            groups.acknowledge(address)
            val ids = automaticAllowsOf(address).map { it.id }
            if (ids.isNotEmpty()) dao.acknowledgeRules(ids)
        }
    }

    override suspend fun blockAddress(address: String, note: String): BlockReceipt? = withContext(Dispatchers.IO) {
        if (SenderAddress.isUnreadable(address)) return@withContext null
        db.runInTransaction<BlockReceipt?> { blockInTransaction(address, note) }
    }

    /**
     * The block of [blockAddress] and [blockSender], inside the caller's transaction: allow rules naming exactly the
     * sender and its memberships go, a block rule is added. [BlockReceipt.stillAllowedBy] names a user allow rule (a
     * prefix) that still wins, so the screen can say why the chat stays in Messages.
     */
    private fun blockInTransaction(address: String, note: String): BlockReceipt? {
        if (SenderAddress.isUnreadable(address)) return null
        val type = ruleTypeFor(address)
        val pattern = readers.rulePattern(address)
        if (pattern.isEmpty()) return null
        val allows = exactRulesOf(address).filter { it.kind == ListKind.ALLOW }
        allows.forEach { dao.deleteRule(it.id) }
        val members = groups.leave(address)
        val ruleId = if (dao.findRule(ListKind.BLOCK, type, pattern) != null) {
            0L
        } else {
            insertRule(SpamRule(kind = ListKind.BLOCK, type = type, pattern = pattern, note = note))
        }
        val stillAllowed = senderRulesOf(address).firstOrNull {
            it.kind == ListKind.ALLOW && !RuleNotes.isMadeByFilter(it.note)
        }
        return BlockReceipt(address, ruleId, allows.map { it.toRule() }, members.map { it.toModel() }, stillAllowed?.id)
    }

    override suspend fun undoBlock(receipt: BlockReceipt) = withContext(Dispatchers.IO) {
        db.runInTransaction {
            if (receipt.ruleId > 0) dao.deleteRule(receipt.ruleId)
            receipt.removedRules.forEach { rule ->
                if (dao.findRule(rule.kind, rule.type, rule.pattern) == null) {
                    val id = if (rule.id > 0 && dao.getRule(rule.id) == null) rule.id else 0
                    dao.insertRule(SpamRuleEntity.from(rule.copy(id = id, subscriptionId = null)))
                }
            }
            receipt.removedMembers.forEach { dao.putMember(AllowGroupMemberEntity.from(it)) }
        }
    }

    override suspend fun markBlockedRead(ids: List<Long>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) { ids.chunked(SQL_CHUNK).forEach { dao.markBlockedRead(it) } }
    }

    override suspend fun deleteBlocked(ids: List<Long>): Unit = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val files = db.runInTransaction<List<String>> {
            ids.chunked(SQL_CHUNK).flatMap { chunk -> dao.mediaFilesOf(chunk).also { dao.deleteBlocked(chunk) } }
        }
        if (files.isNotEmpty()) platform.deleteMedia(files)
        runCatching { platform.cancelBlockedNotifications(ids) }
    }

    /** Deletes expired blocked messages together with their kept MMS content, and content no row refers to. */
    override suspend fun purgeExpired() = withContext(Dispatchers.IO) {
        val days = settingsStore.get().purgeAfterDays
        if (days > 0) {
            val before = System.currentTimeMillis() - days * DAY_MS
            val files = db.runInTransaction<List<String>> {
                dao.mediaFilesBefore(before).also { dao.deleteBlockedBefore(before) }
            }
            if (files.isNotEmpty()) platform.deleteMedia(files)
        }
        platform.sweepMedia(dao.allMediaFiles().toSet() + heldMedia)
    }

    private fun ruleTypeFor(address: String) = if (normalizer.isSenderId(address)) RuleType.SENDER_ID else RuleType.NUMBER

    private fun cleanPattern(rule: SpamRule): String = when (rule.type) {
        RuleType.NUMBER -> ruleNormalizer(rule.subscriptionId).normalize(rule.pattern)
        RuleType.SENDER_ID -> normalizer.normalize(rule.pattern)
        RuleType.PREFIX -> normalizer.cleanPrefix(rule.pattern)
        RuleType.DOMAIN -> DomainMatch.clean(rule.pattern)
        else -> rule.pattern.trim()
    }

    /**
     * Adds [rule] unless the same kind, type and pattern exists (its id is returned then). Since schema 9 every spam
     * rule covers both SIMs (7.2), so a SIM on [rule] only says how a national number is read.
     */
    private fun insertRule(rule: SpamRule): Long {
        val pattern = cleanPattern(rule)
        val existing = dao.findRule(rule.kind, rule.type, pattern)
        if (existing != null) return existing.id
        return dao.insertRule(SpamRuleEntity.from(rule.copy(id = 0, pattern = pattern, subscriptionId = null)))
    }

    /** A rule scoped to one SIM reads national numbers in that SIM's country. */
    private fun ruleNormalizer(subscriptionId: Int?): NumberNormalizer {
        val region = subscriptionId?.let { runCatching { regionForSubscription(it) }.getOrNull() }
        return if (region.isNullOrBlank()) normalizer else normalizer.withRegion(region)
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val WEEK_MS = 7 * DAY_MS
        const val STATS_WINDOW_REFRESH_MS = 15L * 60 * 1000

        /** Stays below SQLite's limit of bound variables per statement. */
        const val SQL_CHUNK = 500

        /** No receiving SIM: rules and members are read the same on both SIMs. */
        const val NO_SIM = -1

        /** Rule types that name a sender rather than read its text. */
        val SENDER_TYPES = setOf(RuleType.NUMBER, RuleType.SENDER_ID, RuleType.PREFIX)
    }
}
