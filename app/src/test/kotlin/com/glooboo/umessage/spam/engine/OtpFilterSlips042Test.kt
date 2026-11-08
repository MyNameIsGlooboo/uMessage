package com.glooboo.umessage.spam.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SP1, SP2, SP-lowA (B8), and I2: what counts as a code and a link. */
class OtpFilterSlips042Test {

    private fun otp(body: String) = OtpDetector.looksLikeOtp(body)
    private fun link(body: String) = OtpDetector.containsUrl(body)

    // ---- SP1: a 4-digit code that looks like a year ----

    @Test fun yearLikeCodeNextToTheWordCodeIsStillACode() {
        assertTrue(otp("Your Uber code is 1987"))
        assertTrue(otp("Snapchat code 2041. Don't share it"))
        assertTrue(otp("کد ورود شما به برنامه 1405 است"))
    }

    @Test fun yearNextToADateWordIsNotACode() {
        assertFalse(otp("Your code is valid until 2025"))
        assertFalse(otp("Membership code for year 2025"))
        assertFalse(otp("کد اشتراک شما تا سال ۱۴۰۴ معتبر است"))
    }

    // ---- SP2: one marketing word next to a real code ----

    @Test fun giftAndRewardWordsDoNotCancelAStrongKeyword() {
        assertTrue(otp("Your Apple Gift Card verification code is 482913"))
        assertTrue(otp("Reward redemption OTP: 551236"))
        assertTrue(otp("Bonus account login: your one-time password is 667788"))
    }

    @Test fun discountsAndPrizesStillCancelEvenAStrongKeyword() {
        assertFalse(otp("You are the winner! Verify with 123456"))
        assertFalse(otp("Discount verification 4821, 50% off"))
        assertFalse(otp("Promo OTP 556677"))
    }

    @Test fun giftWordStillCancelsTheGenericWordCode() {
        assertFalse(otp("Free gift! Your code 4821"))
        assertFalse(otp("Special offer, code 884422"))
    }

    // ---- SP-lowA (B8): a domain with another top-level domain ----

    @Test fun domainsWithOtherTopLevelDomainsAreLinks() {
        assertTrue(link("Your code 482913. Verify at secure-bank.help"))
        assertTrue(link("Code 4821, log in at my-bank.support"))
        assertTrue(link("Code 4821 see login24.me"))
        assertTrue(link("Your code 4821. Visit mybank.in"))
        assertTrue(link("Kod 4821, açın: banka-giris.digital"))
    }

    @Test fun aDomainGluedToAPersianWordIsALink() {
        assertTrue(link("رمز پویا ۱۲۳۴۵۶ را در secure-pay.help وارد کنید"))
        assertTrue(link("کد 4821را درsecure-pay.help وارد کنید"))
    }

    @Test fun sentencesWithoutADomainAreNotLinks() {
        assertFalse(link("Your code is 482915.Do not share it"))
        assertFalse(link("Price 1.50/month"))
        assertFalse(link("Hello.How are you"))
        assertFalse(link("Mr.Smith, your code is 4821"))
    }

    // ---- I2: codes in more languages ----

    @Test fun codesInOtherLanguagesAreRecognised() {
        val texts = listOf(
            "Ваш код подтверждения: 482913",
            "Пароль для входа 4821",
            "Doğrulama kodunuz: 482913",
            "Tek kullanımlık şifre 551236",
            "Tu código de verificación es 482913",
            "Su contraseña de un solo uso es 4821",
            "Seu código de verificação: 482913",
            "Il tuo codice di verifica è 482913",
            "Votre code de vérification est 482913",
            "Ihr Bestätigungscode lautet 482913",
            "Dein Passwort: 4821",
            "Kode verifikasi Anda 482913",
            "Mã xác minh của bạn là 482913",
            "קוד אימות: 482913",
            "आपका ओटीपी 482913 है",
            "你的验证码是482913，请勿泄露",
            "【サービス】認証コード：482913",
            "인증번호 [482913]를 입력해 주세요",
            "รหัสยืนยันของคุณคือ 482913",
            "Kod weryfikacyjny: 482913",
            "Je verificatiecode is 482913",
            "ВАШ КОД 482913",
        )
        for (text in texts) assertTrue(text, otp(text))
    }

    @Test fun foreignTextWithoutACodeOrWithAnAdIsNotACode() {
        assertFalse(otp("Ваш заказ 482913 отправлен"))
        assertFalse(otp("Скидка 50% по промокоду 4821"))
        assertFalse(otp("折扣码 482913 限时优惠"))
        assertFalse(otp("Your parcel 482913 has shipped"))
        assertFalse(otp("你好，明天见"))
    }
}
