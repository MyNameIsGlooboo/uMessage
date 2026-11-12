package com.glooboo.umessage.ui.labels

import androidx.compose.runtime.Immutable
import com.glooboo.umessage.models.ConversationLabel
import com.glooboo.umessage.models.Label

/** How a label name was judged; [Error] means the database failed and nothing was changed. */
enum class LabelResult { Ok, Empty, TooMany, Duplicate, Error }

/** The colours a label can have. Stored as the ARGB int in [Label.color]. */
enum class LabelColor(val argb: Long) {
    Red(0xFFE53935),
    Orange(0xFFFB8C00),
    Yellow(0xFFF9A825),
    Green(0xFF43A047),
    Teal(0xFF00ACC1),
    Blue(0xFF1E88E5),
    Purple(0xFF8E24AA),
    Brown(0xFF6D4C41),
    ;

    val color: Int get() = argb.toInt()
}

/** I29: the rules for label names, how many there may be and which colour comes next. Pure JVM. */
object LabelRules {
    /** Enough to file chats by purpose; more would crowd the search page's tiles. */
    const val MAX_LABELS = 10
    const val MAX_NAME_LENGTH = 20

    private val spaces = Regex("""\s+""")

    fun cleanName(raw: String): String = raw.replace(spaces, " ").trim().take(MAX_NAME_LENGTH)

    /** [exceptId] is the label being renamed: it may keep its own name and is not a new label for the limit. */
    fun check(labels: List<Label>, rawName: String, exceptId: Long? = null): LabelResult {
        val name = cleanName(rawName)
        return when {
            name.isEmpty() -> LabelResult.Empty
            exceptId == null && labels.size >= MAX_LABELS -> LabelResult.TooMany
            labels.any { it.id != exceptId && it.name.equals(name, ignoreCase = true) } -> LabelResult.Duplicate
            else -> LabelResult.Ok
        }
    }

    /** The first palette colour no label has yet, else the palette goes round again. */
    fun nextColor(labels: List<Label>): Int {
        val palette = LabelColor.entries.map { it.color }
        val unused = palette.firstOrNull { colour -> labels.none { it.color == colour } }
        return unused ?: palette[labels.size % palette.size]
    }
}

/** How many of some conversations carry a label. */
enum class LabelCoverage { None, Some, All }

/** The labels and which conversations carry them. [loaded] is false until the database answered the first time. */
@Immutable
data class LabelsUiState(
    val loaded: Boolean = false,
    val labels: List<Label> = emptyList(),
    val threadsByLabel: Map<Long, Set<Long>> = emptyMap(),
) {
    fun label(id: Long): Label? = labels.firstOrNull { it.id == id }

    fun threadsOf(labelId: Long): Set<Long> = threadsByLabel[labelId].orEmpty()

    fun coverage(labelId: Long, threadIds: Collection<Long>): LabelCoverage {
        val carrying = threadsOf(labelId)
        val count = threadIds.count { it in carrying }
        return when {
            count == 0 -> LabelCoverage.None
            count == threadIds.size -> LabelCoverage.All
            else -> LabelCoverage.Some
        }
    }

    companion object {
        fun of(labels: List<Label>, links: List<ConversationLabel>): LabelsUiState = LabelsUiState(
            loaded = true,
            labels = labels,
            threadsByLabel = links.groupBy({ it.labelId }, { it.threadId }).mapValues { it.value.toSet() },
        )
    }
}
