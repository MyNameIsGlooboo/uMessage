package com.glooboo.umessage.ui.thread

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R

/**
 * Link taps in a thread (TH-05). A tap never opens the link straight away: [onTap] shows the link sheet with the
 * domain and the whole address, so a phishing text's link text cannot hide where it really goes.
 */
internal fun linkSheetUriHandler(onTap: (String) -> Unit): UriHandler = object : UriHandler {
    override fun openUri(uri: String) = onTap(uri)
}

/** "Share" in the link sheet: the address as plain text, through the system share sheet. */
internal fun shareLink(context: Context, uri: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, linkCopyText(uri))
    try {
        context.startActivity(Intent.createChooser(send, null))
    } catch (_: Exception) {
        Toast.makeText(context, R.string.thread_no_app, Toast.LENGTH_SHORT).show()
    }
}

private const val MAILTO = "mailto:"
private val schemeHost = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#:@\\s]+)")

/** The domain of a web link without "www." ("bit.ly"), the address of a mailto link, else null. */
internal fun linkHost(uri: String): String? {
    if (uri.startsWith(MAILTO, ignoreCase = true)) {
        return uri.substring(MAILTO.length).substringBefore('?').ifBlank { null }
    }
    val host = runCatching { java.net.URI(uri).host }.getOrNull() ?: schemeHost.find(uri)?.groupValues?.get(1)
    return host?.removePrefix("www.")?.ifBlank { null }
}

/** What "Copy link" copies: the address itself, without "mailto:" for an e-mail address. */
internal fun linkCopyText(uri: String): String =
    if (uri.startsWith(MAILTO, ignoreCase = true)) uri.substring(MAILTO.length) else uri

/**
 * TH-05: the domain in bold, the whole address (selectable), then Copy link, Share and Open. A sender who is not a
 * saved contact ([trusted] false) also gets the line asking to check the address first.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun LinkSheet(
    uri: String,
    trusted: Boolean,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    /** I07: a risky link cannot be opened from here (the setting); the sheet says why and still copies and shares. */
    lockRisky: Boolean = false,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val report = remember(uri) { LinkSafety.check(uri) }
    val locked = lockRisky && report.isRisky
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val ltr = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr)
            linkHost(uri)?.let { host ->
                Text(host, style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Ltr))
            }
            if (!trusted) {
                Text(
                    stringResource(R.string.thread_link_confirm_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinkWarnings(report, locked)
            SelectionContainer {
                Text(uri, style = ltr, fontWeight = FontWeight.Medium)
            }
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
            ) {
                OutlinedButton(onClick = onCopy) { Text(stringResource(R.string.thread_r2_link_copy)) }
                OutlinedButton(onClick = onShare) { Text(stringResource(R.string.thread_r2_link_share)) }
                if (!locked) Button(onClick = onOpen) { Text(stringResource(R.string.thread_link_confirm_open)) }
            }
        }
    }
}
