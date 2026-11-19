package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.spam.engine.OtpDetectorSampleTest.Group.MARKETING
import com.glooboo.umessage.spam.engine.OtpDetectorSampleTest.Group.OTHER
import com.glooboo.umessage.spam.engine.OtpDetectorSampleTest.Group.PHISHING_LINK
import com.glooboo.umessage.spam.engine.OtpDetectorSampleTest.Group.REAL_CODE
import com.glooboo.umessage.spam.engine.OtpDetectorSampleTest.Group.REFERENCE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The code detector on a made-up sample of everyday texts (R14): bank and service codes, order, parcel and queue
 * numbers, marketing, phishing with a link and plain messages, Persian first, then English and Arabic. Every text,
 * brand, link and number is invented; links use the reserved example.com. Each sample records its group (only
 * [Group.REAL_CODE] is a code), what [OtpDetector.looksLikeOtp] says about it ([Sample.detectedToday]: shown as a
 * code, copy chips) and what [AllowingCode.isAllowingCode] says ([Sample.allows]: allows the sender for good, check 10
 * of the filter order). The tests pin the exact misses and false hits of both, so a change to either has to update
 * this file on purpose.
 *
 * Counts: 160 samples (70 Persian, 58 English, 19 Arabic, 13 Chinese, Japanese, Korean and Thai), 71 codes and 89
 * other texts.
 *
 * | Group         | Samples | Shown as a code: wrong | Allows the sender: wrong |
 * |---------------|---------|------------------------|--------------------------|
 * | REAL_CODE     | 71      | 6 missed               | 8 missed                 |
 * | REFERENCE     | 35      | 22 called a code       | 0                        |
 * | MARKETING     | 23      | 6 called a code        | 0                        |
 * | PHISHING_LINK | 9       | 7 called a code        | 2                        |
 * | OTHER         | 22      | 3 called a code        | 0                        |
 *
 * - Shown as a code, misses: a code with no keyword; a code next to a discount word; "Enter 4321 to confirm"; the
 *   Arabic "رقمك السري"; and "Your temporary password is 9876543210", missed because a long number that starts with
 *   98 reads as a phone number (the phone guard of [OtpDetector.longShape]), not because of its length; and
 *   "OTP for your order at Example Shop is 448210", where the keyword is more than four words from the code.
 * - Shown as a code, false hits: order, parcel, customer, referral, branch, queue, broker and zip numbers next to the
 *   word "code" (کد, code, رمز), also "Your booking code is N" and parcel, order and coupon codes in Chinese, Japanese,
 *   Korean and Thai; ads whose discount word the detector does not know ("at checkout", "free",
 *   "كود الخصم", "Sign in today and use code 4455"); "Error code" and a time written without a colon after a password
 *   word (N-1); and every phishing text that names a code.
 * - Allows the sender, misses: the six above, plus two codes next to the plain word only that do not say whose code
 *   it is ("کد: ۷۴۱۰", "کدتان برای ورود ۳۱۲۵ است"). Their senders' chats go to Spam, silently (owner, fix round A).
 * - Allows the sender, false hits: two phishing texts with a real code phrase and a link ("کد تایید ۴۸۲۹",
 *   "Verification code 482915"). The owner decided that a code allows its sender with or without a link.
 */
class OtpDetectorSampleTest {

    /** What a sample is. Only [REAL_CODE] is a code; the others are texts a sender sends every day. */
    private enum class Group {
        REAL_CODE,

        /** A number that names an order, a parcel, an account, a branch, a queue turn or a place. */
        REFERENCE,
        MARKETING,

        /** A fake notice that asks for a tap on a link. */
        PHISHING_LINK,
        OTHER,
    }

    /**
     * One text, its [group], whether the detector shows it as a code ([detectedToday]) and whether it allows the sender
     * ([allows]; by default exactly the codes that are shown as codes).
     */
    private class Sample(
        val group: Group,
        val body: String,
        val detectedToday: Boolean,
        val allows: Boolean = group == REAL_CODE && detectedToday,
    ) {
        val isCode get() = group == REAL_CODE
    }

