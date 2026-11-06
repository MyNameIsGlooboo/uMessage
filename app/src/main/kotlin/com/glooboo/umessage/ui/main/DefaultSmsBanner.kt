package com.glooboo.umessage.ui.main

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.icons.Symbols

/**
 * CM-05: a warning above the bottom bar while another app is the default SMS app. Without the role Android hides the
 * blocked-numbers list and delivers texts to the other app, so spam filtering quietly stops; Fix asks for the role.
 */
@Composable
fun NotDefaultSmsBanner(onFix: () -> Unit, modifier: Modifier = Modifier) {
    val fixDescription = stringResource(R.string.main_not_default_fix_description)
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Symbols.Shield, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                text = stringResource(R.string.main_not_default_banner),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(
                onClick = onFix,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = fixDescription },
            ) { Text(stringResource(R.string.main_not_default_fix)) }
        }
    }
}
