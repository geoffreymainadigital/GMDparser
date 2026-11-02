package com.gmdparser.parser

import com.gmdparser.data.model.Transaction
import com.gmdparser.data.model.TransactionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Deterministic parser for Equity Bank Kenya transaction SMS messages.
 */
object EquityParser {

    // 1. Equity to M-PESA transfer:
    // "Your transaction of Kshs. 14000.0  has been credited to 254728118706 GEOFFREY KIBUCHI MAINA. Ref. EQAF2CAE012FD91. MPESA Ref.  UJMG406C9F on 22/10/2026 at 12:47:33."
    private val EQUITY_TO_MPESA_REGEX = Regex(
        "Your transaction of (?:Kshs?\\.?|KES)\\s*([\\d,]+\\.?\\d*)\\s+has been credited to\\s+(\\d+)\\s+(.+?)\\.\\s*Ref\\.\\s*([A-Z0-9]+)\\.\\s*MPESA Ref\\.?\\s*([A-Z0-9]+)\\s+on\\s+(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})\\s+at\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*(?:AM|PM))?)",
        RegexOption.IGNORE_CASE
    )

    // 2. Loan payment:
    // "Confirmed. KES 186.00 has been successfully paid to your loan account 016OD00003404 from account 0160***7370 on 22-Oct-2026. Reference OJV15KEA9F121"
    private val LOAN_PAYMENT_REGEX = Regex(
        "Confirmed\\.\\s*(?:KES|Kshs?\\.?)\\s*([\\d,]+\\.?\\d*)\\s+has been successfully paid to your loan account\\s+([A-Z0-9]+)\\s+from account\\s+([^\\s]+)\\s+on\\s+(\\d{1,2}[-\\s][A-Za-z]{3,}[-\\s]\\d{2,4})\\.\\s*Reference\\s+([A-Z0-9]+)",
        RegexOption.IGNORE_CASE
    )

    // 3. Sent to another bank (e.g. I&M Bank)
    // "37626.00 KES has been successfully sent to Geoffrey Kibuchi Maina 0*********3050 INVESTMENT & MORGAGES BANK. Ref. A4894D26FDB0C on 24 Oct 2026 at 17:28 EAT. Charges 59.76 KES"
    private val SENT_TO_BANK_REGEX = Regex(
        "([\\d,]+\\.?\\d*)\\s*(?:KES|Kshs?\\.?)\\s+has been successfully sent to\\s+(.+?)\\.\\s*Ref\\.\\s*([A-Z0-9]+)\\s+on\\s+(\\d{1,2}[-\\s][A-Za-z]{3,}[-\\s]\\d{2,4})\\s+at\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*(?:AM|PM))?)(?:\\s+EAT)?(?:\\.\\s*Charges\\s*([\\d,]+\\.?\\d*)\\s*(?:KES|Kshs?\\.?))?",
        RegexOption.IGNORE_CASE
    )

    // 4. Credited to account (Salary/Deposit)
    // "Dear GEOFFREY KES 16871.85 has been credited to your account 0160......7370 Ref:S5668008 on 02-NOV-2026 13:16:17 PM. For any queries, contact 0763000000"
    private val ACCOUNT_CREDITED_REGEX = Regex(
        "Dear\\s+([A-Za-z]+)\\s+(?:KES|Kshs?\\.?)\\s*([\\d,]+\\.?\\d*)\\s+has been credited to your account\\s+([^\\s]+)\\s+Ref:\\s*([A-Z0-9]+)\\s+on\\s+(\\d{1,2}[-\\s][A-Za-z]{3,}[-\\s]\\d{2,4})\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*(?:AM|PM))?)",
        RegexOption.IGNORE_CASE
    )