    /** Persian: bank and app codes, order, parcel and queue numbers, ads with the opt-out line, links. */
    private val persian = listOf(
        Sample(REAL_CODE, "کد تایید شما: ۴۸۲۹۱۵", detectedToday = true),
        Sample(REAL_CODE, "رمز یکبار مصرف خرید: 739201\nبانک نمونه", detectedToday = true),
        Sample(REAL_CODE, "رمز پویا ۵۶۷۸۹۰\nمبلغ ۲۵۰۰۰۰ ریال", detectedToday = true),
        Sample(REAL_CODE, "کد ورود شما به اپلیکیشن نمونه: 2468", detectedToday = true),
        Sample(REAL_CODE, "۳۱۴۱۵۹ کد فعالسازی حساب شماست", detectedToday = true),
        Sample(REAL_CODE, "کد امنیتی: 8080\nاین کد را در اختیار دیگران قرار ندهید", detectedToday = true),
        Sample(REAL_CODE, "کدتان ۹۰۱۲ است", detectedToday = true),
        Sample(REAL_CODE, "رمز دوم پویا: 66554433", detectedToday = true),
        Sample(REAL_CODE, "کد تایید پرداخت 192837 - اعتبار ۲ دقیقه", detectedToday = true),
        Sample(REAL_CODE, "به سامانه نمونه خوش آمدید. کد: ۷۴۱۰", detectedToday = true, allows = false),
        Sample(REAL_CODE, "کد احراز هویت شما 5821 می‌باشد", detectedToday = true),
        Sample(REAL_CODE, "رمز عبور موقت شما: 1357924680", detectedToday = true),
        Sample(REAL_CODE, "کد تایید شما ۱۲۳ ۴۵۶", detectedToday = true),
        Sample(REAL_CODE, "کد یکبار مصرف ورود ۸۸۷۷۶۶\nلغو۱۱", detectedToday = true),
        Sample(REAL_CODE, "رمز اینترنتی دوم: ۴۲۴۲۴۲", detectedToday = true),
        Sample(REAL_CODE, "۴۸۲۹۱۵ را در برنامه نمونه وارد کنید", detectedToday = false),
        Sample(REAL_CODE, "رمز ورود شما: x7k2m9", detectedToday = true),
        Sample(REAL_CODE, "کد فعال سازی 4821 جهت ثبت نام در سایت نمونه", detectedToday = true),
        Sample(REAL_CODE, "کد تایید شما 2580 است. تخفیف ویژه اعضا در اپ نمونه", detectedToday = false),
        Sample(REAL_CODE, "رمز شخصی شما ۱۲۳۴۵۶۷۸۹۰ است", detectedToday = true),
        Sample(REAL_CODE, "کد ورود شما: ۴۸۲۹۱۵\nexample.com/app", detectedToday = true),
        Sample(REAL_CODE, "رمز یکبار مصرف 739201\n\n@example.com #739201", detectedToday = true),
        Sample(REFERENCE, "کد پیگیری سفارش شما 55667788", detectedToday = true),
        Sample(REFERENCE, "مرسوله شما با کد رهگیری 482910 تحویل پست شد", detectedToday = true),
        Sample(REFERENCE, "سفارش ۱۰۲۳۴ شما ارسال شد. پیک تا ساعت ۱۸ می‌رسد", detectedToday = false),
        Sample(REFERENCE, "قبض برق شما به مبلغ ۳۴۵۶۷۸ ریال صادر شد. شناسه پرداخت 1234567", detectedToday = false),
        Sample(REFERENCE, "نوبت شما در مطب نمونه: شنبه ساعت ۱۰:۳۰. شماره نوبت ۱۲", detectedToday = false),
        Sample(REFERENCE, "کد اشتراک شما 1234567890 است", detectedToday = false),
        Sample(REFERENCE, "کد پستی ۱۲۳۴۵۶۷۸۹۰ برای آدرس جدید ثبت شد", detectedToday = false),
        Sample(REFERENCE, "کد مشتری: 4455 لطفا در تماس با پشتیبانی اعلام کنید", detectedToday = true),
        Sample(REFERENCE, "کد معرف شما 482915 است. آن را برای دوستانتان بفرستید", detectedToday = true),
        Sample(REFERENCE, "کد سفارش شما 739201 ثبت شد", detectedToday = true),
        Sample(REFERENCE, "کد شعبه ۱۲۳۴ - بانک نمونه", detectedToday = true),
        Sample(REFERENCE, "کد نوبت شما ۴۵۶۷ است. لطفا در سالن انتظار بمانید", detectedToday = true),
        Sample(REFERENCE, "کد بورسی شما ۱۲۳۴۵۶۷۸ در سامانه نمونه ثبت شد", detectedToday = true),
        Sample(REFERENCE, "پرداخت شما تایید شد. مبلغ ۱۵۰۰۰۰ ریال. شماره پیگیری ۴۸۲۹۱۵", detectedToday = false),
        Sample(REFERENCE, "کد رهگیری مرسوله: ۱۲۳۴۵۶۷۸۹۰۱۲", detectedToday = false),
        Sample(MARKETING, "کد تخفیف ۲۰۲۵ برای خرید بعدی شما!\nلغو۱۱", detectedToday = false),
        Sample(MARKETING, "فقط امروز ۳۰٪ تخفیف روی همه کالاها. کد: SALE30", detectedToday = false),
        Sample(MARKETING, "با کد 7788 در قرعه کشی شرکت کنید\nلغو۱۱", detectedToday = false),
        Sample(
            MARKETING, "جشنواره پاییزه فروشگاه نمونه: خرید بالای ۵۰۰ هزار تومان ارسال رایگان", detectedToday = false,
        ),
        Sample(MARKETING, "کد هدیه ۴۴۵۵ مخصوص شما. اعتبار تا پایان آبان ۱۴۰۵", detectedToday = false),
        Sample(MARKETING, "برای فعالسازی بسته اینترنت ۱۰ گیگ عدد 5 را به 3000 ارسال کنید", detectedToday = false),
        Sample(MARKETING, "اعتبار هدیه ۲۰۰ هزار تومانی با کد 9090 فعال شد", detectedToday = false),
        Sample(MARKETING, "کد تخفیف ویژه: ۷۷۸۸ فقط تا جمعه", detectedToday = false),
        Sample(MARKETING, "دعوت از دوستان = ۵۰ هزار تومان اعتبار. کد معرف: ۲۴۶۸", detectedToday = true),
        Sample(
            PHISHING_LINK,
            "ابلاغیه الکترونیک: پرونده شما با کد پیگیری 48291537 ثبت شد. مشاهده: https://example.com/notice",
            detectedToday = true,
        ),
        Sample(
            PHISHING_LINK,
            "سود سهام شما واریز نشد. کد رهگیری ۵۵۶۶۷۷ را در example.com/sod وارد کنید",
            detectedToday = true,
        ),
        Sample(PHISHING_LINK, "ثبت نام شما ناقص است. تکمیل: example.com/form کد پیگیری: ۷۷۸۸۹۹", detectedToday = true),
        Sample(
            PHISHING_LINK,
            "اخطار: کد تایید ۴۸۲۹ برای بستن حساب شما صادر شد. لغو: example.com/cancel",
            detectedToday = true,
            allows = true,
        ),
        Sample(
            PHISHING_LINK, "حساب شما مسدود شد. برای رفع مسدودی همین حالا وارد شوید: example.com/login",
            detectedToday = false,
        ),
        Sample(OTHER, "شارژ ۵۰۰۰۰ تومانی شما با موفقیت انجام شد", detectedToday = false),
        Sample(OTHER, "بانک نمونه\nبرداشت: ۱,۲۰۰,۰۰۰ ریال\nمانده: ۸,۵۰۰,۰۰۰\n۱۴۰۵/۰۸/۲۸", detectedToday = false),
        Sample(OTHER, "واریز حقوق ماه آبان به حساب شما انجام شد. مبلغ 45000000 ریال", detectedToday = false),
        Sample(OTHER, "کد ملی شما در سامانه ثبت شد", detectedToday = false),
        Sample(OTHER, "رمز کارت شما با موفقیت تغییر کرد", detectedToday = false),
        Sample(OTHER, "سلام، ساعت ۸ جلسه داریم؟", detectedToday = false),
        Sample(OTHER, "قرار ما ساعت ۱۹:۳۰ جلوی سینما", detectedToday = false),
        Sample(OTHER, "برای دریافت رمز با 09060000000 تماس بگیرید", detectedToday = false),
        Sample(OTHER, "رمز عبور شما در تاریخ ۱۴۰۵/۰۸/۲۷ ساعت ۱۴۳۲ تغییر کرد", detectedToday = true),
        Sample(OTHER, "رمز کارت شما ۳ بار اشتباه وارد شد", detectedToday = false),
        // Fix round A-1: more real formats.
        Sample(REAL_CODE, "کد تاییدیه شما: 662281", detectedToday = true),
        Sample(REAL_CODE, "کد اعتبارسنجی: ۵۵۸۱۲۳", detectedToday = true),
        Sample(REAL_CODE, "کد شما: ۷۲۶۱۹۰", detectedToday = true),
        Sample(REAL_CODE, "کد ورود به نمونه: 9182", detectedToday = true),
        Sample(REAL_CODE, "مشتری گرامی، رمز پویای شما ۸۸۱۲۴۵ است", detectedToday = true),
        Sample(REAL_CODE, "رمز یکبار مصرف سفارش شما 552910 است", detectedToday = true),
        Sample(REAL_CODE, "کد تایید ثبت سفارش: ۴۴۱۹۲۰", detectedToday = true),
        Sample(REAL_CODE, "کدتان برای ورود ۳۱۲۵ است", detectedToday = true, allows = false),
        Sample(REAL_CODE, "رمز عبور یکبار مصرف شما: 7731", detectedToday = true),
    )

