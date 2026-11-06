package com.glooboo.umessage.ui.settings

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.ChoiceDialog
import com.glooboo.umessage.ui.icons.Symbols

/** The name shown for [language]; callers can override it (the first screen says "Phone language"). */
@Composable
fun defaultLanguageLabel(language: AppLanguage): String = stringResource(language.label)

/** Single-choice list over [languages]. Selecting one reports it and the caller closes the dialog. */
@Composable
fun LanguageChoiceDialog(
    languages: List<AppLanguage>,
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
    labelOf: @Composable (AppLanguage) -> String = { defaultLanguageLabel(it) },
) {
    ChoiceDialog(
        title = stringResource(org.fossify.commons.R.string.language),
        options = languages.map { labelOf(it) },
        selectedIndex = languages.indexOf(selected),
        onSelect = { onSelect(languages[it]) },
        onDismiss = onDismiss,
    )
}

/** A compact button showing the current language; it opens [LanguageChoiceDialog]. */
@Composable
fun LanguagePicker(
    languages: List<AppLanguage>,
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
    labelOf: @Composable (AppLanguage) -> String = { defaultLanguageLabel(it) },
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = labelOf(selected)
    val description = stringResource(R.string.lang_picker_cd, label)
    OutlinedButton(
        onClick = { open = true },
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics { contentDescription = description },
    ) {
        Icon(Symbols.Language, null, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
    }
    if (open) {
        LanguageChoiceDialog(
            languages = languages,
            selected = selected,
            onSelect = {
                open = false
                onSelect(it)
            },
            onDismiss = { open = false },
            labelOf = labelOf,
        )
    }
}
