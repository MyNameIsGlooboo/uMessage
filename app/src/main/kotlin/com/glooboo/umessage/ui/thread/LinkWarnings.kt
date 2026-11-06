package com.glooboo.umessage.ui.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.icons.Symbols

/** I07: one line per thing [report] found about a link, in the error colour, and why it cannot open if [locked]. */
@Composable
internal fun LinkWarnings(report: LinkReport, locked: Boolean) {
    if (report.isClean) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        report.flags.forEach { flag ->
            WarningLine(
                if (flag == LinkFlag.LOOKALIKE) {
                    stringResource(flag.textRes(), report.lookalikeOf.orEmpty())
                } else {
                    stringResource(flag.textRes())
                },
            )
        }
        if (locked) WarningLine(stringResource(R.string.i07_locked))
    }
}

@Composable
private fun WarningLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(
            Symbols.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

internal fun LinkFlag.textRes(): Int = when (this) {
    LinkFlag.SHORTENER -> R.string.i07_shortener
    LinkFlag.PUNYCODE -> R.string.i07_punycode
    LinkFlag.MIXED_SCRIPT -> R.string.i07_mixed_script
    LinkFlag.RAW_IP -> R.string.i07_raw_ip
    LinkFlag.HTTP_ONLY -> R.string.i07_http_only
    LinkFlag.LOOKALIKE -> R.string.i07_lookalike
    LinkFlag.USER_INFO -> R.string.i07_user_info
}
