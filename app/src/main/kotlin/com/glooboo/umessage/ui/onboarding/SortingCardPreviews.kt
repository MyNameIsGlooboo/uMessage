package com.glooboo.umessage.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.ui.theme.UMessageTheme

@Preview(name = "Sorting card - Strict", showBackground = true, widthDp = 360)
@Composable
private fun SortingCardStrictPreview() = UMessageTheme {
    SortingCard(Modifier.padding(16.dp), mode = UnknownSenderMode.STRICT, reduceMotion = true)
}

@Preview(name = "Sorting card - Off", showBackground = true, widthDp = 360)
@Composable
private fun SortingCardOffPreview() = UMessageTheme {
    SortingCard(Modifier.padding(16.dp), mode = UnknownSenderMode.OFF, reduceMotion = true)
}

/** What the "i" dialog holds in the dark theme: the text, then the card on a lower surface than the dialog. */
@Preview(name = "Sorting card - dialog, dark", showBackground = true, widthDp = 360)
@Composable
private fun SortingCardDialogDarkPreview() = UMessageTheme(darkTheme = true) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(
            Modifier.width(280.dp).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.spam_settings_unknown_body), style = MaterialTheme.typography.bodyMedium)
            SortingCard(reduceMotion = true, containerColor = MaterialTheme.colorScheme.surface)
        }
    }
}