    // 5. Drawn from account (Debit / Expense)
    // "Dear GEOFFREY KES 745.00 has been drawn from your account 0160......7370 Ref:S5674716 on 02-NOV-2026 13:17:02 PM. For any queries, contact 0763000000"
    private val ACCOUNT_DRAWN_REGEX = Regex(
        "Dear\\s+([A-Za-z]+)\\s+(?:KES|Kshs?\\.?)\\s*([\\d,]+\\.?\\d*)\\s+has been drawn from your account\\s+([^\\s]+)\\s+Ref:\\s*([A-Z0-9]+)\\s+on\\s+(\\d{1,2}[-\\s][A-Za-z]{3,}[-\\s]\\d{2,4})\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*(?:AM|PM))?)",
        RegexOption.IGNORE_CASE
    )

    fun parse(smsBody: String?): Transaction? {
        if (smsBody.isNullOrBlank()) return null
        val trimmed = smsBody.trim()

        // 1. Equity to M-PESA
        val mpesaMatch = EQUITY_TO_MPESA_REGEX.find(trimmed)
        if (mpesaMatch != null) {
            val amount = mpesaMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
            val phone = mpesaMatch.groupValues[2].trim()
            val recipient = mpesaMatch.groupValues[3].trim()
            val equityRef = mpesaMatch.groupValues[4].trim()
            val mpesaRef = mpesaMatch.groupValues[5].trim()
            val dateStr = mpesaMatch.groupValues[6].trim()
            val timeStr = mpesaMatch.groupValues[7].trim()

            val txCode = if (mpesaRef.isNotBlank()) mpesaRef else equityRef

            return Transaction(
                transactionCode = txCode,
                amount = amount,
                type = "Balance",
                category = "",
                description = "Transfer to Mpesa ($phone $recipient)".trim(),
                account = "Equity Bank",
                date = normalizeDate(dateStr),
                time = timeStr,
                destinationAccount = "Mpesa",
                balance = null,
                cost = 0.0,
                senderOrRecipient = recipient,
                rawText = trimmed,
                status = TransactionStatus.PENDING_REVIEW
            )
        }

        // 2. Loan payment
        val loanMatch = LOAN_PAYMENT_REGEX.find(trimmed)
        if (loanMatch != null) {
            val amount = loanMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
            val loanAccount = loanMatch.groupValues[2].trim()
            val fromAccount = loanMatch.groupValues[3].trim()
            val dateStr = loanMatch.groupValues[4].trim()
            val reference = loanMatch.groupValues[5].trim()

            return Transaction(
                transactionCode = reference,
                amount = amount,
                type = "Debt",
                category = "Equity Loan",
                description = "Loan payment: $loanAccount from $fromAccount".trim(),
                account = "Equity Bank",
                date = normalizeDate(dateStr),
                time = "",
                destinationAccount = null,
                balance = null,
                cost = 0.0,
                senderOrRecipient = loanAccount,
                rawText = trimmed,
                status = TransactionStatus.PENDING_REVIEW
            )
        }

        // 3. Sent to another bank (e.g. I&M Bank)
        val sentBankMatch = SENT_TO_BANK_REGEX.find(trimmed)
        if (sentBankMatch != null) {
            val amount = sentBankMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
            val targetRaw = sentBankMatch.groupValues[2].trim()
            val reference = sentBankMatch.groupValues[3].trim()
            val dateStr = sentBankMatch.groupValues[4].trim()
            val timeStr = sentBankMatch.groupValues[5].trim()
            val charges = sentBankMatch.groupValues[6].replace(",", "").toDoubleOrNull() ?: 0.0

            val destAccount = resolveDestinationBank(targetRaw)

            return Transaction(
                transactionCode = reference,
                amount = amount,
                type = "Balance",
                category = "",
                description = "Sent to $targetRaw".trim(),
                account = "Equity Bank",
                date = normalizeDate(dateStr),
                time = timeStr,
                destinationAccount = destAccount,
                balance = null,
                cost = charges,
                senderOrRecipient = targetRaw,
                rawText = trimmed,
                status = TransactionStatus.PENDING_REVIEW
            )
        }

        // 4. Credited to account
        val creditedMatch = ACCOUNT_CREDITED_REGEX.find(trimmed)
        if (creditedMatch != null) {
            val amount = creditedMatch.groupValues[2].replace(",", "").toDoubleOrNull() ?: 0.0
            val accountNum = creditedMatch.groupValues[3].trim()
            val reference = creditedMatch.groupValues[4].trim()
            val dateStr = creditedMatch.groupValues[5].trim()
            val timeStr = creditedMatch.groupValues[6].trim()

            return Transaction(
                transactionCode = reference,
                amount = amount,
                type = "Income",
                category = "Salary",
                description = "Credited to account $accountNum".trim(),
                account = "Equity Bank",
                date = normalizeDate(dateStr),
                time = timeStr,
                destinationAccount = null,
                balance = null,
                cost = 0.0,
                senderOrRecipient = "Equity Bank",
                rawText = trimmed,
                status = TransactionStatus.PENDING_REVIEW
            )
        }

        // 5. Drawn from account
        val drawnMatch = ACCOUNT_DRAWN_REGEX.find(trimmed)
        if (drawnMatch != null) {
            val amount = drawnMatch.groupValues[2].replace(",", "").toDoubleOrNull() ?: 0.0
            val accountNum = drawnMatch.groupValues[3].trim()
            val reference = drawnMatch.groupValues[4].trim()
            val dateStr = drawnMatch.groupValues[5].trim()
            val timeStr = drawnMatch.groupValues[6].trim()

            return Transaction(
                transactionCode = reference,
                amount = amount,
                type = "Expenses",
                category = "",
                description = "Drawn from account $accountNum".trim(),
                account = "Equity Bank",
                date = normalizeDate(dateStr),
                time = timeStr,
                destinationAccount = null,
                balance = null,
                cost = 0.0,
                senderOrRecipient = "Equity Bank",
                rawText = trimmed,
                status = TransactionStatus.PENDING_REVIEW
            )
        }

        return null
    }

