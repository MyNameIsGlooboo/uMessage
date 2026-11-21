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
 * - or a one-time code phrase ([OtpKeywords.codePhrases]) stands in the same sentence up to eight words away
 *   ([OtpDetector.WIDE_NEAR], owner decision 21 Nov), with no veto word between them and no link in the text
 *   ("کد تایید شما جهت انجام تراکنش خرید از فروشگاه نمونه: 482913"); never from a bare "verify", "تایید" or "رمز";
 * - or a possessive form says it is the reader's code ("Your code is N", "N is your Acme code", "Use N to verify",
 *   "کد شما: N", "کدتان N"), with no link near the code;
 * - or the text says to keep the code secret ("do not share", "در اختیار دیگران قرار ندهید") and the code has a
 *   keyword a few plain words away ([OtpDetector.keywordClose]: no phone number eight words from "verify"), or its
 *   last line binds the code to a site ([OtpDetector.isOriginBound], `@example.com #123456`).
 * A text with a word of an advertisement anywhere ("discount", "تخفیف", "20%": [OtpDetector.hasAdvertisingWord]) never
 * allows, even when it shows a code next to a one-time code phrase. So every text this allows is also shown as a code,
 * never the other way round.
 */
@Suppress("TooManyFunctions") // One small reading per rule of the class comment; each shares the patterns above it.
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
        "احراز هویت", "گذرواژه", "پسورد", "فرم ورود", "کلمه عبور", "کلمه ی عبور",
        // Arabic (folded like the text: Persian yeh and kaf, plain alef)
        "رمز التحقق", "رمز الدخول", "رمز التفعیل", "رمز التاکید", "کود التفعیل", "کود التحقق", "کلمه المرور",
        "لمره واحده", "التحقق", "التفعیل", "لتاکید", "تاکید", "الرقم السری", "رقمک السری", "کلمه السر",
        "الرمز السری",
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
        // Lead, R6-6: a physical access code (a door, a gate, a car park, a locker) allows nobody, however it is
        // named ("Entrance PIN 4829", "Your building access code"). Not the harmless uses: "at your (front) door" and
        // "to the door" are where a parcel arrives (written with "." for the spaces, as words() would turn a space
        // into an unbounded run, which a look-behind must not hold); an entrance exam, a parking payment, a gate
        // number and Gate.io are no access codes.
        "(?<!(?:at|to).(?:your|the).(?:(?:front|main|back).)?)door", "entrance(?! exam)",
        "parking(?! (?:payment|fee|fine|ticket|permit))", "building", "gate(?!\\.io)(?! (?:number|no\\.?))",
        "gate code", "garage", "locker",
        // Persian
        "پیگیری", "رهگیری", "مرسوله", "کد سفارش", "شماره سفارش", "کد معرف", "کد معرفی", "کد شعبه", "کد نوبت",
        "شماره نوبت", "کد بورسی", "کد پستی", "کد مشتری", "شماره مشتری", "کد اشتراک", "کد ملی", "کد رزرو", "کد خطا",
        "شناسه پرداخت", "تغییر کرد", "تغییر یافت", "درب", "در ورودی", "پارکینگ", "ساختمان", "آسانسور",
        // Arabic; "تم تغییر" (has been changed) and "المنتهیه" (ending in). The words of a card, an account, a blocked
        // or expired password are [noticeWords]. The bare "تغییر" is left out: it is also the Persian word in "کد تایید
        // تغییر شماره: 482913"; so is "حسابک", as in "رمز التحقق الخاص بحسابك هو 482913".
        "الخصم", "التتبع", "تتبع", "الشحنه", "شحنه", "الطلب", "طلبک", "مرجع", "المرجع", "الحجز", "رقم العملیه",
        "تم تغییر", "المنتهیه", "المنتهی",
    )

    /**
     * Words of a notice (review 3, R3-4): a card, an account, a blocked or expired password, a wi-fi or modem password.
     * They take the allow from a password or a PIN, never from a one-time code phrase ("Your WiFi OTP is 482913",
     * "رمز التحقق لتفعيل بطاقتك هو 482913"); "رقم" right before the number counts as one too.
     */
    internal val noticeWords: List<String> = listOf(
        "\\p{L}*بطاق\\p{L}*", "لحساب", "مسدود", "وای فای", "وایفای", "wi-?fi", "مودم", "modem", "الوای فای",
        "الوایفای", "منقضی شد", "منقضی شده", "ستنتهی قریبا",
    )

    /** Phrases of [strongPhrases] that name a lasting secret, not a one-time code: notice words still veto them. */
    internal val staticSecrets: List<String> = listOf(
        "password", "pin", "passcode", "رمز عبور", "رمز شخصی", "رمز ورود", "گذرواژه", "پسورد", "کلمه عبور",
        "کلمه ی عبور", "کلمه المرور", "الرقم السری", "رقمک السری", "کلمه السر", "الرمز السری", "رمز الدخول",
    )

    private const val BARE_THAI_CODE = "รหัส"
    /** As in [OtpDetector.NEAR]: a mark is never a separator, and neither run is cut back, so matching stays linear. */
    private const val SEP = "[^\\p{L}\\p{M}\\p{N}]{1,8}+"
    private const val WORD = OtpDetector.WORD

    /** A reference or masked number the chains step over ([OtpDetector.STEP]), before a word or before the code. */
    private const val STEP_ONE = "${OtpDetector.STEP}$SEP"
    private const val STEP_LAST = "(?:$STEP_ONE){0,2}"

    /** The characters either side of a code that are searched. */
    private const val WINDOW = 120

    /** How far from the code a link stops a possessive form from allowing. */
    private const val LINK_WINDOW = 60

    private fun words(list: List<String>) = "(?<![\\p{L}\\p{N}])(?:" +
        list.map { TextNormalizer.fold(it).replace(" ", "\\s*") }.sortedByDescending { it.length }.joinToString("|") +
        ")(?![\\p{L}\\p{N}])"

    private val phrase = OtpDetector.keywordPattern(strongPhrases)
    private val weak = OtpDetector.keywordPattern(OtpKeywords.weak)
    private val plainWord = "(?!$weak)(?:${OtpDetector.STEP}$SEP){0,2}$WORD"

    /** A phrase, then up to four plain words, or a generic "code" after at most one word ("PIN reset code is"). */
    private val strongBefore = Regex(
        "$phrase$SEP(?:(?:$WORD$SEP)?$weak$SEP(?>$plainWord$SEP){0,2}|(?>$plainWord$SEP){0,4})$STEP_LAST$",
        RegexOption.IGNORE_CASE,
    )
    private val strongAfter = Regex("^$SEP(?>$plainWord$SEP){0,4}$phrase", RegexOption.IGNORE_CASE)

    /** The CJK and Thai code words, without the bare Thai "รหัส" ("code" alone, R-2). */
    private val cjkWords = "(?:" + OtpKeywords.cjk.filter { it != BARE_THAI_CODE }
        .map { TextNormalizer.fold(it).replace(" ", "\\s?") }
        .sortedByDescending { it.length }.joinToString("|") + ")"
    private val cjkBefore = Regex("$cjkWords[^\\p{N}]{0,${OtpKeywords.CJK_GAP}}$")
    private val cjkAfter = Regex("^[^\\p{N}]{0,${OtpKeywords.CJK_GAP}}$cjkWords")

    private val veto = words(vetoWords)
    private val vetoBefore = Regex("$veto$SEP(?>(?:$STEP_ONE){0,2}$WORD$SEP){0,4}$STEP_LAST$", RegexOption.IGNORE_CASE)

    /**
     * A word right before the number that names it a number or an amount: "رقم 1234" (number), "مبلغ: 482913"
     * (amount), "مانده", "amount", "balance". Such a number is shown, but allows nobody (R2-2, R2-5).
     */
    private val numberWordBefore = Regex(
        "(?<![\\p{L}\\p{N}])(?:مبلغ|مانده|amount|balance|انجام شد|کارمزد|تاریخ|ساعت|پذیرنده|date|fee|declined|" +
            "flight|gate|terminal|seat|" +
            "credited|ref)$SEP$",
        RegexOption.IGNORE_CASE,
    )

    /** A number followed by a unit of time is a duration ("اعتبار 1200 ثانیه"): it allows nobody (R4-3). */
    private val durationAfter =
        Regex("^$SEP(?:ثانیه|دقیقه|seconds?|secs?|minutes?|mins?)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private val vetoAfter = Regex("^$SEP(?:$WORD$SEP){0,4}$veto", RegexOption.IGNORE_CASE)
    private val notice = words(noticeWords)
    private val noticeBefore =
        Regex("(?:$notice$SEP(?:$WORD$SEP){0,4}|(?<![\\p{L}\\p{N}])رقم$SEP)$", RegexOption.IGNORE_CASE)
    private val noticeAfter = Regex("^$SEP(?:$WORD$SEP){0,4}$notice", RegexOption.IGNORE_CASE)
    private val oneTime = OtpDetector.keywordPattern(strongPhrases - staticSecrets.toSet())
    private val oneTimeBefore = Regex("$oneTime$SEP(?:$WORD$SEP){0,4}$", RegexOption.IGNORE_CASE)
    private val oneTimeAfter = Regex("^$SEP(?:$WORD$SEP){0,4}$oneTime", RegexOption.IGNORE_CASE)
    /** "N را به 30001234 ارسال کنید": a number to send to a short code, not a code to type in. */
    private val sendTo = Regex("^\\s?را\\s?به\\s?[0-9]{3,}")

    /**
     * Reference words of languages without spaces: parcel, order, express delivery, coupon, discount (R-2), and the
     * pick-up of a parcel (N5-5: "取件验证码" is a parcel locker's code).
     */
    internal val cjkVetoWords = listOf(
        "พัสดุ", "สั่งซื้อ", "订单", "訂單", "快递", "快遞", "注文", "주문", "쿠폰", "优惠", "優惠", "取件", "提货", "取货", "自提",
        "수령", "픽업",
    )
    private val cjkVeto = "(?:" + cjkVetoWords.joinToString("|") + ")"
    private val cjkVetoBefore = Regex("$cjkVeto[^\\p{N}]{0,${OtpKeywords.CJK_GAP}}$")
    private val cjkVetoAfter = Regex("^[^\\p{N}]{0,${OtpKeywords.CJK_GAP}}$cjkVeto")

    /** Marks that end a clause in Chinese, Japanese and Korean: a reference word behind one names something else. */
    private const val CLAUSE_MARKS = "，,。；;！!？?、"

    /** Words of paying and logging in: a clause about one names what the code is for (F-1). */
    private const val PAY_OR_LOGIN = "(?:支付|付款|登录|登錄|登入|結帳|结账|支払|ログイン|결제|로그인)"

    /**
     * Fable F-1: a CJK code word closer to the code than the reference word, with a clause mark between them, wins over
     * it, but only when that clause speaks of paying or logging in: "您正在支付订单，验证码 123456" (you are paying an
     * order; the verification code is 123456) allows, while "您的订单已发货，验证码 482915" (the order has shipped),
     * "您的订单验证码是 573920" (the order's verification code) and "您的快递已到，取件验证码" (pick-up) do not.
     */
    private val notVeto = "(?:(?!$cjkVeto)[^\\p{N}])"
    private val cjkCodeAfterClause =
        Regex("$PAY_OR_LOGIN[^\\p{N}]*?[$CLAUSE_MARKS]$notVeto*?$cjkWords$notVeto{0,${OtpKeywords.CJK_GAP}}$")
    private val cjkClauseBeforeVeto =
        Regex("^$notVeto{0,${OtpKeywords.CJK_GAP}}[$CLAUSE_MARKS]$notVeto{0,${OtpKeywords.CJK_GAP}}$PAY_OR_LOGIN")

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

    /**
     * Lead, R5-5, R6-2, R6-3: a request to hand the code to someone never allows its sender, nor does a text of betting
     * or a lottery. A warning is no request: "not", "never", "n't", "no one" or "nobody" earlier in the same clause
     * (Persian "هرگز", "هیچ وقت", "هیچ کس", "نمی خواهیم") makes it one ("Never tell the agent your OTP", "will never
     * ask you to share the code with our agent", "We never call or ask you to share the OTP with our staff"). A comma,
     * a semicolon or a colon ends the clause ("Don't worry, share the code with our agent" is a request), except one
     * right after the negation or around an aside ("Never, ever share", "Do not, under any circumstances, share").
     * "Example asks you to share the code" is a request.
     */
    private const val WARNED_EN =
        "(?<!(?:(?<![\\p{L}])(?:not|never|dont|no one|nobody)|n't)(?:,[^.!?,;:\u060C\u061B\\n]{0,30},|,)?" +
            "[^.!?,;:\u060C\u061B\\n]{0,32})"
    private const val WARNED_FA =
        "(?<!(?<![\\p{L}])(?:هرگز|هیچ ?وقت|هیچ ?کس|هیچ ?گاه|نمی\\s?خواه\\p{L}{0,4})[^.!?,;:\u060C\u061B\\n]{0,40})"
    private const val ASK_EN = "(?:share|give|tell|read|send|provide|forward|confirm)" +
        "(?: it| this| the| your)?(?: otp| code| verification code| pin)? (?:with|to) (?:the |our |your )?"
    private const val ASK_FA_VERB = "(?:\\p{L}{1,12}\\s?){0,2}(?:اعلام کنید|اعلام نمایید|بگویید|بدهید|بفرستید|" +
        "ارسال کنید|ارائه دهید|ارائه کنید|بخوانید)"

    /** To an agent, a caller, an officer or support: never from the service whose code it is. */
    private val handOverOfficial = Regex(
        "(?<![\\p{L}\\p{N}])(?:" +
            "$WARNED_FA(?:به|برای)\\s?(?:کارشناس|اپراتور|نماینده|پشتیبان|پشتیبانی|همکار|همکاران|مامور(?!\\s?تحویل))" +
            "(?:\\s|$)$ASK_FA_VERB|" +
            "$WARNED_EN(?:$ASK_EN(?:agent|caller|representative|executive|officer|staff|support team|support|" +
            "customer care|team)|tell (?:the |our |your )agent|reply with the (?:verification code|code|otp))" +
            ")(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )

    /**
     * To a driver, a rider or a courier (lead, R6-3): a ride or a delivery code ("Share this code with your driver",
     * "این کد را به پیک بگویید"). It takes the allow only in a text about a bank, a payment, an account, a card or a
     * login ([bankWord]): a courier never needs a bank code.
     */
    private val handOverDelivery = Regex(
        "(?<![\\p{L}\\p{N}])(?:" +
            "$WARNED_FA(?:به|برای)\\s?(?:پیک|راننده|سفیر|مامور\\s?تحویل)(?:\\s|$)$ASK_FA_VERB|" +
            "$WARNED_EN$ASK_EN(?:rider|courier|driver|captain|delivery (?:agent|executive|partner|boy|person))" +
            ")(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )
    private val bankWord = Regex(
        "(?<![\\p{L}])(?:bank|banking|payment|account|a/c|card|login|log ?in|sign ?in|transaction|txn|upi|" +
            "بانک|پرداخت|حساب|کارت|تراکنش|رمز پویا|بنک|البنک|بطاقه|بطاقتک|الحساب|حسابک|الدفع)(?![\\p{L}])",
        RegexOption.IGNORE_CASE,
    )

    /** Betting and lotteries (R5-5, R6-4); "Bet365" stays a name, as "bet" needs a word edge. */
    private val betting = Regex(
        "(?<![\\p{L}\\p{N}])(?:bet|betting|casino|poker|sorteo|gewinnspiel|lucky draw|raffle|sweepstakes?|apostas?|" +
            "apuestas?|bahis|казино|شرط ?بندی|پیش ?بینی|کازینو|رهان|مراهنات|السحب علی الجوائز)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )

    /** Whether the folded [full] text asks to hand the code over, or speaks of betting ([handOverOfficial], ...). */
    private fun handOverOrBet(full: String): Boolean =
        handOverOfficial.containsMatchIn(full) || betting.containsMatchIn(full) ||
            handOverDelivery.containsMatchIn(full) && bankWord.containsMatchIn(full)

    /** Whether [body] allows its sender for good (see the class comment). Never throws. */
    fun isAllowingCode(body: String): Boolean = runCatching {
        if (!OtpDetector.looksLikeOtp(body)) return@runCatching false
        val full = TextNormalizer.fold(body)
        if (OtpDetector.hasAdvertisingWord(full) || handOverOrBet(full)) return@runCatching false
        val text = OtpDetector.scanned(full)
        val secret = secrecy.containsMatchIn(text)
        val bound = OtpDetector.isOriginBound(body)
        val near = OtpDetector.withinReach(text)
        codeRanges(body, text).filter { bound || near(it) }.any { range ->
            val vouched = bound || secret && OtpDetector.keywordClose(text, range)
            !vetoNear(text, range) && (vouched || allowsNear(text, range, body))
        }
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

    /** A single star, bullet or cross right before a number: the last digits of an account or a card (R4-1). */
    private val loneMark = Regex("(?<![\\p{N}*\u2022\u25CF\u00D7])[*\u2022\u25CF\u00D7]$")
    private val phraseAtMark = Regex("$phrase$SEP(?:(?:is|است|هو|شما)$SEP)?$", RegexOption.IGNORE_CASE)

    /** The digits of a card or an account last: four of them after a single mark that no star closes. */
    private const val LAST_DIGITS = 4

    private fun vetoNear(text: String, range: IntRange): Boolean {
        val head = before(text, range)
        val tail = after(text, range)
        if (maskedLastDigits(head, tail, range)) return true
        val cjkCodeFirst = cjkBefore.containsMatchIn(head) && cjkClauseBeforeVeto.containsMatchIn(tail)
        return referenceNear(head, tail) || vetoAfter.containsMatchIn(tail) ||
            cjkVetoBefore.containsMatchIn(head) && !cjkCodeAfterClause.containsMatchIn(head) ||
            cjkVetoAfter.containsMatchIn(tail) && !cjkCodeFirst
    }

    /**
     * R4-1: four digits after a single star, bullet or cross that no mark closes are the last digits of an account or a
     * card ("از حساب *1234 برداشت شد"), unless a strong phrase stands right before the mark ("OTP *4829").
     */
    private fun maskedLastDigits(head: String, tail: String, range: IntRange): Boolean {
        val closed = tail.isNotEmpty() && tail[0] in "*\u2022\u25CF"
        return range.count() == LAST_DIGITS && !closed && loneMark.containsMatchIn(head) &&
            !phraseAtMark.containsMatchIn(head)
    }

    /** A reference, an amount, a notice word, a duration or a short code to send to, next to the code. */
    private fun referenceNear(head: String, tail: String): Boolean {
        val oneTimeNear = oneTimeBefore.containsMatchIn(head) || oneTimeAfter.containsMatchIn(tail)
        val noticeNear = !oneTimeNear && (noticeBefore.containsMatchIn(head) || noticeAfter.containsMatchIn(tail))
        return vetoBefore.containsMatchIn(head) || numberWordBefore.containsMatchIn(head) || noticeNear ||
            durationAfter.containsMatchIn(tail) || sendTo.containsMatchIn(tail)
    }

    /**
     * A one-time code phrase up to eight words of the sentence before the code (owner, 21 Nov; lead, R5-4): never a
     * lasting secret ([staticSecrets]: "رمز عبور", "رمز ورود", "passcode"), never the code first.
     */
    private val codePhrase = OtpDetector.keywordPattern(OtpKeywords.codePhrases - staticSecrets.toSet())
    private val vetoBetween = Regex(veto, RegexOption.IGNORE_CASE)

    /** A comma between the phrase and the code starts another clause ("کد تایید ارسال شد، نوبت شما ..."). */
    private val clauseBreak = Regex("[,\u060C\u061B](?![0-9])(?![^\\p{L}\\p{N}]*$)")
    private val wideStarts = Regex("(?=($codePhrase${OtpDetector.WIDE_NEAR}$))", RegexOption.IGNORE_CASE)

    /**
     * Whether a one-time code phrase stands up to eight words before the code in [head], in one clause, with no veto
     * word in between, and the whole [body] has no link: a code the wider reach found that allows its sender.
     */
    private fun wideNear(body: String, head: String): Boolean {
        val clean = wideStarts.findAll(head).map { it.groupValues[1] }
            .any { !vetoBetween.containsMatchIn(it) && !clauseBreak.containsMatchIn(it) }
        return clean && !OtpDetector.containsUrl(body)
    }

    private fun allowsNear(text: String, range: IntRange, body: String): Boolean {
        val head = before(text, range)
        val tail = after(text, range)
        if (strongBefore.containsMatchIn(head) || strongAfter.containsMatchIn(tail)) return true
        if (wideNear(body, head)) return true
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
