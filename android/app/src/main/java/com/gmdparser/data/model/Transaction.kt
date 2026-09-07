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

data class ApiResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("status") val status: String,
    @SerializedName("message") val message: String? = null,
    @SerializedName("error") val error: String? = null,
    @SerializedName("data") val data: TransactionResponseData? = null
)

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

