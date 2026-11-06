package com.glooboo.umessage.spam.mms

import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MmsPushGateTest {

    private fun ByteArrayOutputStream.bytes(vararg b: Int) = b.forEach { write(it) }
    private fun ByteArrayOutputStream.text(s: String) {
        write(s.toByteArray(Charsets.ISO_8859_1)); write(0)
    }

    /**
     * Hand-built WSP encoded m-notification-ind (OMA MMS 1.0 encapsulation, as seen in WAP push data). [expiry] is
     * the X-Mms-Expiry value: relative 65535 s by default.
     */
    private fun notificationInd(
        from: String?,
        transactionId: String = "T1234",
        expiry: IntArray = intArrayOf(0x88, 0x04, 0x81, 0x02, 0xFF, 0xFF),
    ): ByteArray {
        val o = ByteArrayOutputStream()
        o.bytes(0x8C, 0x82)                       // X-Mms-Message-Type: m-notification-ind
        o.bytes(0x98); o.text(transactionId)      // X-Mms-Transaction-Id
        o.bytes(0x8D, 0x90)                       // X-Mms-MMS-Version: 1.0
        if (from != null) {
            val enc = from.toByteArray(Charsets.ISO_8859_1)
            o.bytes(0x89, enc.size + 2, 0x80)     // From: value-length, address-present-token
            o.write(enc); o.write(0)
        }
        o.bytes(0x8A, 0x80)                       // X-Mms-Message-Class: personal
        o.bytes(0x8E, 0x02, 0x04, 0x00)           // X-Mms-Message-Size: 1024
        o.bytes(*expiry)                          // X-Mms-Expiry
        o.bytes(0x83); o.text("http://mmsc.example.com/msg/abc")  // X-Mms-Content-Location
        return o.toByteArray()
    }

    @Test
    fun extractsSenderAndStripsPlmnSuffix() {
        val info = MmsPushGate.parse(notificationInd("+15551234567/TYPE=PLMN"))
        assertNotNull(info)
        assertTrue(info!!.isNotification)
        assertEquals("+15551234567", info.sender)
        assertEquals("T1234", info.transactionId)
    }

    @Test
    fun missingFromGivesEmptySender() {
        val info = MmsPushGate.parse(notificationInd(null))
        assertEquals("", info!!.sender)
    }

    @Test
    fun garbageAndNullAreNotParsed() {
        assertNull(MmsPushGate.parse(null))
        assertNull(MmsPushGate.parse(ByteArray(0)))
        assertNull(MmsPushGate.parse(byteArrayOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun cleanAddressHandlesVariants() {
        assertEquals("+4912345", MmsPushGate.cleanAddress("+4912345/TYPE=PLMN"))
        assertEquals("a@b.com", MmsPushGate.cleanAddress(" a@b.com "))
    }

    private fun block() = FilterDecision.Block(DecisionReason.UNKNOWN_SENDER)

    @Test
    fun blockDecisionBlocksWithPlaceholderAndSubscription() {
        var seen: IncomingMessage? = null
        val action = MmsPushGate.decide(notificationInd("+15551234567/TYPE=PLMN"), 3, 1000L) { seen = it; block() }
        assertTrue(action is MmsPushAction.Block)
        action as MmsPushAction.Block
        assertEquals("+15551234567", action.placeholder.address)
        assertEquals(MmsPushGate.MMS_BLOCKED_BODY, action.placeholder.body)
        assertEquals(3, action.placeholder.subscriptionId)
        assertTrue(action.placeholder.isMms)
        assertEquals(1000L, action.placeholder.receivedAt)
        assertEquals("T1234", action.transactionId)
        assertEquals("", seen!!.body)
        assertEquals(3, seen!!.subscriptionId)
    }

    /** SE-29: a blocked MMS keeps where it can be downloaded from, so uMessage can keep its pictures. */
    @Test
    fun blockKeepsTheDownloadLocationWhileTheMmsIsAvailable() {
        val now = System.currentTimeMillis()
        val info = MmsPushGate.parse(notificationInd("+15551234567"))!!
        assertEquals("http://mmsc.example.com/msg/abc", info.contentLocation)
        assertTrue(info.expiresAt > now)

        val action = MmsPushGate.decide(notificationInd("+15551234567"), 1, now) { block() } as MmsPushAction.Block
        assertEquals("http://mmsc.example.com/msg/abc", action.contentLocation)
    }

    @Test
    fun blockOfAnExpiredMmsHasNothingToDownload() {
        // Absolute expiry one second after the epoch: long gone.
        val expired = notificationInd("+15551234567", expiry = intArrayOf(0x88, 0x03, 0x80, 0x01, 0x01))
        val action = MmsPushGate.decide(expired, 1, System.currentTimeMillis()) { block() } as MmsPushAction.Block
        assertEquals("", action.contentLocation)
    }

    @Test
    fun allowDecisionDelivers() {
        val action = MmsPushGate.decide(notificationInd("+15551234567"), 1, 0L) { FilterDecision.Allow(DecisionReason.CONTACT) }
        assertEquals(MmsPushAction.Deliver("CONTACT"), action)
    }

    @Test
    fun filterFailureFailsOpen() {
        val action = MmsPushGate.decide(notificationInd("+15551234567"), 1, 0L) { null }
        assertTrue(action is MmsPushAction.Deliver)
    }

    @Test
    fun missingSenderAndUnparseableDeliverWithoutAskingFilter() {
        var called = false
        val eval: (IncomingMessage) -> FilterDecision? = { called = true; block() }
        assertTrue(MmsPushGate.decide(notificationInd(null), 1, 0L, eval) is MmsPushAction.Deliver)
        assertTrue(MmsPushGate.decide(byteArrayOf(9, 9, 9), 1, 0L, eval) is MmsPushAction.Deliver)
        assertTrue(MmsPushGate.decide(null, 1, 0L, eval) is MmsPushAction.Deliver)
        assertEquals(false, called)
    }

    @Test
    fun nonNotificationPduIsDelivered() {
        // m-delivery-ind style header only: type 0x86 without mandatory fields parses to null -> Deliver either way
        val o = ByteArrayOutputStream()
        o.write(0x8C); o.write(0x86)
        val action = MmsPushGate.decide(o.toByteArray(), 1, 0L) { block() }
        assertTrue(action is MmsPushAction.Deliver)
    }
}
