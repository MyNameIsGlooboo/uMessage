package com.glooboo.umessage.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.SettingsSwitchRow

/**
 * J16: under the filter choice on the Welcome page, the quiet Spam notification switch, a short line that the sorting
 * is not always right, and the weekly summary switch. They are the same settings Settings shows: `notifyOnBlock` of
 * the spam settings and the weekly summary of [com.glooboo.umessage.helpers.CleanupPrefs]. Each row is one switch for
 * TalkBack, named by its text; the texts wrap at large font sizes.
 */
@Composable
internal fun WelcomeSpamNotices(
    notifyOn: Boolean,
    onNotify: (Boolean) -> Unit,
    digestOn: Boolean,
    onDigest: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSwitchRow(
            title = stringResource(R.string.onb_spam_notify_quiet),
            checked = notifyOn,
            onCheckedChange = onNotify,
        )
        Text(
            stringResource(R.string.onb_spam_check_weekly),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = true,
            maxLines = Int.MAX_VALUE,
            modifier = Modifier.fillMaxWidth(),
        )
        SettingsSwitchRow(
            title = stringResource(R.string.digest_row_title),
            summary = stringResource(R.string.digest_row_summary),
            checked = digestOn,
            onCheckedChange = onDigest,
        )
    }
}
