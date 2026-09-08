package com.gmdparser.parser

import com.gmdparser.data.model.TaxonomyDefaults
import com.gmdparser.data.model.Transaction
import com.gmdparser.data.model.TransactionPayload
import com.gmdparser.data.repository.TransactionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpesaParserTest {

    // 1. Bills classification (Electricity / KPLC)
    @Test
    fun testBillsClassificationElectricity() {
        val sms = "TD47XYZ123 Confirmed. Ksh3,500.00 sent to Kenya Power and Lighting Company for account 12345678 on 7/9/26 at 8:15 PM. New M-PESA balance is Ksh12,450.00. Transaction cost, Ksh23.00."
        val tx = MpesaParser.parse(sms)
        assertNotNull(tx)
        assertEquals("TD47XYZ123", tx?.transactionCode)
        assertEquals(3500.00, tx?.amount ?: 0.0, 0.001)
        assertEquals("Bills", tx?.type)
        assertEquals("Electricity", tx?.category)
        assertEquals("2026-09-07", tx?.date)
        assertEquals("8:15 PM", tx?.time)
        assertEquals(12450.00, tx?.balance ?: 0.0, 0.001)
        assertEquals(23.00, tx?.cost ?: 0.0, 0.001)
    }

    // 2. Bills classification (Supermarket / Monthly Shopping)
    @Test
    fun testBillsClassificationMonthlyShopping() {
        val sms = "TD48ABC456 Confirmed. Ksh1,250.00 paid to NAIVAS SUPERMARKET. on 7/9/26 at 2:30 PM. New M-PESA balance is Ksh11,200.00. Transaction cost, Ksh0.00."
        val tx = MpesaParser.parse(sms)
        assertNotNull(tx)
        assertEquals("TD48ABC456", tx?.transactionCode)
        assertEquals(1250.00, tx?.amount ?: 0.0, 0.001)
        assertEquals("Bills", tx?.type)
        assertEquals("Monthly Shopping", tx?.category)
        assertEquals("NAIVAS SUPERMARKET", tx?.description)
    }

    // 3. Expense classification (Dining Out / Eating Out)
    @Test
    fun testExpenseClassificationEatingOut() {
        val sms = "TD48KFC999 Confirmed. Ksh1,800.00 paid to KFC DRIVE THRU. on 7/9/26 at 6:30 PM. New M-PESA balance is Ksh9,400.00."
        val tx = MpesaParser.parse(sms)
        assertNotNull(tx)
        assertEquals("Expenses", tx?.type)
        assertEquals("Eating Out", tx?.category)
    }

    // 4. Expense classification (Transport / Fare)
    @Test
    fun testExpenseClassificationFare() {
        val sms = "TD49DEF789 Confirmed. Ksh200.00 sent to SUPER METRO SACCO on 7/9/26 at 10:05 AM. New M-PESA balance is Ksh9,200.00. Transaction cost, Ksh0.00."
        val tx = MpesaParser.parse(sms)
        assertNotNull(tx)
        assertEquals("Expenses", tx?.type)
        assertEquals("Fare", tx?.category)
    }

    // 5. Income classification
    @Test
    fun testIncomeClassification() {
        val sms = "TD50GHI012 Confirmed. You have received Ksh50,000.00 from EMPLOYER CO LTD on 7/9/26 at 8:00 AM. New M-PESA balance is Ksh59,200.00."
        val tx = MpesaParser.parse(sms)
        assertNotNull(tx)
        assertEquals("TD50GHI012", tx?.transactionCode)
        assertEquals(50000.00, tx?.amount ?: 0.0, 0.001)
        assertEquals("Income", tx?.type)
        assertEquals("Salary", tx?.category)
    }

    // 6. Balance classification (Withdrawal to Cash)
    @Test
    fun testBalanceClassificationWithdrawal() {
        val sms = "TD53PQR901 Confirmed. on 7/9/26 at 4:20 PM Withdraw Ksh3,000.00 from 123456 - AGENT COMMUNICATIONS New M-PESA balance is Ksh44,970.00. Transaction cost, Ksh30.00."
        val tx = MpesaParser.parse(sms)
        assertNotNull(tx)
        assertEquals("TD53PQR901", tx?.transactionCode)
        assertEquals(3000.00, tx?.amount ?: 0.0, 0.001)
        assertEquals("Balance", tx?.type)
        assertEquals("", tx?.category)
        assertEquals("Cash", tx?.destinationAccount)
    }

    // 7. Tower Sacco Savings classification
    @Test
    fun testTowerSaccoSavingsClassification() {
        val sms = "TD54TWR123 Confirmed. Ksh5,000.00 sent to TOWER SACCO for account 12345 on 7/9/26 at 11:30 AM. New M-PESA balance is Ksh15,000.00. Transaction cost, Ksh23.00."
        val tx = MpesaParser.parse(sms)
        assertNotNull(tx)
        assertEquals("TD54TWR123", tx?.transactionCode)
        assertEquals(5000.00, tx?.amount ?: 0.0, 0.001)
        assertEquals("Savings", tx?.type)
        assertEquals("Tower Sacco", tx?.category)
        assertEquals("Mpesa", tx?.account)
        assertNull(tx?.destinationAccount)
    }

    // 8. Eastgate Towers NOT Tower Sacco
    @Test
    fun testEastgateTowersNotTowerSacco() {
        val sms = "TD58TWR999 Confirmed. Ksh1,500.00 sent to EASTGATE TOWERS on 7/9/26 at 2:00 PM. New M-PESA balance is Ksh13,500.00. Transaction cost, Ksh15.00."
        val tx = MpesaParser.parse(sms)
        assertNotNull(tx)
        assertNotEquals("Tower Sacco", tx?.category)
        assertNotEquals("Savings", tx?.type)
        assertEquals("Mpesa", tx?.account)
    }

    // 9. NCA Sacco Savings classification
    @Test
    fun testNcaSaccoSavingsClassification() {
        val savingsSms = "TD55NCA123 Confirmed. Ksh4,000.00 sent to NCA SACCO for account 67890 on 7/9/26 at 12:00 PM. New M-PESA balance is Ksh11,000.00. Transaction cost, Ksh23.00."
        val savingsTx = MpesaParser.parse(savingsSms)
        assertNotNull(savingsTx)
        assertEquals("Savings", savingsTx?.type)
        assertEquals("NCA Sacco", savingsTx?.category)
        assertEquals("Mpesa", savingsTx?.account)
    }

    // 10. NCA Sacco Loan Debt classification (vs Savings)
    @Test
    fun testNcaSaccoLoanDebtClassification() {
        val loanSms = "TD56LON123 Confirmed. Ksh2,500.00 sent to NCA SACCO LOAN for account 67890 on 7/9/26 at 12:15 PM. New M-PESA balance is Ksh8,500.00. Transaction cost, Ksh23.00."
        val loanTx = MpesaParser.parse(loanSms)
        assertNotNull(loanTx)
        assertEquals("Debt", loanTx?.type)
        assertEquals("NCA Sacco Loan", loanTx?.category)
        assertEquals("Mpesa", loanTx?.account)
    }

    // 11. Transaction code length validation (8 and 12 chars)
    @Test
    fun testTransactionCodeLengths() {
        val sms8 = "QA12BC34 Confirmed. Ksh500.00 sent to JOHN DOE 0712345678 on 7/9/26 at 9:00 AM. New M-PESA balance is Ksh8,700.00."
        val tx8 = MpesaParser.parse(sms8)
        assertNotNull(tx8)
        assertEquals("QA12BC34", tx8?.transactionCode)

        val sms12 = "ABCDEFGHIJ12 Confirmed. Ksh1,000.00 sent to JANE DOE 0722334455 on 7/9/26 at 11:30 AM. New M-PESA balance is Ksh7,700.00."
        val tx12 = MpesaParser.parse(sms12)
        assertNotNull(tx12)
        assertEquals("ABCDEFGHIJ12", tx12?.transactionCode)
    }

    // 12. Promotional SMS ignored
    @Test
    fun testPromotionalSmsIgnored() {
        val promo = "Dear Customer, get 500MB for Ksh50 valid for 24hrs. Dial *544#."
        val tx = MpesaParser.parse(promo)
        assertNull(tx)
    }

    // 13. Manual balance transfer with Tower Sacco
    @Test
    fun testManualBalanceTransactionWithTowerSacco() {
        val tx = MpesaParser.createManualBalanceTransaction(
            sourceAccount = "Tower Sacco",
            destinationAccount = "Mpesa",
            amount = 10000.0,
            transactionCode = "BAL-TWR-001"
        )
        assertNotNull(tx)
        assertEquals("BAL-TWR-001", tx.transactionCode)
        assertEquals(10000.0, tx.amount, 0.001)
        assertEquals("Balance", tx.type)
        assertEquals("Tower Sacco", tx.account)
        assertEquals("Mpesa", tx.destinationAccount)
    }

    // 14. Immutable Invariant: AutoSync is strictly false
    @Test
    fun testAutoSyncPermanentlyDisabled() {
        assertFalse(TransactionRepository.isAutoSync())
    }

    // 15. Taxonomy validation: Real categories only
    @Test
    fun testTaxonomyValidationRealCategories() {
        assertTrue(TaxonomyDefaults.TYPES.contains("Expenses"))
        assertTrue(TaxonomyDefaults.TYPES.contains("Savings"))
        assertTrue(TaxonomyDefaults.TYPES.contains("Bills"))
        assertTrue(TaxonomyDefaults.TYPES.contains("Debt"))
        assertTrue(TaxonomyDefaults.TYPES.contains("Income"))
        assertTrue(TaxonomyDefaults.TYPES.contains("Balance"))

        val expenseCategories = TaxonomyDefaults.CATEGORIES_BY_TYPE["Expenses"] ?: emptyList()
        assertTrue(expenseCategories.contains("Mama Mboga"))
        assertTrue(expenseCategories.contains("Black Tax"))
        assertTrue(expenseCategories.contains("Eating Out"))
        assertTrue(expenseCategories.contains("Fare"))
        assertFalse(expenseCategories.contains("Groceries")) // Not in sheet

        val accounts = TaxonomyDefaults.ACCOUNTS
        assertTrue(accounts.contains("Mpesa"))
        assertTrue(accounts.contains("Equity Bank"))
        assertTrue(accounts.contains("I&M Bank"))
        assertTrue(accounts.contains("Tower Sacco"))
    }

    // 16. Write payload contains only raw input-column fields
    @Test
    fun testWritePayloadContainsOnlyRawInputFields() {
        val payload = TransactionPayload(
            date = "2026-09-08",
            type = "Expenses",
            category = "Mama Mboga",
            description = "Market purchases",
            amount = 450.0,
            account = "Mpesa",
            transactionCode = "TD47XYZ123"
        )
        // Confirm fields match input columns (Date, Type, Category, Description, Amount, Account, Code)
        assertEquals("2026-09-08", payload.date)
        assertEquals("Expenses", payload.type)
        assertEquals("Mama Mboga", payload.category)
        assertEquals("Market purchases", payload.description)
        assertEquals(450.0, payload.amount, 0.001)
        assertEquals("Mpesa", payload.account)
        assertEquals("TD47XYZ123", payload.transactionCode)
    }

    // 17. Airtime purchase parsing
    @Test
    fun testAirtimePurchaseParsing() {
        val sms = "UI8G46M0VD Confirmed. You bought Ksh20.00 of airtime on 8/9/26 at 9:20 AM. New M-PESA balance is Ksh150.00."
        val tx = MpesaParser.parse(sms)
        assertNotNull(tx)
        assertEquals("UI8G46M0VD", tx?.transactionCode)
        assertEquals(20.0, tx?.amount ?: 0.0, 0.001)
        assertEquals("Expenses", tx?.type)
        assertEquals("Bundles", tx?.category)
        assertEquals("Mpesa", tx?.account)
    }

    // 18. Airtime for other recipient
    @Test
    fun testAirtimeForRecipientParsing() {
        val sms = "UI8G46M0VD Confirmed. You bought Ksh50.00 of airtime for 0712345678 on 8/9/26 at 11:45 AM. New M-PESA balance is Ksh200.00."
        val tx = MpesaParser.parse(sms)
        assertNotNull(tx)
        assertEquals("UI8G46M0VD", tx?.transactionCode)
        assertEquals(50.0, tx?.amount ?: 0.0, 0.001)
        assertEquals("Expenses", tx?.type)
        assertEquals("Bundles", tx?.category)
    }

    // 19. Space in currency format (e.g. Ksh 2.00)
    @Test
    fun testCurrencyWithSpaceParsing() {
        val sms = "UI8G46M3FH Confirmed. Ksh 5.00 sent to DENNIS MOSE 0757662366 on 8/9/26 at 10:00 AM. New M-PESA balance is Ksh2,685.10."
        val tx = MpesaParser.parse(sms)
        assertNotNull(tx)
        assertEquals("UI8G46M3FH", tx?.transactionCode)
        assertEquals(5.0, tx?.amount ?: 0.0, 0.001)
        assertEquals("Expenses", tx?.type)
    }

    // 20. Fallback extraction ensures amount is never 0.0
    @Test
    fun testFallbackExtractionNeverZeroAmount() {
        val sms = "UI8G46M0VD Confirmed. Ksh2.00 transferred via M-PESA to merchant XYZ on 8/9/26 at 9:20 AM."
        val tx = MpesaParser.parse(sms)
        assertNotNull(tx)
        assertEquals("UI8G46M0VD", tx?.transactionCode)
        assertEquals(2.0, tx?.amount ?: 0.0, 0.001)
        assertEquals("Expenses", tx?.type)
    }
}
