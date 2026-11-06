package com.glooboo.umessage.ui.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.BankCardPrefs
import com.glooboo.umessage.helpers.MessageCategories
import com.glooboo.umessage.helpers.MessageCategory
import com.glooboo.umessage.ui.components.localizeDigits
import com.glooboo.umessage.ui.components.uiLocale
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/**
 * The bank alert of a received text, or null: only a text that [MessageCategories] calls a transaction from a service
 * sender is read, and only when [BankAlertParser] is certain of every figure.
 */
internal fun bankAlertOf(address: String, body: String): BankAlert? {
    val category = MessageCategories.classify(address, body, isContact = false)
    return if (category == MessageCategory.Transactions) BankAlertParser.parse(body) else null
}

/** [amount] with grouping and at most two decimals, in the digits of [locale]. */
internal fun formatBankAmount(amount: BigDecimal, locale: Locale): String {
    val format = NumberFormat.getNumberInstance(locale)
    format.maximumFractionDigits = 2
    format.minimumFractionDigits = 0
    return localizeDigits(format.format(amount), locale)
}

private fun BankAlertKind.labelRes(): Int = when (this) {
    BankAlertKind.Deposit -> R.string.i31_card_deposit
    BankAlertKind.Withdrawal -> R.string.i31_card_withdrawal
    BankAlertKind.Balance -> R.string.i31_card_balance_title
}

private fun BankCurrency.labelRes(): Int = when (this) {
    BankCurrency.Rial -> R.string.i31_currency_rial
    BankCurrency.Toman -> R.string.i31_currency_toman
    BankCurrency.Usd -> R.string.i31_currency_usd
    BankCurrency.Eur -> R.string.i31_currency_eur
    BankCurrency.Gbp -> R.string.i31_currency_gbp
    BankCurrency.Aed -> R.string.i31_currency_aed
}

private fun BankHintKind.labelRes(): Int = when (this) {
    BankHintKind.Card -> R.string.i31_card_hint_card
    BankHintKind.Account -> R.string.i31_card_hint_account
    BankHintKind.Other -> R.string.i31_card_hint_other
}

/**
 * I31: a small card under a received bank text: deposit or withdrawal and its amount, the balance and the last card
 * digits, whichever the text shows. The text itself stays in the bubble. Nothing shows when the card is switched off or
 * the parser is not certain.
 */
@Composable
internal fun BankAlertCard(address: String, body: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val enabled = remember(context) { BankCardPrefs.get(context).cardsEnabled }
    val alert = remember(address, body, enabled) { if (enabled) bankAlertOf(address, body) else null } ?: return
    val locale = uiLocale()
    val colors = MaterialTheme.colorScheme
    val resources = LocalContext.current.resources
    val currency = alert.currency?.let { resources.getString(it.labelRes()) }
    val money = { value: BigDecimal ->
        val number = formatBankAmount(value, locale)
        if (currency == null) number else resources.getString(R.string.i31_money, number, currency)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
        modifier = modifier.padding(top = 4.dp, start = 4.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            alert.amount?.let { amount ->
                Text(
                    stringResource(R.string.i31_card_title, stringResource(alert.kind.labelRes()), money(amount)),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            alert.balance?.let { balance ->
                Text(
                    stringResource(R.string.i31_card_balance, money(balance)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            alert.hint?.let { hint ->
                Text(
                    stringResource(hint.kind.labelRes(), localizeDigits(hint.digits, locale)),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
