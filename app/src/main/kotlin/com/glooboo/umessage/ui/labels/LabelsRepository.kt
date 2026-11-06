package com.glooboo.umessage.ui.labels

import com.glooboo.umessage.interfaces.LabelsDao
import com.glooboo.umessage.models.ConversationLabel
import com.glooboo.umessage.models.Label

/**
 * I29: changes to labels, on the database. Deleting a label removes only its links to conversations; no conversation or
 * message is ever touched here.
 */
class LabelsRepository(private val dao: LabelsDao) {

    /** Adds a label and puts [threadIds] under it. Nothing is written unless the name passes [LabelRules.check]. */
    suspend fun add(name: String, color: Int, threadIds: Collection<Long> = emptyList()): LabelResult {
        val labels = dao.getLabels()
        val result = LabelRules.check(labels, name)
        if (result != LabelResult.Ok) return result
        val sortOrder = (labels.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val id = dao.insertLabel(Label(name = LabelRules.cleanName(name), color = color, sortOrder = sortOrder))
        threadIds.forEach { dao.addToConversation(ConversationLabel(it, id)) }
        return LabelResult.Ok
    }

    suspend fun update(labelId: Long, name: String, color: Int): LabelResult {
        val labels = dao.getLabels()
        val label = labels.firstOrNull { it.id == labelId } ?: return LabelResult.Empty
        val result = LabelRules.check(labels, name, exceptId = labelId)
        if (result != LabelResult.Ok) return result
        dao.updateLabel(label.copy(name = LabelRules.cleanName(name), color = color))
        return LabelResult.Ok
    }

    suspend fun delete(labelId: Long) {
        dao.deleteLabelLinks(labelId)
        dao.deleteLabelRow(labelId)
    }

    /** Puts every conversation of [threadIds] under the label, or takes them all out of it. */
    suspend fun setAssigned(threadIds: Collection<Long>, labelId: Long, assigned: Boolean) {
        threadIds.forEach { threadId ->
            if (assigned) {
                dao.addToConversation(ConversationLabel(threadId, labelId))
            } else {
                dao.removeFromConversation(threadId, labelId)
            }
        }
    }
}
