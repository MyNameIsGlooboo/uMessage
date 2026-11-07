package com.glooboo.umessage.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.IconChip
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.settings.AppLanguage
import com.glooboo.umessage.ui.settings.LanguagePicker
import java.util.Locale

/** [locale]'s language written in that language ("English", "Deutsch", "فارسی"), or "" when it has no name. */
internal fun nativeLanguageName(locale: Locale): String =
    locale.getDisplayLanguage(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else "$it" }

/** The top row of the first page: Restore, then the language chip, together at the end of the row. */
@Composable
internal fun WelcomeTopRow(
    language: AppLanguage,
    onLanguage: ((AppLanguage) -> Unit)?,
    onRestore: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onRestore != null) {
            IconChip(Symbols.Upload, stringResource(R.string.onb_restore_chip), onRestore)
        }
        if (onLanguage != null) {
            val phoneLabel = stringResource(R.string.onb_lang_phone)
            val native = nativeLanguageName(LocalConfiguration.current.locales[0]).ifEmpty { phoneLabel }
            LanguagePicker(
                languages = AppLanguage.choices,
                selected = language,
                onSelect = onLanguage,
                buttonLabel = native,
                labelOf = { if (it == AppLanguage.SYSTEM) phoneLabel else stringResource(it.label) },
            )
        }
    }
}

/**
 * The uMessage app icon as a rounded square: the launcher's white shield-bubble on its coral background. The launcher
 * foreground is drawn for a mask that shows the middle two thirds of its canvas, so it is scaled up to fill the square.
 */
@Composable
internal fun AppIconMark(modifier: Modifier = Modifier, iconSize: Dp = 76.dp) {
    Box(modifier = modifier.size(iconSize).clip(RoundedCornerShape(iconSize * 0.24f))) {
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, Modifier.fillMaxSize())
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = ICON_FOREGROUND_ZOOM, scaleY = ICON_FOREGROUND_ZOOM),
        )
    }
}

/** The adaptive-icon canvas is 108dp and its mask shows 72dp of it. */
private const val ICON_FOREGROUND_ZOOM = 108f / 72f
