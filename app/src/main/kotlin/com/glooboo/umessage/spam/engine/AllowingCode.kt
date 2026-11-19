package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.spam.TextNormalizer

/**
 * The stricter reading of "this text holds a verification code" that allows its sender for good (check 10 of the
 * filter order, D3 A: the sender joins the group "Senders of a verification code"). Pure JVM.
 *
 * [OtpDetector.looksLikeOtp] stays the one rule for showing a code (notification, copy chips, Codes filter,
 * forwarding). It is generous on purpose: a copy chip on a tracking number costs nothing. Here a false hit lets a
 * sender into Messages for good, so a text allows only when [OtpDetector.looksLikeOtp] says yes and also
 * - a strong code phrase ("کد تایید", "رمز یکبار مصرف", "رمز پویا", "verification code", "OTP", "one-time",
 *   "رمز التحقق", ...) stands a few plain words before or after a code-like token, or
 * - the text says to keep the code secret ("do not share", "در اختیار دیگران قرار ندهید"), or
 * - its last line binds the code to a site ([OtpDetector.isOriginBound], `@example.com #123456`),
 * and no word of a reference number is in it (tracking, order, referral, branch, queue, stock, zip, error, booking,
 * checkout, customer; "کد پیگیری", "کد سفارش", "کد معرف", "الخصم", ...). The plain words "code", "کد", "رمز" alone
 * never allow. So every text this allows is also shown as a code, never the other way round.
 */
object AllowingCode {

    /**
     * Phrases that say the number is a one-time code. The generic words ("code", "کد", "رمز", "verification",
     * "تایید", "الرمز") are left out: "Your verification is complete. Ref 556677" is no code.
     */
    internal val strongPhrases: List<String> = listOf(
        // English
        "otp", "one[- ]?time", "verification code", "verify code", "security code", "login code", "log[- ]?in code",
        "sign[- ]?in code", "log[- ]?in", "sign[- ]?in", "passcode", "password", "pin", "2fa",
        "authentication code", "auth code", "confirmation code", "access code", "activation code",
        // Persian
        "کد تایید", "کد تأیید", "کد تائید", "رمز یکبار مصرف", "رمز یک بار مصرف", "یکبار مصرف", "یک بار مصرف",
        "رمز پویا", "رمز دوم", "رمز اینترنتی", "رمز عبور", "رمز ورود", "رمز شخصی", "رمز موقت", "کد ورود",
        "کد فعالسازی", "کد فعال سازی", "کد امنیتی", "کد احراز هویت", "احراز هویت", "گذرواژه", "پسورد", "فرم ورود",
        // Arabic (folded like the text: Persian yeh and kaf)
        "رمز التحقق", "رمز الدخول", "رمز التفعیل", "رمز التأکید", "کود التفعیل", "کود التحقق", "کلمة المرور",
        "لمرة واحدة", "التحقق", "التفعیل", "لتأکید", "تأکید",
        // Other languages: the compound words of OtpKeywords.strong
        "код подтверждения", "код підтвердження", "проверочный код", "код безопасности", "одноразовый",
        "одноразовий", "пароль", "пин[- ]?код", "doğrulama kodu", "onay kodu", "güvenlik kodu", "şifre",
        "tek kullanımlık", "código de verificación", "código de seguridad", "codice di verifica", "contraseña",
        "senha", "un solo uso", "uso único", "monouso", "mot de passe", "code de sécurité", "code de confirmation",
        "à usage unique", "bestätigungscode", "verifizierungscode", "sicherheitscode", "anmeldecode", "passwort",
        "einmalpasswort", "verificatiecode", "bevestigingscode", "wachtwoord", "kod weryfikacyjny", "hasło",
        "jednorazowy", "kode verifikasi", "kata sandi", "sekali pakai", "kod pengesahan", "mã xác nhận",
        "mã xác minh", "mật khẩu", "קוד אימות", "סיסמה", "ओटीपी", "पासवर्ड", "ওটিপি", "পাসওয়ার্ড", "پاس ورڈ",
    )

    /** Lines that only one-time codes carry: keep it secret. */
    internal val secrecyPhrases: List<String> = listOf(
        "do not share", "don't share", "dont share", "never share", "do not give", "never give",
        "در اختیار دیگران قرار ندهید", "در اختیار کسی قرار ندهید", "به کسی ندهید", "به کسی نگویید",
        "لا تشارک", "لا تعط",
    )

    /**
     * Words of a reference number (tracking, order, referral, branch, queue, stock, zip, error, booking, checkout,
     * customer) and of offers the code lists miss ("free", "الخصم"), and of a notice that something changed. Any of
     * them in the text and it allows nobody. Persian reference nouns that also name banks or places ("ملی", "شعبه")
     * only count after "کد" or "شماره".
     */
    internal val vetoWords: List<String> = listOf(
        // English
        "tracking", "track", "(?<!in\\s)orders?", "referral", "refer", "branch", "queue", "stock", "zip", "postal",
        "postcode", "error", "booking", "reservation", "checkout", "customer", "ref", "reference", "invoice",
        "parcel", "shipment", "free", "changed",
        // Persian
        "پیگیری", "رهگیری", "مرسوله", "کد سفارش", "شماره سفارش", "کد معرف", "کد معرفی", "کد شعبه", "کد نوبت",
        "شماره نوبت", "کد بورسی", "کد پستی", "کد مشتری", "شماره مشتری", "کد اشتراک", "کد ملی", "کد رزرو", "کد خطا",
        "شناسه پرداخت", "تغییر کرد", "تغییر یافت",
        // Arabic
        "الخصم", "التتبع", "تتبع", "الشحنة", "شحنة", "الطلب", "طلبک", "مرجع", "المرجع", "الحجز", "رقم العملیة",
    )

    /** A code-like token: 4-12 letters and digits with a digit, or two groups of three or four digits. */
    private const val TOKEN = "(?<![\\p{L}\\p{N}])(?=[0-9a-z]*[0-9])" +
        "(?:[0-9]{3,4}[ -][0-9]{3,4}|[0-9a-z]{4,12})(?![\\p{L}\\p{N}])"

    private val strongNearCode: Regex = run {
        val phrase = OtpDetector.keywordPattern(strongPhrases)
        Regex("$phrase${OtpDetector.NEAR}$TOKEN|$TOKEN${OtpDetector.NEAR}$phrase", RegexOption.IGNORE_CASE)
    }

    private val secrecy: Regex = wordsRegex(secrecyPhrases)

    private val veto: Regex = wordsRegex(vetoWords)

    private fun wordsRegex(words: List<String>) = Regex(
        "(?<![\\p{L}\\p{N}])(?:" +
            words.map { TextNormalizer.fold(it).replace(" ", "\\s*") }.sortedByDescending { it.length }
                .joinToString("|") +
            ")(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )

    /** Whether [body] allows its sender for good (see the class comment). Never throws. */
    fun isAllowingCode(body: String): Boolean = runCatching {
        if (!OtpDetector.looksLikeOtp(body)) return@runCatching false
        val text = TextNormalizer.fold(body)
        if (veto.containsMatchIn(text)) return@runCatching false
        strongNearCode.containsMatchIn(text) || secrecy.containsMatchIn(text) || OtpDetector.isOriginBound(body)
    }.getOrDefault(false)
}
