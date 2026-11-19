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
 * brand, link and number is invented. Each sample records its group (only [Group.REAL_CODE] is a code) and what
 * [OtpDetector.looksLikeOtp] says about it today; the tests pin the exact misses and false hits, so a change to the
 * detector that moves any of them has to update this file on purpose.
 *
 * Counts: 116 samples (60 Persian, 37 English, 19 Arabic), 41 codes and 75 other texts; 5 misses, 28 false hits.
 *
 * | Group         | Samples | Wrong today        |
 * |---------------|---------|--------------------|
 * | REAL_CODE     | 41      | 5 missed           |
 * | REFERENCE     | 27      | 14 called a code   |
 * | MARKETING     | 19      | 4 called a code    |
 * | PHISHING_LINK | 7       | 7 called a code    |
 * | OTHER         | 22      | 3 called a code    |
 *
 * - Misses: a code with no keyword; a code next to a discount word; "Enter 4321 to confirm"; the Arabic "رقمك السري";
 *   and "Your temporary password is 9876543210", missed because a long number that starts with 98 reads as a phone
 *   number (the phone guard of [OtpDetector.longShape]), not because of its length.
 * - False hits: order, parcel, customer, referral, branch, queue, broker and zip numbers next to the word "code"
 *   (کد, code, رمز); ads whose discount word the detector does not know ("at checkout", "free", "كود الخصم");
 *   "Error code" and a time after a password word; and every phishing text, as the detector does not look at links:
 *   [OtpDetector.containsUrl] is what stops SMART mode from letting those through as codes, and the last test pins
 *   that every phishing sample has a link.
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

    /** One text, its [group] and whether the detector calls it a code today ([detectedToday]). */
    private class Sample(val group: Group, val body: String, val detectedToday: Boolean) {
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
        Sample(REAL_CODE, "به سامانه نمونه خوش آمدید. کد: ۷۴۱۰", detectedToday = true),
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
        Sample(REAL_CODE, "کد ورود شما: ۴۸۲۹۱۵\nexample.ir/app", detectedToday = true),
        Sample(REAL_CODE, "رمز یکبار مصرف 739201\n\n@example.ir #739201", detectedToday = true),
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
            "ابلاغیه الکترونیک: پرونده شما با کد پیگیری 48291537 ثبت شد. مشاهده: https://example.ir/notice",
            detectedToday = true,
        ),
        Sample(
            PHISHING_LINK,
            "سود سهام شما واریز نشد. کد رهگیری ۵۵۶۶۷۷ را در example.com/sod وارد کنید",
            detectedToday = true,
        ),
        Sample(PHISHING_LINK, "ثبت نام شما ناقص است. تکمیل: example.ir/form کد پیگیری: ۷۷۸۸۹۹", detectedToday = true),
        Sample(
            PHISHING_LINK,
            "اخطار: کد تایید ۴۸۲۹ برای بستن حساب شما صادر شد. لغو: example.ir/cancel",
            detectedToday = true,
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
        ),
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

    private val all = persian + english + arabic

    private fun detected(sample: Sample) = OtpDetector.looksLikeOtp(sample.body)

    private fun bodies(samples: List<Sample>) = samples.joinToString("\n") { it.body }

    @Test fun theSampleIsPersianFirstAndMixed() {
        assertEquals(60, persian.size)
        assertEquals(37, english.size)
        assertEquals(19, arabic.size)
        assertEquals(41, all.count { it.isCode })
        assertEquals(75, all.count { !it.isCode })
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

    /** Per group: how many samples, and how many the detector gets wrong today (the table at the top). */
    @Test fun theCountsPerGroupAreFixed() {
        val counts = Group.entries.associateWith { group ->
            val samples = all.filter { it.group == group }
            samples.size to samples.count { detected(it) != it.isCode }
        }
        assertEquals(
            mapOf(
                REAL_CODE to (41 to 5),
                REFERENCE to (27 to 14),
                MARKETING to (19 to 4),
                PHISHING_LINK to (7 to 7),
                OTHER to (22 to 3),
            ),
            counts,
        )
    }

    @Test fun everyPhishingSampleHasALink() {
        val withoutLink = all.filter { it.group == PHISHING_LINK && !OtpDetector.containsUrl(it.body) }
        assertTrue(bodies(withoutLink), withoutLink.isEmpty())
    }

    private companion object {
        const val EXPECTED_MISSES = 5
        const val EXPECTED_FALSE_HITS = 28
    }
}