    /** English: codes, orders and parcels, promotions, links, reminders and notices. */
    private val english = listOf(
        Sample(REAL_CODE, "Your Acme verification code is 482915. Do not share it.", detectedToday = true),
        Sample(REAL_CODE, "573920 is your ExampleBank one-time password. Valid for 5 minutes.", detectedToday = true),
        Sample(REAL_CODE, "Use code 1029 to sign in to ExampleApp.", detectedToday = true),
        Sample(REAL_CODE, "Your login code: 123-456", detectedToday = true),
        Sample(REAL_CODE, "Your PIN reset code is 7314", detectedToday = true),
        Sample(REAL_CODE, "ExampleShop: your security code is 664422", detectedToday = true),
        Sample(REAL_CODE, "Enter 4321 to confirm your phone number.", detectedToday = false),
        Sample(REAL_CODE, "Your temporary password is 9876543210", detectedToday = false),
        Sample(REAL_CODE, "Your code is ab12cd", detectedToday = true),
        Sample(REAL_CODE, "Your code is 482915.\n\n@example.com #482915", detectedToday = true),
        Sample(
            REAL_CODE, "Your ExampleBank code is 573920. Never share it. Help: example.com/help", detectedToday = true,
        ),
        Sample(REFERENCE, "Your order #48291 has shipped and will arrive Tuesday.", detectedToday = false),
        Sample(REFERENCE, "Your parcel 7720 1834 is out for delivery today.", detectedToday = false),
        Sample(REFERENCE, "Tracking code 99887766 for your delivery", detectedToday = true),
        Sample(REFERENCE, "Your verification is complete. Ref 556677", detectedToday = true),
        Sample(REFERENCE, "Zip code 12345 saved for your new address", detectedToday = true),
        Sample(REFERENCE, "Your booking reference is 482915. See you on Friday", detectedToday = false),
        Sample(REFERENCE, "Your order code is 556677", detectedToday = true),
        Sample(REFERENCE, "Branch code 1234 has been added to your account", detectedToday = true),
        Sample(MARKETING, "Use promo code SAVE20 for 20% off your next order!", detectedToday = false),
        Sample(MARKETING, "Flash sale! Code 2025 gets you free shipping until Dec 31, 2025.", detectedToday = false),
        Sample(MARKETING, "Call +15550001234 to claim your prize", detectedToday = false),
        Sample(MARKETING, "Win a free trip! Text 5555 to enter. Reply STOP to opt out.", detectedToday = false),
        Sample(MARKETING, "Your gift card code 4455 6677 is ready", detectedToday = false),
        Sample(MARKETING, "Use code 4455 at checkout", detectedToday = true),
        Sample(MARKETING, "Get 2 months free with code 3344", detectedToday = true),
        Sample(
            PHISHING_LINK,
            "Your account is locked. Verification code 482915. Unlock at http://example.com/unlock",
            detectedToday = true,
            allows = true,
        ),
        Sample(PHISHING_LINK, "Your account is suspended. Restore it at example.com/restore", detectedToday = false),
        Sample(
            PHISHING_LINK, "Parcel held: pay the fee with tracking code 7720 at example.com/pay", detectedToday = true,
        ),
        Sample(OTHER, "Your account balance is \$1,250.40 as of 11/19.", detectedToday = false),
        Sample(OTHER, "Reminder: your appointment is on Monday at 10:30. Reply 1 to confirm.", detectedToday = false),
        Sample(OTHER, "Your table for 4 at Example Bistro is booked for 7pm.", detectedToday = false),
        Sample(OTHER, "Your Example Mobile bill of 45.99 USD is due on 2026-12-01.", detectedToday = false),
        Sample(OTHER, "Meet me at gate 12 at 14:05", detectedToday = false),
        Sample(OTHER, "Your ride is arriving in 4 minutes. Driver: Sam, plate 7ABC123", detectedToday = false),
        Sample(OTHER, "Error code 5003: payment could not be completed", detectedToday = true),
        Sample(
            OTHER,
            "Password changed on 2026-11-18 at 1432. If this was not you, contact support.",
            detectedToday = true,
        ),
        Sample(OTHER, "Your PIN was changed. Not you? Call +15550001234", detectedToday = false),
        // Fix round A-1: plain English codes and bank texts with "Dear Customer", "order", "Ref no", "Free Msg".
        Sample(REAL_CODE, "Your code is 482915", detectedToday = true),
        Sample(REAL_CODE, "Your Example code: 573920", detectedToday = true),
        Sample(REAL_CODE, "Use 664422 to verify your account", detectedToday = true),
        Sample(REAL_CODE, "Dear Customer, your OTP is 482915. Do not share it with anyone.", detectedToday = true),
        Sample(
            REAL_CODE, "Dear Customer, 739201 is the OTP for your order of USD 25.00 at Example Shop.",
            detectedToday = true,
        ),
        Sample(
            REAL_CODE, "Ref no 55123. Your one-time password is 284613. Valid for 5 minutes.", detectedToday = true,
        ),
        Sample(REAL_CODE, "Free Msg: Your Example verification code is 902183", detectedToday = true),
        Sample(REAL_CODE, "Your Example login PIN is 4471. Don’t share it.", detectedToday = true),
        Sample(REAL_CODE, "G-482915 is your Example verification code.", detectedToday = true),
        Sample(REAL_CODE, "482915 is your Example code", detectedToday = true),
        Sample(REAL_CODE, "Your security code for Example is 3391", detectedToday = true),
        Sample(REAL_CODE, "Example: 557201 is your code. It expires in 10 minutes.", detectedToday = true),
        Sample(
            REAL_CODE, "Example Bank: OTP for your order at Example Shop is 448210. Never share it.",
            detectedToday = false,
        ),
        Sample(REAL_CODE, "Your sign-in code is 662013", detectedToday = true),
        Sample(REAL_CODE, "Your Example verification code is: 8812-4410", detectedToday = true),
        Sample(MARKETING, "Sign in today and use code 4455", detectedToday = true),
        // Second review R-2: a possessive "code" that names a booking, a reference or a discount.
        Sample(MARKETING, "Your discount code is 4455", detectedToday = false),
        Sample(REFERENCE, "Your booking code is 482915", detectedToday = true),
        Sample(REFERENCE, "Your reference code is 556677", detectedToday = true),
        Sample(REFERENCE, "556677 is your membership code", detectedToday = true),
    )

