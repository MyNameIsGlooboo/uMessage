package com.glooboo.umessage.spam.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The code detector on a made-up sample of everyday texts (R14): bank and service codes, delivery notices, marketing
 * and plain messages, Persian first, then English and Arabic. Every text, brand and number is invented. Each sample
 * records what a person would call it (a code or not); the test pins how many the detector gets wrong today, so a
 * change to [OtpDetector] that moves either count has to update this file on purpose.
 *
 * Counts: 81 samples (43 Persian, 25 English, 13 Arabic), 36 codes and 45 other texts.
 * - Misses: 4 codes the detector does not see (a code with no keyword, a code next to a discount word, "Enter 4321 to
 *   confirm", a 10-digit temporary password).
 * - False hits: 5 other texts it calls a code (tracking and customer numbers next to the word "code", and a
 *   reference number after "verification").
 */
class OtpDetectorSampleTest {

    private class Sample(val body: String, val isCode: Boolean)

    private fun code(body: String) = Sample(body, isCode = true)
    private fun notCode(body: String) = Sample(body, isCode = false)

    /** Persian: bank and app codes, delivery, marketing with the opt-out line, bank and service notices. */
    private val persian = listOf(
        code("کد تایید شما: ۴۸۲۹۱۵"),
        code("رمز یکبار مصرف خرید: 739201\nبانک نمونه"),
        code("رمز پویا ۵۶۷۸۹۰\nمبلغ ۲۵۰۰۰۰ ریال"),
        code("کد ورود شما به اپلیکیشن نمونه: 2468"),
        code("۳۱۴۱۵۹ کد فعالسازی حساب شماست"),
        code("کد امنیتی: 8080\nاین کد را در اختیار دیگران قرار ندهید"),
        code("کدتان ۹۰۱۲ است"),
        code("رمز دوم پویا: 66554433"),
        code("کد تایید پرداخت 192837 - اعتبار ۲ دقیقه"),
        code("به سامانه نمونه خوش آمدید. کد: ۷۴۱۰"),
        code("کد احراز هویت شما 5821 می‌باشد"),
        code("رمز عبور موقت شما: 1357924680"),
        code("کد تایید شما ۱۲۳ ۴۵۶"),
        code("کد یکبار مصرف ورود ۸۸۷۷۶۶\nلغو۱۱"),
        code("رمز اینترنتی دوم: ۴۲۴۲۴۲"),
        code("۴۸۲۹۱۵ را در برنامه نمونه وارد کنید"), // miss
        code("رمز ورود شما: x7k2m9"),
        code("کد فعال سازی 4821 جهت ثبت نام در سایت نمونه"),
        code("کد تایید شما 2580 است. تخفیف ویژه اعضا در اپ نمونه"), // miss
        code("رمز شخصی شما ۱۲۳۴۵۶۷۸۹۰ است"),
        notCode("کد پیگیری سفارش شما 55667788"), // false hit
        notCode("مرسوله شما با کد رهگیری 482910 تحویل پست شد"), // false hit
        notCode("سفارش ۱۰۲۳۴ شما ارسال شد. پیک تا ساعت ۱۸ می‌رسد"),
        notCode("کد تخفیف ۲۰۲۵ برای خرید بعدی شما!\nلغو۱۱"),
        notCode("فقط امروز ۳۰٪ تخفیف روی همه کالاها. کد: SALE30"),
        notCode("با کد 7788 در قرعه کشی شرکت کنید\nلغو۱۱"),
        notCode("جشنواره پاییزه فروشگاه نمونه: خرید بالای ۵۰۰ هزار تومان ارسال رایگان"),
        notCode("کد هدیه ۴۴۵۵ مخصوص شما. اعتبار تا پایان آبان ۱۴۰۵"),
        notCode("برای فعالسازی بسته اینترنت ۱۰ گیگ عدد 5 را به 3000 ارسال کنید"),
        notCode("شارژ ۵۰۰۰۰ تومانی شما با موفقیت انجام شد"),
        notCode("بانک نمونه\nبرداشت: ۱,۲۰۰,۰۰۰ ریال\nمانده: ۸,۵۰۰,۰۰۰\n۱۴۰۵/۰۸/۲۸"),
        notCode("واریز حقوق ماه آبان به حساب شما انجام شد. مبلغ 45000000 ریال"),
        notCode("قبض برق شما به مبلغ ۳۴۵۶۷۸ ریال صادر شد. شناسه پرداخت 1234567"),
        notCode("نوبت شما در مطب نمونه: شنبه ساعت ۱۰:۳۰. شماره نوبت ۱۲"),
        notCode("کد ملی شما در سامانه ثبت شد"),
        notCode("کد اشتراک شما 1234567890 است"),
        notCode("رمز کارت شما با موفقیت تغییر کرد"),
        notCode("کد پستی ۱۲۳۴۵۶۷۸۹۰ برای آدرس جدید ثبت شد"),
        notCode("کد مشتری: 4455 لطفا در تماس با پشتیبانی اعلام کنید"), // false hit
        notCode("سلام، ساعت ۸ جلسه داریم؟"),
        notCode("قرار ما ساعت ۱۹:۳۰ جلوی سینما"),
        notCode("اعتبار هدیه ۲۰۰ هزار تومانی با کد 9090 فعال شد"),
        notCode("برای دریافت رمز با 09000001111 تماس بگیرید"),
    )

    /** English: codes, orders and parcels, promotions, reminders. */
    private val english = listOf(
        code("Your Acme verification code is 482915. Do not share it."),
        code("573920 is your ExampleBank one-time password. Valid for 5 minutes."),
        code("Use code 1029 to sign in to ExampleApp."),
        code("Your login code: 123-456"),
        code("Your PIN reset code is 7314"),
        code("ExampleShop: your security code is 664422"),
        code("Enter 4321 to confirm your phone number."), // miss
        code("Your temporary password is 9876543210"), // miss
        code("Your code is ab12cd"),
        code("Your code is 482915.\n\n@example.com #482915"),
        notCode("Use promo code SAVE20 for 20% off your next order!"),
        notCode("Your order #48291 has shipped and will arrive Tuesday."),
        notCode("Your parcel 7720 1834 is out for delivery today."),
        notCode("Flash sale! Code 2025 gets you free shipping until Dec 31, 2025."),
        notCode("Your account balance is \$1,250.40 as of 11/19."),
        notCode("Reminder: your appointment is on Monday at 10:30. Reply 1 to confirm."),
        notCode("Your table for 4 at Example Bistro is booked for 7pm."),
        notCode("Call +15550001234 to claim your prize"),
        notCode("Your Example Mobile bill of 45.99 USD is due on 2026-12-01."),
        notCode("Win a free trip! Text 5555 to enter. Reply STOP to opt out."),
        notCode("Your gift card code 4455 6677 is ready"),
        notCode("Tracking code 99887766 for your delivery"), // false hit
        notCode("Your verification is complete. Ref 556677"), // false hit
        notCode("Meet me at gate 12 at 14:05"),
        notCode("Your ride is arriving in 4 minutes. Driver: Sam, plate 7ABC123"),
    )

    /** Arabic: codes, offers, shipping and bank notices. */
    private val arabic = listOf(
        code("رمز التحقق الخاص بك هو 482915"),
        code("٧٣٩٢٠١ هو رمز الدخول لحسابك"),
        code("كود التفعيل: ٤٨٢١"),
        code("كلمة المرور المؤقتة: 9087"),
        code("رمز التحقق لمرة واحدة ٥٥٦٦٧٧ صالح لمدة ٥ دقائق"),
        code("استخدم الرمز 1234 لتأكيد رقم هاتفك"),
        notCode("خصم ٥٠٪ على جميع المنتجات باستخدام الكود SALE50"),
        notCode("تم شحن طلبك رقم ١٢٣٤٥ وسيصل غداً"),
        notCode("تم خصم مبلغ ٢٥٠ ريال من حسابك"),
        notCode("عرض خاص: احصل على هدية مجانية مع كود 7788"),
        notCode("موعدك يوم الأحد الساعة ١٠:٣٠"),
        notCode("رصيدك الحالي 1500 ريال"),
        notCode("تم تحويل مبلغ 3000 إلى حسابك. رقم العملية 556677"),
    )

    private val all = persian + english + arabic

    private fun misses() = all.filter { it.isCode && !OtpDetector.looksLikeOtp(it.body) }
    private fun falseHits() = all.filter { !it.isCode && OtpDetector.looksLikeOtp(it.body) }

    @Test fun theSampleIsPersianFirstAndMixed() {
        assertEquals(43, persian.size)
        assertEquals(25, english.size)
        assertEquals(13, arabic.size)
        assertEquals(36, all.count { it.isCode })
        assertEquals(45, all.count { !it.isCode })
    }

    @Test fun theMissCountIsFixed() {
        val misses = misses()
        assertEquals(misses.joinToString("\n") { it.body }, EXPECTED_MISSES, misses.size)
    }

    @Test fun theFalseHitCountIsFixed() {
        val falseHits = falseHits()
        assertEquals(falseHits.joinToString("\n") { it.body }, EXPECTED_FALSE_HITS, falseHits.size)
    }

    private companion object {
        const val EXPECTED_MISSES = 4
        const val EXPECTED_FALSE_HITS = 5
    }
}
