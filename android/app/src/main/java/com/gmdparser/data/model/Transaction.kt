package com.gmdparser.data.model

import com.google.gson.annotations.SerializedName

enum class TransactionStatus {
    PENDING_REVIEW,
    CONFIRMED,
    SYNCED,
    DUPLICATE,
    FAILED
}

data class Transaction(
    val transactionCode: String,
    val amount: Double,
    val type: String,
    val category: String,
    val description: String,
    val account: String = "Mpesa",
    val date: String,
    val time: String = "",
    val destinationAccount: String? = null,
    val balance: Double? = null,
    val cost: Double? = null,
    val senderOrRecipient: String = "",
    val rawText: String = "",
    val status: TransactionStatus = TransactionStatus.PENDING_REVIEW
)

data class TransactionPayload(
    @SerializedName("date") val date: String,
    @SerializedName("type") val type: String,
    @SerializedName("category") val category: String,
    @SerializedName("description") val description: String,
    @SerializedName("amount") val amount: Double,
    @SerializedName("account") val account: String,
    @SerializedName("transactionCode") val transactionCode: String,
    @SerializedName("destinationAccount") val destinationAccount: String? = null
)

data class CreateTransactionRequest(
    @SerializedName("action") val action: String = "createTransaction",
    @SerializedName("transaction") val transaction: TransactionPayload
)

/** Batch write request — sends N transactions in a single round-trip. */
data class BatchCreateTransactionRequest(
    @SerializedName("action") val action: String = "batchCreateTransactions",
    @SerializedName("transactions") val transactions: List<TransactionPayload>
)

/** Per-transaction result inside a batch response. */
data class BatchTransactionResult(
    @SerializedName("index") val index: Int,
    @SerializedName("transactionCode") val transactionCode: String,
    @SerializedName("status") val status: String,
    @SerializedName("success") val success: Boolean,
    @SerializedName("row") val row: Int? = null,
    @SerializedName("existingRow") val existingRow: Int? = null,
    @SerializedName("error") val error: String? = null,
    @SerializedName("errors") val errors: List<String>? = null,
    @SerializedName("amount") val amount: Double? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("category") val category: String? = null,
    @SerializedName("account") val account: String? = null,
    @SerializedName("date") val date: String? = null,
    @SerializedName("notes") val notes: String? = null
)

/** Top-level response for batchCreateTransactions. */
data class BatchApiResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("status") val status: String,
    @SerializedName("written") val written: Int = 0,
    @SerializedName("duplicates") val duplicates: Int = 0,
    @SerializedName("errors") val errors: Int = 0,
    @SerializedName("total") val total: Int = 0,
    @SerializedName("firstRow") val firstRow: Int? = null,
    @SerializedName("lastRow") val lastRow: Int? = null,
    @SerializedName("results") val results: List<BatchTransactionResult> = emptyList(),
    @SerializedName("timestamp") val timestamp: String? = null,
    @SerializedName("error") val error: String? = null
)

data class TransactionResponseData(
    @SerializedName("row") val row: Int,
    @SerializedName("transactionCode") val transactionCode: String,
    @SerializedName("amount") val amount: Double,
    @SerializedName("type") val type: String,
    @SerializedName("category") val category: String,
    @SerializedName("account") val account: String,
    @SerializedName("date") val date: String,
    @SerializedName("timestamp") val timestamp: String
)

data class ExistingRecord(
    @SerializedName("row") val row: Int?,
    @SerializedName("transactionCode") val transactionCode: String?
)

data class ApiResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("status") val status: String,
    @SerializedName("message") val message: String? = null,
    @SerializedName("error") val error: String? = null,
    @SerializedName("data") val data: TransactionResponseData? = null,
    @SerializedName("existingRecord") val existingRecord: ExistingRecord? = null
)

class DuplicateTransactionException(
    val existingRow: Int?,
    val transactionCode: String,
    override val message: String
) : Exception(message)

data class HealthResponse(
    @SerializedName("status") val status: String,
    @SerializedName("service") val service: String,
    @SerializedName("version") val version: String,
    @SerializedName("timestamp") val timestamp: String
)

data class TaxonomyData(
    @SerializedName("types") val types: List<String>,
    @SerializedName("categoriesByType") val categoriesByType: Map<String, List<String>>,
    @SerializedName("accounts") val accounts: List<String>
)

data class TaxonomyResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: TaxonomyData
)

data class AuthCheckResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("status") val status: String,
    @SerializedName("service") val service: String? = null,
    @SerializedName("serverUrl") val serverUrl: String? = null,
    @SerializedName("autoSyncAllowed") val autoSyncAllowed: Boolean = false,
    @SerializedName("timestamp") val timestamp: String? = null,
    @SerializedName("error") val error: String? = null
)

data class SummaryTile(
    @SerializedName("actual") val actual: Double = 0.0,
    @SerializedName("goal") val goal: Double? = null,
    @SerializedName("diff") val diff: Double? = null,
    @SerializedName("statusText") val statusText: String? = null
)

data class SummaryTiles(
    @SerializedName("totalIncome") val totalIncome: SummaryTile? = null,
    @SerializedName("totalBills") val totalBills: SummaryTile? = null,
    @SerializedName("totalDebtPayoff") val totalDebtPayoff: SummaryTile? = null,
    @SerializedName("totalExpenses") val totalExpenses: SummaryTile? = null,
    @SerializedName("totalSavings") val totalSavings: SummaryTile? = null,
    @SerializedName("unallocatedIncome") val unallocatedIncome: SummaryTile? = null
)

data class CategoryItem(
    @SerializedName("category") val category: String,
    @SerializedName("goal") val goal: Double = 0.0,
    @SerializedName("actual") val actual: Double = 0.0,
    @SerializedName("diff") val diff: Double = 0.0
)

data class MonthlyDashboardData(
    @SerializedName("month") val month: String? = null,
    @SerializedName("tab") val tab: String? = null,
    @SerializedName("summaryTiles") val summaryTiles: SummaryTiles,
    @SerializedName("tables") val tables: Map<String, List<CategoryItem>>
)

data class AccountBalanceItem(
    @SerializedName("accountName") val accountName: String,
    @SerializedName("startBalance") val startBalance: Double = 0.0,
    @SerializedName("currentBalance") val currentBalance: Double = 0.0,
    @SerializedName("deposits") val deposits: Double = 0.0,
    @SerializedName("withdrawals") val withdrawals: Double = 0.0
)

data class DashboardResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("period") val period: String? = null,
    @SerializedName("month") val month: MonthlyDashboardData? = null,
    @SerializedName("dashboard") val dashboard: MonthlyDashboardData? = null,
    @SerializedName("accounts") val accounts: List<AccountBalanceItem>? = null,
    @SerializedName("timestamp") val timestamp: String? = null,
    @SerializedName("error") val error: String? = null
)