    /** Languages without spaces between words: Chinese (simplified and traditional), Japanese, Korean, Thai. */
    private val otherScripts = listOf(
        Sample(REAL_CODE, "您的验证码是 482915，5分钟内有效。", detectedToday = true),
        Sample(REAL_CODE, "您的驗證碼為 573920，請勿告訴他人。", detectedToday = true),
        Sample(REAL_CODE, "認証コード：664422 このコードは10分間有効です。", detectedToday = true),
        Sample(REAL_CODE, "[예시] 인증번호 [482915]를 입력해 주세요.", detectedToday = true),
        Sample(REAL_CODE, "รหัส OTP ของคุณคือ 739201", detectedToday = true),
        Sample(REAL_CODE, "รหัสยืนยันของคุณคือ 482915", detectedToday = true),
        // Second review R-2: parcels, orders and coupons, and the bare Thai "รหัส" (code).
        Sample(REFERENCE, "รหัสพัสดุ 482915", detectedToday = true),
        Sample(REFERENCE, "รหัส 4821 สำหรับคำสั่งซื้อ", detectedToday = true),
        Sample(REFERENCE, "【示例快递】您的取件验证码是 482915", detectedToday = true),
        Sample(REFERENCE, "您的订单验证码是 573920", detectedToday = true),
        Sample(REFERENCE, "ご注文の確認コード：664422", detectedToday = true),
        Sample(MARKETING, "쿠폰 인증번호 4455", detectedToday = true),
        Sample(MARKETING, "优惠验证码 7788", detectedToday = false),
    )

