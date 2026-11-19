package com.glooboo.umessage.spam.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strict reading that allows a sender for good (check 10, D3 A). Fable's everyday non-codes from the step 0
 * review must not allow anyone, real codes with a strong phrase must. Every text and number is made up.
 */
class AllowingCodeTest {

    private fun allows(body: String) = AllowingCode.isAllowingCode(body)

    @Test fun strongPhrasesNextToACodeAllow() {
        listOf(
            "کد تایید شما: ۴۸۲۹۱۵",
            "رمز یکبار مصرف 739201",
            "رمز پویا ۵۶۷۸۹۰",
            "Your verification code is 482915",
            "Your OTP is 4821",
            "573920 is your one-time code",
            "Your one-time password: 664422",
            "رمز التحقق الخاص بك هو 482915",
            "Use 482915 in order to sign in",
        ).forEach { assertTrue(it, allows(it)) }
    }

    @Test fun aSecretLineOrAnOriginBoundLineAllowsThePlainWordCode() {
        assertTrue(allows("Your ExampleBank code is 573920. Never share it."))
        assertTrue(allows("کد شما 4821 است. این کد را در اختیار دیگران قرار ندهید"))
        assertTrue(allows("Your code is 482915.\n\n@example.com #482915"))
    }

    @Test fun aPossessiveFormAllowsWithoutALinkNearTheCode() {
        listOf(
            "Your code is 482915", "Your code is ab12cd", "Your Example code: 573920", "482915 is your Example code",
            "Use 664422 to verify your account", "کد شما: ۷۲۶۱۹۰", "کدتان ۹۰۱۲ است",
        ).forEach { assertTrue(it, allows(it)) }
        assertFalse(allows("Your code is 482915. Help: example.com/help"))
        assertFalse(allows("Use 664422 now"))
    }

    /** R-2: a possessive "code" that names a booking, a reference or a discount is no one-time code. */
    @Test fun aPossessiveReferenceCodeDoesNotAllow() {
        listOf(
            "Your booking code is 482915", "Your reference code is 556677", "556677 is your membership code",
            "Your ticket code is 482915", "Your account no is 48291537",
        ).forEach { assertFalse(it, allows(it)) }
    }

    /** R-2: the bare Thai word for "code" allows nobody; parcel, order and coupon words of CJK and Thai veto. */
    @Test fun cjkAndThaiReferenceWordsVeto() {
        listOf(
            "รหัส 4821 สำหรับคำสั่งซื้อ", "รหัสพัสดุ 482915",
            "您的订单验证码是 573920", "ご注文の確認コード：664422", "쿠폰 인증번호 4455",
        ).forEach { assertFalse(it, allows(it)) }
        assertTrue(allows("รหัส OTP ของคุณคือ 739201"))
    }

    @Test fun thePlainWordAloneNeverAllows() {
        listOf("کد: ۷۴۱۰", "Code 482915", "کدتان برای ورود ۳۱۲۵ است").forEach { assertFalse(it, allows(it)) }
    }

    /** A-12: the strong phrase must stand next to the code itself, not anywhere before it. */
    @Test fun aPhraseFarFromTheCodeDoesNotAllow() {
        assertFalse(allows("Sign in today and use code 4455"))
        assertTrue(allows("Use code 1029 to sign in to ExampleApp."))
        assertTrue(allows("Your PIN reset code is 7314"))
    }

    /** A veto word away from the code ("Dear Customer", "for your order", "Free Msg") does not veto. */
    @Test fun everydayWordsAwayFromTheCodeDoNotVeto() {
        listOf(
            "Dear Customer, your OTP is 482915. Do not share it with anyone.",
            "Dear Customer, 739201 is the OTP for your order of USD 25.00 at Example Shop.",
            "Ref no 55123. Your one-time password is 284613. Valid for 5 minutes.",
            "Free Msg: Your Example verification code is 902183",
            "Tracking code 99887766, verification code 4821",
            "Your order verification code is 556677",
        ).forEach { assertTrue(it, allows(it)) }
    }

    @Test fun cjkAndThaiWordsAndTheCurlyApostrophe() {
        assertTrue(allows("您的验证码是 482915，5分钟内有效。"))
        assertTrue(allows("認証コード：664422"))
        assertTrue(allows("รหัสยืนยันของคุณคือ 482915"))
        assertTrue(allows("Example code 482915. Don’t share it."))
        assertTrue(allows("کد تاییدیه شما: 662281"))
        assertTrue(allows("کد اعتبارسنجی: ۵۵۸۱۲۳"))
    }

    @Test fun referenceWordsNextToTheCodeVeto() {
        listOf(
            "کد معرف 123456",
            "کد سفارش شما 739201 ثبت شد",
            "کد شعبه ۱۲۳۴ - بانک نمونه",
            "کد پیگیری 48291537",
            "کد رهگیری مرسوله ۵۵۶۶۷۷",
            "کد نوبت شما ۴۵۶۷ است",
            "کد بورسی شما ۱۲۳۴۵۶۷۸",
            "Zip code 90210",
            "Error code 5001",
            "Use code 4455 at checkout",
            "Your order code is 556677",
            "Booking reference: security code 4821",
            "Your customer id code is 4821",
            "كود الخصم 4455 على طلبك القادم",
            "رمز التتبع 556677",
            "Password changed on 2026-11-18 at 1432",
        ).forEach { assertFalse(it, allows(it)) }
    }

    @Test fun phishingWithATrackingCodeAndALinkAllowsNobody() {
        assertFalse(allows("Parcel held: pay the fee with tracking code 7720 at example.com/pay"))
        assertFalse(allows("سود سهام شما واریز نشد. کد رهگیری ۵۵۶۶۷۷ را در example.com/sod وارد کنید"))
    }

    @Test fun aBankNameThatLooksLikeAReferenceWordDoesNotVeto() {
        // "ملی" and "شعبه" only veto after "کد" or "شماره": a bank called "ملی" still sends codes.
        assertTrue(allows("رمز پویا بانک ملی: 482915"))
    }

    @Test fun neverThrows() {
        assertFalse(allows(""))
        assertFalse(allows("\u0000￿"))
    }
}
