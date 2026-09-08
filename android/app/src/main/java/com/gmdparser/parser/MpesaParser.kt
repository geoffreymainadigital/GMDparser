package com.gmdparser.parser

import com.gmdparser.data.model.Transaction
import com.gmdparser.data.model.TransactionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MpesaParser {

    private val CONFIRMED_CODE_REGEX = Regex("^([A-Z0-9]{8,12})\\s+Confirmed[\\.\\s]", RegexOption.IGNORE_CASE)
    private val BALANCE_REGEX = Regex("New M-PESA balance is (?:Ksh|KES)\\.?\\s*([\\d,]+\\.?\\d*)", RegexOption.IGNORE_CASE)
    private val COST_REGEX = Regex("Transaction cost,?\\s*(?:Ksh|KES)\\.?\\s*([\\d,]+\\.?\\d*)", RegexOption.IGNORE_CASE)

    // 1. Sent to (Paybill, Send Money, Bank)
    private val SENT_REGEX = Regex(
        "(?:Ksh|KES)\\.?\\s*([\\d,]+\\.?\\d*)\\s+sent to\\s+(.+?)\\s+on\\s+(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})\\s+at\\s+(\\d{1,2}:\\d{2}\\s*(?:AM|PM)?)",
        RegexOption.IGNORE_CASE
    )

    // 2. Paid to (Buy Goods / Till)
    private val PAID_REGEX = Regex(
        "(?:Ksh|KES)\\.?\\s*([\\d,]+\\.?\\d*)\\s+paid to\\s+(.+?)(?:\\.|\\s+on)\\s+(?:on\\s+)?(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})\\s+at\\s+(\\d{1,2}:\\d{2}\\s*(?:AM|PM)?)",
        RegexOption.IGNORE_CASE
    )

    // 3. Received from
    private val RECEIVED_REGEX = Regex(
        "You have received\\s+(?:Ksh|KES)\\.?\\s*([\\d,]+\\.?\\d*)\\s+from\\s+(.+?)\\s+on\\s+(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})\\s+at\\s+(\\d{1,2}:\\d{2}\\s*(?:AM|PM)?)",
        RegexOption.IGNORE_CASE
    )

    // 4. Withdrawal from agent
    private val WITHDRAW_REGEX = Regex(
        "on\\s+(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})\\s+at\\s+(\\d{1,2}:\\d{2}\\s*(?:AM|PM)?)\\s+Withdraw\\s+(?:Ksh|KES)\\.?\\s*([\\d,]+\\.?\\d*)\\s+from\\s+(.+?)(?:\\s+New|\\.|$)",
        RegexOption.IGNORE_CASE
    )

    // 5. Airtime / Bundles purchase
    private val AIRTIME_REGEX = Regex(
        "(?:You\\s+(?:have\\s+)?bought|bought)\\s+(?:Ksh|KES)\\.?\\s*([\\d,]+\\.?\\d*)\\s+of\\s+(?:airtime|data(?:\\s+bundles?)?|bundles)(?:\\s+for\\s+(.+?))?\\s+on\\s+(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})\\s+at\\s+(\\d{1,2}:\\d{2}\\s*(?:AM|PM)?)",
        RegexOption.IGNORE_CASE
    )

    fun parse(smsBody: String?): Transaction? {
        if (smsBody.isNullOrBlank()) return null
        val trimmed = smsBody.trim()

        val codeMatch = CONFIRMED_CODE_REGEX.find(trimmed) ?: return null
        val txCode = codeMatch.groupValues[1].uppercase(Locale.ROOT)

        val balance = BALANCE_REGEX.find(trimmed)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        val cost = COST_REGEX.find(trimmed)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

        // 1. Sent to (Paybill, Send Money, Bank Transfer)
        val sentMatch = SENT_REGEX.find(trimmed)
        if (sentMatch != null) {
            val amount = sentMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
            var targetRaw = sentMatch.groupValues[2].trim()
            val dateStr = sentMatch.groupValues[3]
            val timeStr = sentMatch.groupValues[4]

            var accountRef: String? = null
            val acctMatch = Regex("^(.+?)\\s+for account\\s+(.+)$", RegexOption.IGNORE_CASE).find(targetRaw)
            if (acctMatch != null) {
                targetRaw = acctMatch.groupValues[1].trim()
                accountRef = acctMatch.groupValues[2].trim()
            }

            var phone: String? = null
            val phoneMatch = Regex("^(.+?)\\s+(07\\d{8}|01\\d{8}|\\+?254\\d{9})$").find(targetRaw)
            if (phoneMatch != null) {
                targetRaw = phoneMatch.groupValues[1].trim()
                phone = phoneMatch.groupValues[2].trim()
            }

            val classification = classifySentTarget(targetRaw, accountRef)

            return Transaction(
                transactionCode = txCode,
                amount = amount,
                type = classification.type,
                category = classification.category,
                description = targetRaw + if (!accountRef.isNullOrBlank()) " ($accountRef)" else "",
                account = "Mpesa",
                date = formatIsoDate(dateStr),
                time = timeStr,
                destinationAccount = classification.destinationAccount,
                balance = balance,
                cost = cost,
                senderOrRecipient = targetRaw,
                rawText = trimmed,
                status = TransactionStatus.PENDING_REVIEW
            )
        }

        // 2. Paid to (Buy Goods / Till)
        val paidMatch = PAID_REGEX.find(trimmed)
        if (paidMatch != null) {
            val amount = paidMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
            val merchant = paidMatch.groupValues[2].trim().removeSuffix(".")
            val dateStr = paidMatch.groupValues[3]
            val timeStr = paidMatch.groupValues[4]

            val classification = classifyMerchant(merchant)

            return Transaction(
                transactionCode = txCode,
                amount = amount,
                type = classification.type,
                category = classification.category,
                description = merchant,
                account = "Mpesa",
                date = formatIsoDate(dateStr),
                time = timeStr,
                destinationAccount = null,
                balance = balance,
                cost = cost,
                senderOrRecipient = merchant,
                rawText = trimmed,
                status = TransactionStatus.PENDING_REVIEW
            )
        }

        // 3. Received from (Income)
        val receivedMatch = RECEIVED_REGEX.find(trimmed)
        if (receivedMatch != null) {
            val amount = receivedMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
            var sender = receivedMatch.groupValues[2].trim()
            val dateStr = receivedMatch.groupValues[3]
            val timeStr = receivedMatch.groupValues[4]

            val phoneMatch = Regex("^(.+?)\\s+(07\\d{8}|01\\d{8}|\\+?254\\d{9})$").find(sender)
            if (phoneMatch != null) {
                sender = phoneMatch.groupValues[1].trim()
            }

            return Transaction(
                transactionCode = txCode,
                amount = amount,
                type = "Income",
                category = "Salary",
                description = "Received from $sender",
                account = "Mpesa",
                date = formatIsoDate(dateStr),
                time = timeStr,
                destinationAccount = null,
                balance = balance,
                cost = 0.0,
                senderOrRecipient = sender,
                rawText = trimmed,
                status = TransactionStatus.PENDING_REVIEW
            )
        }

        // 4. Withdrawal from agent
        val withdrawMatch = WITHDRAW_REGEX.find(trimmed)
        if (withdrawMatch != null) {
            val dateStr = withdrawMatch.groupValues[1]
            val timeStr = withdrawMatch.groupValues[2]
            val amount = withdrawMatch.groupValues[3].replace(",", "").toDoubleOrNull() ?: 0.0
            val agent = withdrawMatch.groupValues[4].trim()

            return Transaction(
                transactionCode = txCode,
                amount = amount,
                type = "Balance",
                category = "",
                description = "Withdrawal: $agent",
                account = "Mpesa",
                date = formatIsoDate(dateStr),
                time = timeStr,
                destinationAccount = "Cash",
                balance = balance,
                cost = cost,
                senderOrRecipient = agent,
                rawText = trimmed,
                status = TransactionStatus.PENDING_REVIEW
            )
        }

        // 5. Airtime or Bundles purchase
        val airtimeMatch = AIRTIME_REGEX.find(trimmed)
        if (airtimeMatch != null) {
            val amount = airtimeMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
            val recipient = airtimeMatch.groupValues[2].trim().ifEmpty { "Airtime" }
            val dateStr = airtimeMatch.groupValues[3]
            val timeStr = airtimeMatch.groupValues[4].trim()

            return Transaction(
                transactionCode = txCode,
                amount = amount,
                type = "Expenses",
                category = "Bundles",
                description = "Airtime for $recipient".trim(),
                account = "Mpesa",
                date = formatIsoDate(dateStr),
                time = timeStr,
                destinationAccount = null,
                balance = balance,
                cost = cost ?: 0.0,
                senderOrRecipient = recipient,
                rawText = trimmed,
                status = TransactionStatus.PENDING_REVIEW
            )
        }

        // 6. Smart fallback: extract amount, date, time and recipient from confirmed M-PESA SMS
        val fallbackAmountMatch = Regex("(?:Ksh|KES)\\.?\\s*([\\d,]+\\.?\\d*)", RegexOption.IGNORE_CASE).find(trimmed)
        val extractedAmount = fallbackAmountMatch?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: 0.0

        val fallbackDateMatch = Regex("(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})").find(trimmed)
        val extractedDate = fallbackDateMatch?.groupValues?.get(1)?.let { formatIsoDate(it) }
            ?: SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())

        val fallbackTimeMatch = Regex("(\\d{1,2}:\\d{2}\\s*(?:AM|PM)?)", RegexOption.IGNORE_CASE).find(trimmed)
        val extractedTime = fallbackTimeMatch?.groupValues?.get(1)?.trim() ?: ""

        val payeeMatch = Regex("(?:sent to|paid to|from|bought)\\s+([A-Za-z0-9\\s\\.\\-]+?)(?:\\s+on|\\s+for account|\\s+New M-PESA|\\.|,|$)", RegexOption.IGNORE_CASE).find(trimmed)
        val extractedDesc = payeeMatch?.groupValues?.get(1)?.trim()?.ifEmpty { "M-PESA Transaction" } ?: "M-PESA Transaction"

        val classification = classifySentTarget(extractedDesc, null)

        return Transaction(
            transactionCode = txCode,
            amount = extractedAmount,
            type = classification.type,
            category = classification.category,
            description = extractedDesc,
            account = "Mpesa",
            date = extractedDate,
            time = extractedTime,
            destinationAccount = null,
            balance = balance,
            cost = cost,
            senderOrRecipient = extractedDesc,
            rawText = trimmed,
            status = TransactionStatus.PENDING_REVIEW
        )
    }

    fun createManualBalanceTransaction(
        sourceAccount: String,
        destinationAccount: String,
        amount: Double,
        transactionCode: String? = null,
        date: String? = null
    ): Transaction {
        val code = transactionCode ?: "BAL-${System.currentTimeMillis()}"
        val today = date ?: SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        return Transaction(
            transactionCode = code,
            amount = amount,
            type = "Balance",
            category = "",
            description = "Transfer from $sourceAccount to $destinationAccount",
            account = sourceAccount,
            destinationAccount = destinationAccount,
            date = today,
            status = TransactionStatus.PENDING_REVIEW
        )
    }

    private data class Classification(val type: String, val category: String, val destinationAccount: String? = null)

    private fun classifySentTarget(target: String, accountRef: String?): Classification {
        val t = target.lowercase(Locale.ROOT)
        val a = accountRef?.lowercase(Locale.ROOT) ?: ""

        // 1. Debt & Loans
        if (t.contains("equity loan") || (t.contains("equity") && (t.contains("loan") || a.contains("loan")))) {
            return Classification("Debt", "Equity Loan")
        }
        if (t.contains("nca sacco loan") || (t.contains("nca sacco") && (t.contains("loan") || a.contains("loan")))) {
            return Classification("Debt", "NCA Sacco Loan")
        }
        if (t.contains("helb")) {
            return Classification("Debt", "Helb Loan")
        }
        if (t.contains("fuliza") || t.contains("m-shwari") || t.contains("tala") || t.contains("branch")) {
            return Classification("Debt", "Equity Loan")
        }

        // 2. Specific Savings Institutions
        // Tower Sacco: Payment to Tower Sacco via SMS is a Savings contribution funded from Mpesa
        if (t.contains("tower sacco")) {
            return Classification("Savings", "Tower Sacco", null)
        }
        // NCA Sacco: Payment to NCA Sacco (without loan) is a Savings contribution
        if (t.contains("nca sacco")) {
            return Classification("Savings", "NCA Sacco", null)
        }
        if (t.contains("sanlam")) {
            return Classification("Savings", "Sanlam MMF", null)
        }
        if (t.contains("britam")) {
            return Classification("Savings", "Britam EQ and MMF", null)
        }
        if (t.contains("etica")) {
            return Classification("Savings", "Etica MMF", null)
        }
        if (t.contains("ziidi")) {
            return Classification("Savings", "Ziidi MMF", null)
        }
        if (t.contains("cic") || t.contains("mmf") || t.contains("money market")) {
            return Classification("Savings", "Sanlam MMF", null)
        }

        // 3. Matatu Transport Saccos (Expenses -> Fare)
        if (t.contains("super metro") || t.contains("2nk") || t.contains("lopha") || t.contains("metro") || t.contains("kbs") || t.contains("city hoppa") || t.contains("matatu")) {
            return Classification("Expenses", "Fare")
        }

        // 4. Utilities / Bills (Matching verified sheet: Electricity, Water, WIFI, Minutes, Rent, Monthly Shopping)
        if (t.contains("kenya power") || t.contains("kplc") || t.contains("electricity")) {
            return Classification("Bills", "Electricity")
        }
        if (t.contains("nairobi water") || t.contains("water")) {
            return Classification("Bills", "Water")
        }
        if (t.contains("safaricom home") || t.contains("zuku") || t.contains("faiba") || t.contains("wifi") || t.contains("poa") || t.contains("internet")) {
            return Classification("Bills", "WIFI")
        }
        if (t.contains("airtime") || t.contains("minutes")) {
            return Classification("Bills", "Minutes")
        }
        if (t.contains("rent")) {
            return Classification("Bills", "Rent")
        }

        // 5. Bank Transfers / Balance adjustments
        if (t.contains("equity") || t.contains("equity bank")) {
            return Classification("Balance", "", "Equity Bank")
        }
        if (t.contains("i&m") || t.contains("i and m")) {
            return Classification("Balance", "", "I&M Bank")
        }

        // Default P2P or personal expense
        return Classification("Expenses", "House Supplies")
    }

    private fun classifyMerchant(merchant: String): Classification {
        val m = merchant.lowercase(Locale.ROOT)
        // Supermarkets / Shopping -> Bills: Monthly Shopping
        if (m.contains("naivas") || m.contains("carrefour") || m.contains("quickmart") || m.contains("chandarana") || m.contains("cleanshelf")) {
            return Classification("Bills", "Monthly Shopping")
        }
        // Restaurants / Dining -> Expenses: Eating Out
        if (m.contains("kfc") || m.contains("java") || m.contains("artcaffe") || m.contains("pizza") || m.contains("restaurant") || m.contains("cafe") || m.contains("hotel")) {
            return Classification("Expenses", "Eating Out")
        }
        // Matatus / Fare -> Expenses: Fare
        if (m.contains("super metro") || m.contains("2nk") || m.contains("metro") || m.contains("matatu") || m.contains("uber") || m.contains("bolt") || m.contains("fare")) {
            return Classification("Expenses", "Fare")
        }
        // Fuel / Gas -> Expenses: Gas
        if (m.contains("total") || m.contains("shell") || m.contains("rubis") || m.contains("ola") || m.contains("gas") || m.contains("energy")) {
            return Classification("Expenses", "Gas")
        }
        // Groceries / Vegetables -> Expenses: Mama Mboga
        if (m.contains("mboga") || m.contains("grocer") || m.contains("market") || m.contains("veg")) {
            return Classification("Expenses", "Mama Mboga")
        }
        // Barber / Salon -> Expenses: Kinyozi
        if (m.contains("kinyozi") || m.contains("barber") || m.contains("salon")) {
            return Classification("Expenses", "Kinyozi")
        }
        // Clothes / Shoes -> Expenses: Clothes / Shoes
        if (m.contains("shoe") || m.contains("footwear")) {
            return Classification("Expenses", "Shoes")
        }
        if (m.contains("clothe") || m.contains("apparel") || m.contains("boutique")) {
            return Classification("Expenses", "Clothes")
        }
        return Classification("Expenses", "House Supplies")
    }

    private fun formatIsoDate(rawDate: String): String {
        val parts = rawDate.split("/")
        if (parts.size != 3) return SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        val day = parts[0].padStart(2, '0')
        val month = parts[1].padStart(2, '0')
        var year = parts[2]
        if (year.length == 2) year = "20$year"
        return "$year-$month-$day"
    }
}