    /** Arabic: codes, shipping and order numbers, offers, a link, bank notices. */
    private val arabic = listOf(
        Sample(REAL_CODE, "رمز التحقق الخاص بك هو 482915", detectedToday = true),
        Sample(REAL_CODE, "٧٣٩٢٠١ هو رمز الدخول لحسابك", detectedToday = true),
        Sample(REAL_CODE, "كود التفعيل: ٤٨٢١", detectedToday = true),
        Sample(REAL_CODE, "كلمة المرور المؤقتة: 9087", detectedToday = true),
        Sample(REAL_CODE, "رمز التحقق لمرة واحدة ٥٥٦٦٧٧ صالح لمدة ٥ دقائق", detectedToday = true),
        Sample(REAL_CODE, "استخدم الرمز 1234 لتأكيد رقم هاتفك", detectedToday = true),
        Sample(REAL_CODE, "رقمك السري المؤقت 482915", detectedToday = false),
        Sample(REAL_CODE, "رمز التحقق الخاص بك 4821. للمساعدة: example.com/help", detectedToday = true),
        Sample(REFERENCE, "تم شحن طلبك رقم ١٢٣٤٥ وسيصل غداً", detectedToday = false),
        Sample(REFERENCE, "تم تحويل مبلغ 3000 إلى حسابك. رقم العملية 556677", detectedToday = false),
        Sample(REFERENCE, "رمز الشحنة 12345678", detectedToday = true),
        Sample(REFERENCE, "رقم الطلب 556677 تم تأكيده", detectedToday = false),
        Sample(MARKETING, "خصم ٥٠٪ على جميع المنتجات باستخدام الكود SALE50", detectedToday = false),
        Sample(MARKETING, "عرض خاص: احصل على هدية مجانية مع كود 7788", detectedToday = false),
        Sample(MARKETING, "كود الخصم 4455 على طلبك القادم", detectedToday = true),
        Sample(PHISHING_LINK, "تم تعليق حسابك. أدخل رمز التتبع 556677 على example.com/verify", detectedToday = true),
        Sample(OTHER, "تم خصم مبلغ ٢٥٠ ريال من حسابك", detectedToday = false),
        Sample(OTHER, "موعدك يوم الأحد الساعة ١٠:٣٠", detectedToday = false),
        Sample(OTHER, "رصيدك الحالي 1500 ريال", detectedToday = false),
    )