    private fun resolveDestinationBank(target: String): String {
        val t = target.uppercase(Locale.ROOT)
        return when {
            t.contains("INVESTMENT & MORGAGES") || t.contains("INVESTMENT & MORTGAGES") || t.contains("I&M") || t.contains("I AND M") -> "I&M Bank"
            t.contains("COOP") || t.contains("CO-OPERATIVE") -> "Coop Bank"
            t.contains("MPESA") || t.contains("M-PESA") -> "Mpesa"
            t.contains("TOWER") -> "Tower Sacco"
            else -> "I&M Bank"
        }
    }

    fun normalizeDate(rawDate: String): String {
        val trimmed = rawDate.trim()

        // Match DD/MM/YYYY or D/M/YY
        if (trimmed.contains("/")) {
            val parts = trimmed.split("/")
            if (parts.size == 3) {
                val day = parts[0].padStart(2, '0')
                val month = parts[1].padStart(2, '0')
                var year = parts[2]
                if (year.length == 2) year = "20$year"
                return "$year-$month-$day"
            }
        }

        // Match "22-Oct-2026" or "24 Oct 2026" or "02-NOV-2026"
        val namedMonthRegex = Regex("(\\d{1,2})[-\\s]([A-Za-z]{3,})[-\\s](\\d{2,4})")
        val match = namedMonthRegex.find(trimmed)
        if (match != null) {
            val day = match.groupValues[1].padStart(2, '0')
            val monthStr = match.groupValues[2].uppercase(Locale.ROOT)
            var year = match.groupValues[3]
            if (year.length == 2) year = "20$year"

            val month = when {
                monthStr.startsWith("JAN") -> "01"
                monthStr.startsWith("FEB") -> "02"
                monthStr.startsWith("MAR") -> "03"
                monthStr.startsWith("APR") -> "04"
                monthStr.startsWith("MAY") -> "05"
                monthStr.startsWith("JUN") -> "06"
                monthStr.startsWith("JUL") -> "07"
                monthStr.startsWith("AUG") -> "08"
                monthStr.startsWith("SEP") -> "09"
                monthStr.startsWith("OCT") -> "10"
                monthStr.startsWith("NOV") -> "11"
                monthStr.startsWith("DEC") -> "12"
                else -> "01"
            }
            return "$year-$month-$day"
        }

        return SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
    }
}

