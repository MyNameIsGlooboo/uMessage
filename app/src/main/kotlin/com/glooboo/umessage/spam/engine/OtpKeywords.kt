package com.glooboo.umessage.spam.engine

/**
 * The word lists of [OtpDetector], one list per kind of word, each with English, Persian and Arabic first and then the
 * other big languages (Russian, Ukrainian, Turkish, Spanish, Portuguese, Italian, French, German, Dutch, Polish,
 * Indonesian, Malay, Vietnamese, Hebrew, Hindi, Bengali, Urdu). Words are written as they appear in text and are read
 * through [com.glooboo.umessage.spam.TextNormalizer.fold] when the patterns are built. A word is matched as a whole
 * word, so "pin" never matches "shipping".
 */
internal object OtpKeywords {

    /** Words that say what the number is: any 4-8 digit number next to one is a code. */
    val strong = listOf(
        // English
        "otp", "passcode", "password", "pin", "verification", "verify", "one[- ]?time", "authenticat\\p{L}*",
        "security code", "login code", "2fa", "passcodes", "log[- ]?in", "sign[- ]?in", "verification code",
        // Persian
        "رمز", "رمز پویا", "رمز عبور", "رمز یکبار مصرف", "گذرواژه", "کد تایید", "کد تأیید", "کد فعالسازی",
        "کد ورود", "فرم ورود", "ورود به حساب", "تایید", "تأیید", "تائید", "یکبار مصرف", "یک بار مصرف", "پسورد",
        "کلمه عبور",
        // Arabic (written with Persian yeh and kaf, as the text is folded the same way)
        "الرمز", "رمز التحقق", "رمز التفعیل", "کلمة المرور", "التحقق", "التفعیل", "الرقم السری", "رقمک السری",
        // Russian and Ukrainian
        "пароль", "код подтверждения", "код підтвердження", "проверочный код", "код безопасности", "одноразовый",
        "одноразовий", "подтверждения", "підтвердження", "пин[- ]?код", "пин",
        // Turkish
        "doğrulama", "doğrulama kodu", "onay kodu", "güvenlik kodu", "şifre", "tek kullanımlık", "parola",
        // Spanish, Portuguese, Italian
        "verificación", "verificacion", "verificação", "verificacao", "verifica", "clave", "contraseña", "contrasena",
        "senha", "palavra-passe", "un solo uso", "uso único", "monouso", "codice di verifica", "código de seguridad",
        "código de verificación",
        // French
        "mot de passe", "vérification", "à usage unique", "usage unique", "authentification", "code de sécurité",
        "code de confirmation",
        // German and Dutch
        "bestätigungscode", "verifizierungscode", "sicherheitscode", "anmeldecode", "passwort", "einmalpasswort",
        "kennwort", "einmalkennwort", "verifizierung", "verificatiecode", "wachtwoord", "bevestigingscode",
        // Polish
        "kod weryfikacyjny", "weryfikacyjny", "hasło", "jednorazowy", "kod autoryzacyjny",
        // Indonesian, Malay, Vietnamese
        "kode verifikasi", "verifikasi", "kata sandi", "sandi", "sekali pakai", "kod pengesahan", "pengesahan",
        "xác minh", "xác thực", "mật khẩu", "mã xác nhận", "xác nhận", "một lần",
        // Hebrew
        "קוד אימות", "אימות", "סיסמה", "סיסמא", "חד פעמי",
        // Hindi, Bengali, Urdu
        "ओटीपी", "सत्यापन", "पासवर्ड", "वन टाइम", "ওটিপি", "যাচাইকরণ", "পাসওয়ার্ড", "پاس ورڈ", "تصدیقی", "تصدیق",
    )

    /**
     * Words for a password. Only these make a 9-10 digit number a code ("رمز شخصی شما 1234567890"); the other strong
     * words ("تایید", "verify") do not, as a long number next to them is usually a tracking or account number.
     */
    val password = listOf(
        "رمز", "رمز عبور", "رمز پویا", "رمز یکبار مصرف", "گذرواژه", "پسورد", "کلمه عبور", "password", "passcode",
    )

    /** Generic words ("code", "کد", "код"): a number next to one is a code unless it reads as a year. */
    val weak = listOf(
        "code", "codes", "کد", "کود", "کوڈ", "код", "кода", "kod", "kodu", "kodunuz", "kode", "código", "codigo",
        "codice", "mã", "קוד", "कोड", "কোড",
    )

    /**
     * Words of languages written without spaces between words (Chinese, Japanese, Korean, Thai). They are matched next
     * to a code with up to [CJK_GAP] other characters in between, as there are no word edges to count.
     */
    val cjk = listOf(
        "验证码", "驗證碼", "校验码", "校驗碼", "动态码", "動態碼", "动态密码", "動態密碼", "安全码", "確認碼", "确认码", "密码",
        "密碼", "口令", "随机码", "認証コード", "確認コード", "認証番号", "確認番号", "ワンタイムパスワード", "パスワード",
        "暗証番号", "セキュリティコード", "인증번호", "인증 번호", "인증코드", "확인코드", "비밀번호", "보안코드", "승인번호",
        "รหัสยืนยัน", "รหัสผ่าน", "รหัส",
    )

