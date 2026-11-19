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

    @Test fun thePlainWordAloneNeverAllows() {
        listOf("Your code is 482915", "کد: ۷۴۱۰", "کدتان ۹۰۱۲ است", "Your code is ab12cd")
            .forEach { assertFalse(it, allows(it)) }
    }

    @Test fun referenceWordsVetoEvenNextToACodeWord() {
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
            "Tracking code 99887766, verification code 4821",
            "Your order verification code is 556677",
            "Booking reference 482915, security code 4821",
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
