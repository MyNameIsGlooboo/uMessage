package com.glooboo.umessage.ui.thread

import android.content.Context
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.spam.SpamThreads
import com.glooboo.umessage.ui.spam.SenderPlace
import com.glooboo.umessage.ui.spam.SpamRepositoryProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Provider truth (4.3): works out the chat line of the open one-to-one chat and keeps it in step with the rules, the
 * group members and the switches. Nothing runs while ProviderTruthFlag is off. Main thread.
 */
internal class ChatLineController(
    private val context: Context,
    private val state: ChatLineState,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private var address: String? = null

    /**
     * Follows the sender [address] (null: a group or no single sender, no line). [force] works it out again for the
     * same sender (the screen came back: a contact may have been added meanwhile).
     */
    fun follow(address: String?, force: Boolean = false) {
        if (!ProviderTruthFlag.enabled) return
        if (!force && address == this.address && job?.isActive == true) return
        this.address = address
        job?.cancel()
        if (address == null) {
            state.clear()
            return
        }
        val repo = runCatching { SpamRepositoryProvider.get(context) }.getOrNull() ?: return
        val place = SenderPlace.of(context)
        job = scope.launch {
            combine(repo.rules, repo.groupMembers, repo.settings) { rules, members, settings ->
                withContext(Dispatchers.IO) {
                    val readers = SpamThreads.readers(context)
                    val keys = readers.senderKeys(address)
                    val own = members.filter { it.senderKey in keys || readers.same(it.address, address) }
                    ChatLinePolicy.lineFor(place.verdict(address), rules, own, settings) to settings.filterOn
                }
            }.catch { }.collect { (line, filterOn) -> state.update(line, filterOn) }
        }
    }
}