    private val all = persian + english + arabic + otherScripts

    private fun detected(sample: Sample) = OtpDetector.looksLikeOtp(sample.body)

    private fun allowed(sample: Sample) = AllowingCode.isAllowingCode(sample.body)

    private fun bodies(samples: List<Sample>) = samples.joinToString("\n") { it.body }

    @Test fun theSampleIsPersianFirstAndMixed() {
        assertEquals(70, persian.size)
        assertEquals(58, english.size)
        assertEquals(19, arabic.size)
        assertEquals(13, otherScripts.size)
        assertEquals(71, all.count { it.isCode })
        assertEquals(89, all.count { !it.isCode })
    }

    @Test fun theMissesAreExactlyTheRecordedOnes() {
        val expected = all.filter { it.isCode && !it.detectedToday }
        val actual = all.filter { it.isCode && !detected(it) }
        assertEquals(bodies(expected), bodies(actual))
        assertEquals(EXPECTED_MISSES, actual.size)
    }

    @Test fun theFalseHitsAreExactlyTheRecordedOnes() {
        val expected = all.filter { !it.isCode && it.detectedToday }
        val actual = all.filter { !it.isCode && detected(it) }
        assertEquals(bodies(expected), bodies(actual))
        assertEquals(EXPECTED_FALSE_HITS, actual.size)
    }

