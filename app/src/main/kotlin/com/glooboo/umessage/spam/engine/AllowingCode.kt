package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.helpers.CodeKind
import com.glooboo.umessage.helpers.OtpCodeExtractor
import com.glooboo.umessage.helpers.OtpCodes
import com.glooboo.umessage.spam.TextNormalizer

/**
 * The stricter reading of "this text holds a verification code" that allows its sender for good (check 10 of the
 * filter order, D3 A: the sender joins the group "Senders of a verification code"). Pure JVM.
 *
 * [OtpDetector.looksLikeOtp] stays the one rule for showing a code (notification, copy chips, Codes filter,
 * forwarding). It is generous on purpose: a copy chip on a tracking number costs nothing. Here a false hit lets a
 * sender into Messages for good, so a text allows only when [OtpDetector.looksLikeOtp] says yes, and for one of the
 * codes it found ([OtpCodes.tokens]), right next to that code:
 * - no word of a reference number stands a few plain words before or after it ([vetoWords]: tracking, order number,
 *   referral, branch, queue, stock, zip, error, booking reference, checkout, customer number; "کد پیگیری",
 *   "کد سفارش", "کد معرف", "الخصم", ...). A veto word elsewhere in the text ("Dear Customer", "for your order") does
 *   not count;
 * - and a strong code phrase ([strongPhrases]: "کد تایید", "رمز یکبار مصرف", "verification code", "OTP",
 *   "one-time", "رمز التحقق", the CJK and Thai words of [OtpKeywords.cjk], ...) stands next to it, with at most one
 *   plain word before a generic "code" in between ("PIN reset code is 7314" yes; "Sign in today and use code 4455"
 *   no, that "code" belongs to "use code");
 * - or a possessive form says it is the reader's code ("Your code is N", "N is your Acme code", "Use N to verify",
 *   "کد شما: N", "کدتان N"), with no link near the code;
 * - or the text says to keep the code secret ("do not share", "در اختیار دیگران قرار ندهید"), or its last line
 *   binds the code to a site ([OtpDetector.isOriginBound], `@example.com #123456`).
 * A text with a word of an advertisement anywhere ("discount", "تخفیف", "20%": [OtpDetector.hasAdvertisingWord]) never
 * allows, even when it shows a code next to a one-time code phrase. So every text this allows is also shown as a code,
 * never the other way round.
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
        "کد تایید", "کد تأیید", "کد تائید", "کد تاییدیه", "کد اعتبارسنجی", "کد اعتبار سنجی", "رمز یکبار مصرف",
        "رمز یک بار مصرف", "یکبار مصرف", "یک بار مصرف", "رمز پویا", "رمز دوم", "رمز اینترنتی", "رمز عبور",
        "رمز ورود", "رمز شخصی", "رمز موقت", "کد ورود", "کد فعالسازی", "کد فعال سازی", "کد امنیتی", "کد احراز هویت",
        "احراز هویت", "گذرواژه", "پسورد", "فرم ورود", "کلمه عبور",
        // Arabic (folded like the text: Persian yeh and kaf, plain alef)
        "رمز التحقق", "رمز الدخول", "رمز التفعیل", "رمز التاکید", "کود التفعیل", "کود التحقق", "کلمه المرور",
        "لمره واحده", "التحقق", "التفعیل", "لتاکید", "تاکید", "الرقم السری", "رقمک السری",
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
     * Words of a reference number. They veto only a few plain words before or after the code itself, so "Dear
     * Customer, your OTP is 482915" still allows. English words that are everyday words alone ("customer", "order",
     * "free") count only in their reference compounds.
     */
    internal val vetoWords: List<String> = listOf(
        // English
        "tracking", "track", "customer number", "customer no", "customer id", "order number", "order no",
        "order id", "order code", "ref no", "ref number", "reference no", "reference number", "booking ref",
        "booking reference", "referral", "branch", "queue", "stock", "zip", "postal code", "postcode", "error",
        "checkout", "toll[- ]?free", "changed",
        // Persian
        "پیگیری", "رهگیری", "مرسوله", "کد سفارش", "شماره سفارش", "کد معرف", "کد معرفی", "کد شعبه", "کد نوبت",
        "شماره نوبت", "کد بورسی", "کد پستی", "کد مشتری", "شماره مشتری", "کد اشتراک", "کد ملی", "کد رزرو", "کد خطا",
        "شناسه پرداخت", "تغییر کرد", "تغییر یافت",
        // Arabic
        "الخصم", "التتبع", "تتبع", "الشحنه", "شحنه", "الطلب", "طلبک", "مرجع", "المرجع", "الحجز", "رقم العملیه",
    )

    private const val BARE_THAI_CODE = "รหัส"
    private const val SEP = "[^\\p{L}\\p{N}]{1,4}"
    private const val WORD = "[\\p{L}\\p{M}]{1,15}"

    /** The characters either side of a code that are searched. */
    private const val WINDOW = 120

    /** How far from the code a link stops a possessive form from allowing. */
    private const val LINK_WINDOW = 60

    private fun words(list: List<String>) = "(?<![\\p{L}\\p{N}])(?:" +
        list.map { TextNormalizer.fold(it).replace(" ", "\\s*") }.sortedByDescending { it.length }.joinToString("|") +
        ")(?![\\p{L}\\p{N}])"

    private val phrase = OtpDetector.keywordPattern(strongPhrases)
    private val weak = OtpDetector.keywordPattern(OtpKeywords.weak)
    private val plainWord = "(?!$weak)$WORD"

    /** A phrase, then up to four plain words, or a generic "code" after at most one word ("PIN reset code is"). */
    private val strongBefore = Regex(
        "$phrase$SEP(?:(?:$WORD$SEP)?$weak$SEP(?:$plainWord$SEP){0,2}|(?:$plainWord$SEP){0,4})$",
        RegexOption.IGNORE_CASE,
    )
    private val strongAfter = Regex("^$SEP(?:$plainWord$SEP){0,4}$phrase", RegexOption.IGNORE_CASE)

    /** The CJK and Thai code words, without the bare Thai "รหัส" ("code" alone, R-2). */
    private val cjkWords = "(?:" + OtpKeywords.cjk.filter { it != BARE_THAI_CODE }
        .map { TextNormalizer.fold(it).replace(" ", "\\s?") }
        .sortedByDescending { it.length }.joinToString("|") + ")"
    private val cjkBefore = Regex("$cjkWords[^\\p{N}]{0,${OtpKeywords.CJK_GAP}}$")
    private val cjkAfter = Regex("^[^\\p{N}]{0,${OtpKeywords.CJK_GAP}}$cjkWords")

    private val veto = words(vetoWords)
    private val vetoBefore = Regex("$veto$SEP(?:$WORD$SEP){0,4}$", RegexOption.IGNORE_CASE)
    private val vetoAfter = Regex("^$SEP(?:$WORD$SEP){0,4}$veto", RegexOption.IGNORE_CASE)

    /** Reference words of languages without spaces: parcel, order, express delivery, coupon, discount (R-2). */
    internal val cjkVetoWords = listOf("พัสดุ", "สั่งซื้อ", "订单", "訂單", "快递", "快遞", "注文", "주문", "쿠폰", "优惠", "優惠")
    private val cjkVeto = "(?:" + cjkVetoWords.joinToString("|") + ")"
    private val cjkVetoBefore = Regex("$cjkVeto[^\\p{N}]{0,${OtpKeywords.CJK_GAP}}$")
    private val cjkVetoAfter = Regex("^[^\\p{N}]{0,${OtpKeywords.CJK_GAP}}$cjkVeto")

    /** Marks that end a clause in Chinese, Japanese and Korean: a reference word behind one names something else. */
    private const val CLAUSE_MARKS = "，,。；;！!？?、"

    /**
     * Fable F-1: a CJK code word closer to the code than the reference word, with a clause mark between them, wins over
     * it: "您正在支付订单，验证码 123456" (you are paying an order; the verification code is 123456) allows, while
     * "您的订单验证码是 573920" (the order's verification code) and "【示例快递】您的取件验证码" do not.
     */
    private val notVeto = "(?:(?!$cjkVeto)[^\\p{N}])"
    private val cjkCodeAfterClause =
        Regex("[$CLAUSE_MARKS]$notVeto*?$cjkWords$notVeto{0,${OtpKeywords.CJK_GAP}}$")
    private val cjkClauseBeforeVeto = Regex("^$notVeto{0,${OtpKeywords.CJK_GAP}}[$CLAUSE_MARKS]")

    /** What the possessive code is when it is no one-time code: "Your booking code is N", "Your reference no: N". */
    private const val NOT_A_CODE = "(?:booking|reservation|reference|ref|invoice|parcel|shipment|customer|" +
        "member(?:ship)?|ticket|account|discount|promo|coupon|gift|voucher)"
    private const val NUMBER_WORD = "(?:code|number|no|id)"
    private val possessiveVetoBefore = Regex(
        "(?<![\\p{L}\\p{N}])$NOT_A_CODE$SEP$NUMBER_WORD(?:${SEP}is)?$SEP$",
        RegexOption.IGNORE_CASE,
    )
    private val possessiveVetoAfter = Regex(
        "^${SEP}is${SEP}your$SEP(?:$WORD$SEP){0,3}?$NOT_A_CODE$SEP$NUMBER_WORD(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )

    /** "Your code is N", "Your Acme code: N", "کد شما: N", "کدتان N". */
    private val possessiveBefore = Regex(
        "(?<![\\p{L}\\p{N}])(?:your$SEP(?:$WORD$SEP){0,3}?$weak(?:${SEP}is)?|کد${SEP}شما|کدتان)$SEP$",
        RegexOption.IGNORE_CASE,
    )

    /** "N is your Acme code". */
    private val possessiveAfter = Regex("^${SEP}is${SEP}your$SEP(?:$WORD$SEP){0,3}?$weak", RegexOption.IGNORE_CASE)

    /** The purpose after "Use N": "to verify", "to confirm", "to log in". */
    private val useAfter = Regex(
        "^${SEP}to$SEP(?:verify|confirm|log\\s?in|sign\\s?in)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )
    private val useBefore = Regex("(?<![\\p{L}\\p{N}])use$SEP$", RegexOption.IGNORE_CASE)

    private val secrecy = Regex(words(secrecyPhrases), RegexOption.IGNORE_CASE)

    /** Whether [body] allows its sender for good (see the class comment). Never throws. */
    fun isAllowingCode(body: String): Boolean = runCatching {
        if (!OtpDetector.looksLikeOtp(body)) return@runCatching false
        val text = TextNormalizer.fold(body)
        if (OtpDetector.hasAdvertisingWord(text)) return@runCatching false
        val anywhere = secrecy.containsMatchIn(text) || OtpDetector.isOriginBound(body)
        codeRanges(body, text).any { range -> !vetoNear(text, range) && (anywhere || allowsNear(text, range)) }
    }.getOrDefault(false)

    /**
     * Where the codes [OtpDetector.looksLikeOtp] found stand in the folded [text]. [OtpCodes.tokens] gives no value
     * for a CJK or Thai keyword ("验证码是 482915"), so then every code-shaped number of the text is a candidate; each
     * still needs its own phrase next to it.
     */
    private fun codeRanges(body: String, text: String): List<IntRange> {
        val found = OtpCodes.tokens(body)
            .filter { it.kind != CodeKind.Username }
            .flatMap { OtpCodes.spans(text, it.value) }
        if (found.isNotEmpty()) return found.distinct()
        return OtpCodeExtractor.codeShapes(text).mapNotNull { it.groups[1]?.range }.toList()
    }

    private fun before(text: String, range: IntRange) = text.substring(maxOf(0, range.first - WINDOW), range.first)

    private fun after(text: String, range: IntRange) =
        text.substring(range.last + 1, minOf(text.length, range.last + 1 + WINDOW))

    private fun vetoNear(text: String, range: IntRange): Boolean {
        val head = before(text, range)
        val tail = after(text, range)
        val cjkCodeFirst = cjkBefore.containsMatchIn(head) && cjkClauseBeforeVeto.containsMatchIn(tail)
        return vetoBefore.containsMatchIn(head) || vetoAfter.containsMatchIn(tail) ||
            cjkVetoBefore.containsMatchIn(head) && !cjkCodeAfterClause.containsMatchIn(head) ||
            cjkVetoAfter.containsMatchIn(tail) && !cjkCodeFirst
    }

    private fun allowsNear(text: String, range: IntRange): Boolean {
        val head = before(text, range)
        val tail = after(text, range)
        if (strongBefore.containsMatchIn(head) || strongAfter.containsMatchIn(tail)) return true
        if (cjkBefore.containsMatchIn(head) || cjkAfter.containsMatchIn(tail)) return true
        val possessive = when {
            useBefore.containsMatchIn(head) -> useAfter.containsMatchIn(tail)
            else -> possessiveBefore.containsMatchIn(head) || possessiveAfter.containsMatchIn(tail)
        }
        val notACode = possessiveVetoBefore.containsMatchIn(head) || possessiveVetoAfter.containsMatchIn(tail)
        return possessive && !notACode && !linkNear(text, range)
    }

    private fun linkNear(text: String, range: IntRange): Boolean {
        val from = maxOf(0, range.first - LINK_WINDOW)
        val to = minOf(text.length, range.last + 1 + LINK_WINDOW)
        return OtpDetector.containsUrl(text.substring(from, to))
    }
}