    const val CJK_GAP = 16

    /**
     * Words that make a text advertising whatever else it says: discounts, coupons, prizes, percentages. A text with
     * one of them is not a code, not even next to a strong keyword ("You are the winner, verify with 123456").
     */
    val marketingHard = listOf(
        // English
        "promo", "promotion", "promotional", "coupon", "voucher", "discount", "cashback", "prize", "lottery",
        "jackpot", "winner",
        // Persian (the opt-out line "لغو۱۱" is [optOut], not here: a real login code can end with it)
        "تخفیف", "جشنواره", "حراج", "جایزه", "برنده", "قرعه کشی", "قرعه\u200Cکشی", "رایگان", "پیشنهاد ویژه",
        "کوپن", "تبلیغ",
        // Arabic
        "خصم", "جائزة", "مجانا", "کوبون",
        // Other languages
        "скидка", "скидки", "акция", "промокод", "indirim", "kampanya", "rabatt", "réduction", "desconto", "descuento",
        "promoção", "promocao", "diskon", "sconto", "promocja", "korting", "khuyến mãi", "giảm giá", "छूट",
    )

    /**
     * The opt-out line that Persian promotional texts end with ("لغو۱۱", also "لغو 11"). It marks most advertisements,
     * but a few real codes carry it too (for example a login code from Taaghche), so unlike [marketingHard] it only
     * vetoes a code that has no strong keyword next to it: "کد 1234 را وارد کنید. لغو11" is an advertisement, while
     * "کد 9878 را در فرم ورود ... وارد کنید. لغو11" says what the number is.
     */
    val optOut = listOf("لغو ?11")

    /**
     * Phrases that only a one-time code carries. A code right next to one of them is a code even in a text with a word
     * of [marketingHard], as long as that word stands in another sentence ("Your verification code is 482913. Get 20%
     * off your next ride!"): an advertising footer does not hide the code. Never the generic words ("code", "کد",
     * "verify"), so "Winner! Verify with 123456" and "50% off, code SALE20" stay advertisements.
     */
    val codePhrases = listOf(
        // English
        "otp", "one[- ]?time password", "one[- ]?time passcode", "one[- ]?time code", "verification code",
        "security code", "login code", "log[- ]?in code", "sign[- ]?in code", "passcode", "authentication code",
        // Persian
        "کد تایید", "کد تأیید", "کد تائید", "رمز یکبار مصرف", "رمز یک بار مصرف", "رمز پویا", "کد ورود",
        "کد فعالسازی", "کد فعال سازی", "رمز عبور", "رمز ورود",
        // Arabic
        "رمز التحقق", "رمز التفعیل", "رمز الدخول", "کود التفعیل", "کود التحقق",
        // Other languages
        "код подтверждения", "проверочный код", "doğrulama kodu", "onay kodu", "código de verificación",
        "código de verificação", "codice di verifica", "code de vérification", "bestätigungscode",
        "verifizierungscode", "kod weryfikacyjny", "kode verifikasi", "mã xác nhận", "mã xác minh",
    )

    /** Marketing words of languages without spaces between words: matched anywhere in the text, without word edges. */
    val marketingCjk = listOf("优惠", "優惠", "折扣", "割引", "セール", "할인", "ส่วนลด")

    /**
     * Words that are often part of a real transactional text ("Apple Gift Card verification code", "Reward redemption
     * OTP"): they only count against a code written next to a generic word such as "code".
     */
    val marketingSoft = listOf(
        "offer", "sale", "reward", "rewards", "gift", "bonus", "هدیه", "هدیة", "oferta", "angebot", "geschenk",
        "cadeau",
        "regalo", "presente", "hadiah", "подарок", "hediye", "aanbieding",
    )

    /** Words that make a 4-digit year-like number a year: "valid until 2025", "سال ۱۴۰۴". */
    val yearContext = listOf(
        "until", "till", "valid", "validity", "expir\\p{L}*", "year", "since", "copyright", "©", "سال", "تا", "معتبر",
        "انقضا", "hasta", "bis", "до", "kadar", "até", "sampai",
    )

    /** Top-level domains a bare domain ("mybank-secure.com", "x.ir") is recognised by, without http or a slash. */
    val tlds = listOf(
        "com", "net", "org", "ir", "info", "biz", "io", "co", "app", "xyz", "site", "online", "top", "link", "click",
        "shop", "store", "live", "ly", "gl", "gd", "cc", "tk", "ml", "ga", "cf", "gq", "ru", "cn", "uk", "de", "eu",
        "tv", "ai", "dev", "pro", "club", "vip", "icu", "cyou", "sbs", "buzz", "rest", "fun", "space", "website",
        "tech",
        "ws", "su", "pw", "lol", "ae", "tr", "help", "support", "me", "us", "in", "to", "page", "digital", "world",
        "life", "cloud", "login", "secure", "bank", "money", "today", "email", "work", "pay", "finance", "services",
    )
}
