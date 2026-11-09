package com.glooboo.umessage.ui.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.glooboo.umessage.R
import com.glooboo.umessage.models.SearchResult
import com.glooboo.umessage.ui.conversations.ContactAvatar
import com.glooboo.umessage.ui.conversations.RowLabel
import com.glooboo.umessage.ui.newconversation.loadPhoneContacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.commons.models.SimpleContact

/*
 * The results as Google Messages draws them: each section is a heading row ("Conversations", "Contacts") with a
 * "See all" link, and under it ONE rounded card holding the section's rows, a thin gap between rows.
 */

/** How many rows a section shows before "See all". */
internal const val SECTION_PREVIEW = 3

private val CardOuterRadius = 24.dp
private val CardInnerRadius = 4.dp
private val RowGap = 2.dp
@Suppress("MagicNumber")
private val MatchWash = Color(0xFFFFE082)
@Suppress("MagicNumber")
private val MatchInk = Color(0xFF2B2100)
private const val MIN_NUMBER_DIGITS = 3

/** The yellow wash behind the matched characters of a title. */
internal val TitleMatchStyle = SpanStyle(background = MatchWash, color = MatchInk)

/** A contact that matches the typed text, with the one number that is shown for it. */
internal data class ContactHit(val contact: SimpleContact, val number: String, val typeLabel: String)

/** The contacts of [all] whose name, or whose number for a digits-only [query], holds [query]. */
internal fun matchContacts(
    all: List<SimpleContact>,
    query: String,
    typeLabel: (Int, String) -> String,
): List<ContactHit> {
    if (!isSearchable(query)) return emptyList()
    val digits = query.all { it in '0'..'9' || it in " +-()" } && query.count { it in '0'..'9' } >= MIN_NUMBER_DIGITS
    return all.mapNotNull { contact ->
        val byName = highlightRanges(contact.name, query).isNotEmpty()
        val number = contact.phoneNumbers.firstOrNull { digits && highlightRanges(it.value, query).isNotEmpty() }
            ?: contact.phoneNumbers.firstOrNull { it.isPrimary }
            ?: contact.phoneNumbers.firstOrNull()
        val byNumber = digits && contact.phoneNumbers.any { highlightRanges(it.value, query).isNotEmpty() }
        if (number == null || !(byName || byNumber)) {
            null
        } else {
            ContactHit(contact, number.value, typeLabel(number.type, number.label))
        }
    }
}

/** Every contact with a phone number, loaded once when the permission is held; empty without it. */
@Composable
internal fun rememberSearchContacts(): State<List<SimpleContact>> {
    val context = LocalContext.current
    return produceState(emptyList<SimpleContact>(), context) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            value = withContext(Dispatchers.IO) { runCatching { loadPhoneContacts(context) }.getOrDefault(emptyList()) }
        }
    }
}

/** The label of a phone number's type, "Mobile", "Home", or the contact's own label. */
internal fun phoneTypeLabel(context: Context, type: Int, label: String): String =
    Phone.getTypeLabel(context.resources, type, label).toString()

/** The card shape of row [index] of [count]: large corners outside, small ones where rows meet. */
private fun cardShape(index: Int, count: Int): RoundedCornerShape = RoundedCornerShape(
    topStart = if (index == 0) CardOuterRadius else CardInnerRadius,
    topEnd = if (index == 0) CardOuterRadius else CardInnerRadius,
    bottomStart = if (index == count - 1) CardOuterRadius else CardInnerRadius,
    bottomEnd = if (index == count - 1) CardOuterRadius else CardInnerRadius,
)

/** The look of row [index] of [count] in a section card; the gap above a row shows the page behind. */
@Composable
internal fun Modifier.sectionCardRow(index: Int, count: Int): Modifier = this
    .padding(horizontal = 16.dp)
    .padding(top = if (index == 0) 0.dp else RowGap)
    .clip(cardShape(index, count))
    .background(MaterialTheme.colorScheme.surfaceContainer)

/** A section's heading: the title at the start and, when rows are hidden, "See all" in the primary colour. */
@Composable
internal fun SectionHeading(title: String, showSeeAll: Boolean, onSeeAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 28.dp, end = 12.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp).semantics { heading() },
        )
        if (showSeeAll) TextButton(onClick = onSeeAll) { Text(stringResource(R.string.search_section_see_all)) }
    }
}

/** The "Conversations" section: heading, then one card with a row per conversation. */
internal fun LazyListScope.conversationSection(
    page: ResultPage<ConversationHitRow>,
    query: String,
    labels: Map<Long, List<RowLabel>>,
    onSeeAll: () -> Unit,
    onOpen: (SearchResult) -> Unit,
    heading: String,
) {
    if (page.items.isEmpty()) return
    item(key = "h-conversations") { SectionHeading(heading, page.hidden > 0, onSeeAll) }
    items(page.items.size, key = { "r-" + page.items[it].threadId }) { index ->
        val hit = page.items[index]
        ConversationResultRow(
            hit, query, labels[hit.threadId].orEmpty(), onOpen,
            modifier = Modifier.sectionCardRow(index, page.items.size),
        )
    }
}

/** The "Contacts" section: heading, then one card with a row per contact. */
internal fun LazyListScope.contactSection(
    page: ResultPage<ContactHit>,
    query: String,
    onSeeAll: () -> Unit,
    onOpen: (ContactHit) -> Unit,
    heading: String,
) {
    if (page.items.isEmpty()) return
    item(key = "h-contacts") { SectionHeading(heading, page.hidden > 0, onSeeAll) }
    items(page.items.size, key = { "k-" + page.items[it].contact.contactId }) { index ->
        ContactResultRow(page.items[index], query, onOpen, Modifier.sectionCardRow(index, page.items.size))
    }
}

/** A contact: avatar, the name with the match marked, the number, and the type of the number at the far end. */
@Composable
private fun ContactResultRow(
    hit: ContactHit,
    query: String,
    onOpen: (ContactHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpen(hit) }
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ContactAvatar(title = hit.contact.name, photoUri = hit.contact.photoUri)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                highlightedText(hit.contact.name, query, TitleMatchStyle),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                highlightedText(hit.number, query, TitleMatchStyle),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (hit.typeLabel.isNotEmpty()) {
            Text(
                hit.typeLabel,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