    /** Per group: how many samples, and how many are wrongly shown as a code or not (the table at the top). */
    @Test fun theCountsPerGroupAreFixed() {
        val counts = Group.entries.associateWith { group ->
            val samples = all.filter { it.group == group }
            samples.size to samples.count { detected(it) != it.isCode }
        }
        assertEquals(
            mapOf(
                REAL_CODE to (71 to 6),
                REFERENCE to (35 to 22),
                MARKETING to (23 to 6),
                PHISHING_LINK to (9 to 7),
                OTHER to (22 to 3),
            ),
            counts,
        )
    }

    @Test fun theAllowingMissesAreExactlyTheRecordedOnes() {
        val expected = all.filter { it.isCode && !it.allows }
        val actual = all.filter { it.isCode && !allowed(it) }
        assertEquals(bodies(expected), bodies(actual))
        assertEquals(EXPECTED_ALLOWING_MISSES, actual.size)
    }

    @Test fun theAllowingFalseHitsAreExactlyTheRecordedOnes() {
        val expected = all.filter { !it.isCode && it.allows }
        val actual = all.filter { !it.isCode && allowed(it) }
        assertEquals(bodies(expected), bodies(actual))
        assertEquals(EXPECTED_ALLOWING_FALSE_HITS, actual.size)
    }

    /** Per group: how many texts would allow their sender wrongly (the last column of the table at the top). */
    @Test fun theAllowingCountsPerGroupAreFixed() {
        val counts = Group.entries.associateWith { group ->
            all.filter { it.group == group }.count { allowed(it) != it.isCode }
        }
        assertEquals(mapOf(REAL_CODE to 8, REFERENCE to 0, MARKETING to 0, PHISHING_LINK to 2, OTHER to 0), counts)
    }

    /** Whatever allows a sender is also shown as a code: the strict reading never disagrees the other way. */
    @Test fun everyAllowingTextIsAlsoShownAsACode() {
        val allowedNotShown = all.filter { allowed(it) && !detected(it) }
        assertTrue(bodies(allowedNotShown), allowedNotShown.isEmpty())
    }

    @Test fun everyPhishingSampleHasALink() {
        val withoutLink = all.filter { it.group == PHISHING_LINK && !OtpDetector.containsUrl(it.body) }
        assertTrue(bodies(withoutLink), withoutLink.isEmpty())
    }

    /** N-2: three real codes carry a link; they still allow their sender (owner: with or without a link). */
    @Test fun threeRealCodesHaveALinkAndAllow() {
        val withLink = all.filter { it.isCode && OtpDetector.containsUrl(it.body) }
        assertEquals(3, withLink.size)
        assertTrue(bodies(withLink), withLink.all { allowed(it) })
    }

    /** N-3: phishing without any code word is neither shown as a code nor allows its sender. */
    @Test fun phishingWithoutACodeWordIsNoCode() {
        val plain = all.filter { it.group == PHISHING_LINK && !it.detectedToday }
        assertEquals(2, plain.size)
        assertTrue(bodies(plain), plain.none { detected(it) || allowed(it) })
    }

    /**
     * N-1: two of the OTHER false hits are a time written without a colon ("ساعت ۱۴۳۲", "at 1432") after a password
     * word. They are shown as a code, but allow nobody ("changed" and "تغییر کرد" veto).
     */
    @Test fun timesWithoutAColonAreShownButDoNotAllow() {
        val times = all.filter { it.group == OTHER && "1432" in OtpDetector.normalizeDigits(it.body) }
        assertEquals(2, times.size)
        assertTrue(bodies(times), times.all { detected(it) && !allowed(it) })
    }

    private companion object {
        const val EXPECTED_MISSES = 6
        const val EXPECTED_FALSE_HITS = 38
        const val EXPECTED_ALLOWING_MISSES = 8
        const val EXPECTED_ALLOWING_FALSE_HITS = 2
    }
}
