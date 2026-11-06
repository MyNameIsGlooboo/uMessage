package com.glooboo.umessage.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.theme.UMessageTheme

/**
 * Shown instead of the conversation list when the user declined the default-SMS dialog: says what is lost and offers
 * to ask again, like the onboarding default-app page, instead of closing the app.
 */
@Composable
fun DefaultSmsRequiredScreen(onSetDefault: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    RequiredStepScreen(
        title = stringResource(R.string.onb_default_title),
        body = stringResource(R.string.ui_default_required_body),
        action = stringResource(R.string.onb_default_action),
        onAction = onSetDefault,
        onClose = onClose,
        modifier = modifier,
    )
}

/**
 * MC-04: shown instead of the conversation list when SMS permission was denied, so the app no longer just closes.
 * Grant asks again; once Android stops asking ("Don't ask again") only the app's settings page can grant it.
 */
@Composable
fun SmsPermissionRequiredScreen(
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RequiredStepScreen(
        title = stringResource(R.string.main_sms_permission_title),
        body = stringResource(R.string.main_sms_permission_body),
        action = stringResource(R.string.main_permission_grant),
        onAction = onGrant,
        secondary = stringResource(R.string.main_sms_permission_settings),
        onSecondary = onOpenSettings,
        onClose = onClose,
        modifier = modifier,
    )
}

@Composable
private fun RequiredStepScreen(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_onboarding_shield),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(96.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text(action) }
            if (secondary != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) { Text(secondary) }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.ui_default_required_close))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DefaultSmsRequiredPreview() {
    UMessageTheme { DefaultSmsRequiredScreen(onSetDefault = {}, onClose = {}) }
}
