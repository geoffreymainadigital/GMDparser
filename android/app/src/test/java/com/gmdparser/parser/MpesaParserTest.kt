package com.gmdparser.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MpesaParserTest {

    @Test
    fun testKplcPaybillSms() {
        val sms = "TD47XYZ123 Confirmed. Ksh3,500.00 sent to Kenya Power and Lighting Company for account 12345678 on 7/9/26 at 8:15 PM. New M-PESA balance is Ksh12,450.00. Transaction cost, Ksh23.00."
        val tx = MpesaParser.parse(sms)
        assertNotNull(tx)
        assertEquals("TD47XYZ123", tx?.transactionCode)
        assertEquals(3500.00, tx?.amount ?: 0.0, 0.001)
        assertEquals("Bills", tx?.type)
        assertEquals("Electricity / KPLC", tx?.category)
        assertEquals("2026-09-07", tx?.date)
        assertEquals("8:15 PM", tx?.time)
        assertEquals(12450.00, tx?.balance ?: 0.0, 0.001)
        assertEquals(23.00, tx?.cost ?: 0.0, 0.001)
    }

    @Test
    fun testBuyGoodsTillSms() {
        val sms = "TD48ABC456 Confirmed. Ksh1,250.00 paid to NAIVAS SUPERMARKET. on 7/9/26 at 2:30 PM. New M-PESA balance is Ksh11,200.00. Transaction cost, Ksh0.00."
        val tx = MpesaParser.parse(sms)
        assertNotNull(tx)
        assertEquals("TD48ABC456", tx?.transactionCode)
        assertEquals(1250.00, tx?.amount ?: 0.0, 0.001)
        assertEquals("Expenses", tx?.type)
        assertEquals("Groceries", tx?.category)
        assertEquals("NAIVAS SUPERMARKET", tx?.description)
    }

    @Test
    fun testP2pSendMoneySms() {
        val sms = "TD49DEF789 Confirmed. Ksh2,000.00 sent to JANE DOE 0712345678 on 7/9/26 at 10:05 AM. New M-PESA balance is Ksh9,200.00. Transaction cost, Ksh15.00."
        val tx = MpesaParser.parse(sms)
        assertNotNull(tx)
        assertEquals("TD49DEF789", tx?.transactionCode)
        assertEquals(2000.00, tx?.amount ?: 0.0, 0.001)
        assertEquals("Expenses", tx?.type)
    }

    @Test
    fun testBankTransferSms() {
        val sms = "TD51JKL345 Confirmed. Ksh10,000.00 sent to NCBA LOOP for account 0123456789 on 7/9/26 at 1:15 PM. New M-PESA balance is Ksh49,200.00."
        val tx = MpesaParser.parse(sms)
        assertNotNull(tx)
        assertEquals("TD51JKL345", tx?.transactionCode)
        assertEquals(10000.00, tx?.amount ?: 0.0, 0.001)
        assertEquals("Transfer", tx?.type)
        assertEquals("Bank (NCBA Loop)", tx?.destinationAccount)
    }

    @Test
    fun testEightCharTransactionCode() {
        val sms = "QA12BC34 Confirmed. Ksh500.00 sent to JOHN DOE 0712345678 on 7/9/26 at 9:00 AM. New M-PESA balance is Ksh8,700.00."
        val tx = MpesaParser.parse(sms)
        assertNotNull(tx)
        assertEquals("QA12BC34", tx?.transactionCode)
        assertEquals(500.0, tx?.amount ?: 0.0, 0.001)
    }

    @Test
    fun testTwelveCharTransactionCode() {
        val sms = "ABCDEFGHIJ12 Confirmed. Ksh1,000.00 sent to JANE DOE 0722334455 on 7/9/26 at 11:30 AM. New M-PESA balance is Ksh7,700.00."
        val tx = MpesaParser.parse(sms)
        assertNotNull(tx)
        assertEquals("ABCDEFGHIJ12", tx?.transactionCode)
        assertEquals(1000.0, tx?.amount ?: 0.0, 0.001)
    }

    @Test
    fun testReceivedMoneyIncome() {
        val sms = "TD50GHI012 Confirmed. You have received Ksh50,000.00 from EMPLOYER CO LTD on 7/9/26 at 8:00 AM. New M-PESA balance is Ksh59,200.00."
        val tx = MpesaParser.parse(sms)
        assertNotNull(tx)
        assertEquals("TD50GHI012", tx?.transactionCode)
        assertEquals(50000.00, tx?.amount ?: 0.0, 0.001)
        assertEquals("Income", tx?.type)
        assertEquals("Salary", tx?.category)
    }

    @Test
    fun testWithdrawalToCash() {
        val sms = "TD53PQR901 Confirmed. on 7/9/26 at 4:20 PM Withdraw Ksh3,000.00 from 123456 - AGENT COMMUNICATIONS New M-PESA balance is Ksh44,970.00. Transaction cost, Ksh30.00."
        val tx = MpesaParser.parse(sms)
        assertNotNull(tx)
        assertEquals("TD53PQR901", tx?.transactionCode)
        assertEquals(3000.00, tx?.amount ?: 0.0, 0.001)
        assertEquals("Transfer", tx?.type)
        assertEquals("Cash", tx?.destinationAccount)
    }

    @Test
    fun testPromotionalSmsIgnored() {
        val promo = "Dear Customer, get 500MB for Ksh50 valid for 24hrs. Dial *544#."
        val tx = MpesaParser.parse(promo)
        assertNull(tx)
    }

    @Test
    fun testMalformedSmsHandling() {
        assertNull(MpesaParser.parse(""))
        assertNull(MpesaParser.parse(null))
        assertNull(MpesaParser.parse("Random junk text with no code"))
        assertNull(MpesaParser.parse("SHORT Confirmed. Ksh100 sent to someone."))
    }
}
