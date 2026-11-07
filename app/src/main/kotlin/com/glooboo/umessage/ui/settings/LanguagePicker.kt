package com.glooboo.umessage.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.ChoiceDialog
import com.glooboo.umessage.ui.components.IconChip
import com.glooboo.umessage.ui.icons.Symbols

/** The name shown for [language]; callers can override it (the first screen says "Phone language" for System). */
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

/** A compact chip showing the current language, or [buttonLabel] when given; it opens [LanguageChoiceDialog]. */
@Composable
fun LanguagePicker(
    languages: List<AppLanguage>,
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
    buttonLabel: String? = null,
    labelOf: @Composable (AppLanguage) -> String = { defaultLanguageLabel(it) },
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = buttonLabel ?: labelOf(selected)
    val description = stringResource(R.string.lang_picker_cd, label)
    IconChip(
        icon = Symbols.Language,
        label = label,
        onClick = { open = true },
        modifier = modifier.semantics { contentDescription = description },
    )
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
