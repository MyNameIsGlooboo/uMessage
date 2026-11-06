package com.glooboo.umessage.ui.thread

/**
 * I07: a bank or brand people are impersonated as. [names] are the spellings a fake address copies (lower case, no
 * dashes); [official] are the real registrable domains, which are never reported. Persian names carry no digits or
 * Latin look-alikes. A plain list on purpose: adding a bank is one line.
 */
internal class LinkBrand(
    val label: String,
    val names: List<String>,
    val official: Set<String> = emptySet(),
    /** The brand uses its name under many domain endings (google.de), so its bare name alone is not a copy. */
    val global: Boolean = false,
)

internal val linkBrands: List<LinkBrand> = listOf(
    // Local banks and payment services.
    LinkBrand("Bank Mellat", listOf("mellat", "bankmellat", "بانکملت"), setOf("bankmellat.ir")),
    LinkBrand("Bank Melli", listOf("bankmelli", "بانکملی"), setOf("bmi.ir")),
    LinkBrand("Bank Saderat", listOf("banksaderat", "saderat", "بانکصادرات"), setOf("bsi.ir", "bank-saderat.ir")),
    LinkBrand("Bank Sepah", listOf("banksepah", "بانکسپه"), setOf("banksepah.ir")),
    LinkBrand("Bank Tejarat", listOf("tejaratbank", "banktejarat", "بانکتجارت"), setOf("tejaratbank.ir")),
    LinkBrand("Bank Parsian", listOf("parsianbank", "bankparsian", "بانکپارسیان"), setOf("parsian-bank.ir")),
    LinkBrand("Bank Pasargad", listOf("bankpasargad", "pasargad", "بانکپاسارگاد"), setOf("bpi.ir")),
    LinkBrand("Bank Saman", listOf("banksaman", "بانکسامان"), setOf("sb24.ir", "sb24.com")),
    LinkBrand("Bank Refah", listOf("refahbank", "bankrefah", "بانکرفاه"), setOf("rb24.ir", "refah-bank.ir")),
    LinkBrand("Bank Maskan", listOf("bankmaskan", "بانکمسکن"), setOf("bank-maskan.ir")),
    LinkBrand("Bank Keshavarzi", listOf("bankkeshavarzi", "بانککشاورزی"), setOf("bki.ir")),
    LinkBrand("Bank Shahr", listOf("bankshahr", "بانکشهر"), setOf("shahr-bank.ir")),
    LinkBrand("Bank Ayandeh", listOf("bankayandeh", "بانکآینده"), setOf("ba24.ir")),
    LinkBrand("Shaparak", listOf("shaparak", "شاپرک"), setOf("shaparak.ir")),
    LinkBrand("Zarinpal", listOf("zarinpal", "زرینپال"), setOf("zarinpal.com"), global = true),
    LinkBrand("Digikala", listOf("digikala", "دیجیکالا"), setOf("digikala.com"), global = true),
    LinkBrand("Snapp", listOf("snapp", "اسنپ"), setOf("snapp.ir", "snapp.taxi"), global = true),
    LinkBrand("Irancell", listOf("irancell", "ایرانسل"), setOf("irancell.ir"), global = true),
    // Brands that are imitated worldwide.
    LinkBrand("PayPal", listOf("paypal"), setOf("paypal.com", "paypal.me"), global = true),
    LinkBrand("Google", listOf("google"), setOf("google.com"), global = true),
    LinkBrand("Apple", listOf("apple", "icloud"), setOf("apple.com", "icloud.com"), global = true),
    LinkBrand("Amazon", listOf("amazon"), setOf("amazon.com"), global = true),
    LinkBrand("Microsoft", listOf("microsoft"), setOf("microsoft.com"), global = true),
    LinkBrand("Netflix", listOf("netflix"), setOf("netflix.com"), global = true),
    LinkBrand("Instagram", listOf("instagram"), setOf("instagram.com"), global = true),
    LinkBrand("Facebook", listOf("facebook"), setOf("facebook.com"), global = true),
    LinkBrand("WhatsApp", listOf("whatsapp"), setOf("whatsapp.com", "wa.me"), global = true),
    LinkBrand("Telegram", listOf("telegram"), setOf("telegram.org", "t.me"), global = true),
    LinkBrand("FedEx", listOf("fedex"), setOf("fedex.com"), global = true),
    LinkBrand("DHL", listOf("dhl"), setOf("dhl.com"), global = true),
)

/** Link shorteners: the address hides where the link goes. */
internal val linkShorteners: Set<String> = setOf(
    "bit.ly", "bitly.com", "tinyurl.com", "t.co", "goo.gl", "is.gd", "v.gd", "ow.ly", "buff.ly", "cutt.ly",
    "rb.gy", "rebrand.ly", "shorturl.at", "tiny.cc", "t.ly", "s.id", "lnkd.in", "bl.ink", "shrtco.de", "short.io",
    "clck.ru", "u.to", "soo.gd",
)
