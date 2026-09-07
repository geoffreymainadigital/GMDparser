package com.gmdparser.data.repository

import com.gmdparser.data.model.ApiResponse
import com.gmdparser.data.model.CreateTransactionRequest
import com.gmdparser.data.model.Transaction
import com.gmdparser.data.model.TransactionPayload
import com.gmdparser.data.model.TransactionStatus
import com.gmdparser.data.network.NetworkClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object TransactionRepository {

    // Immutable Invariant: AutoSync is permanently disabled to enforce user confirmation
    fun isAutoSync(): Boolean = false

    private val _pendingTransactions = MutableStateFlow<List<Transaction>>(emptyList())
    val pendingTransactions: StateFlow<List<Transaction>> = _pendingTransactions.asStateFlow()

    private val _confirmedTransactions = MutableStateFlow<List<Transaction>>(emptyList())
    val confirmedTransactions: StateFlow<List<Transaction>> = _confirmedTransactions.asStateFlow()

    fun addPendingTransaction(tx: Transaction) {
        _pendingTransactions.update { current ->
            // Prevent duplicate pending entries with same transaction code
            if (current.none { it.transactionCode == tx.transactionCode }) {
                listOf(tx) + current
            } else {
                current
            }
        }
    }

    fun updatePendingTransaction(updated: Transaction) {
        _pendingTransactions.update { current ->
            current.map { if (it.transactionCode == updated.transactionCode) updated else it }
        }
    }

    fun removePendingTransaction(txCode: String) {
        _pendingTransactions.update { current ->
            current.filter { it.transactionCode != txCode }
        }
    }

    /**
     * Executes the mandatory confirmed transaction write pipeline
     */
    suspend fun confirmAndSubmitTransaction(
        tx: Transaction,
        authKey: String? = null
    ): Result<ApiResponse> {
        // Enforce fail-closed check
        if (tx.amount <= 0.0) {
            return Result.failure(IllegalArgumentException("Amount must be greater than zero."))
        }
        if (tx.transactionCode.isBlank()) {
            return Result.failure(IllegalArgumentException("Transaction code cannot be blank."))
        }
        if (tx.type == "Transfer" && tx.destinationAccount.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("Transfers require a destination account."))
        }

        val payload = TransactionPayload(
            date = tx.date,
            type = tx.type,
            category = tx.category,
            description = tx.description,
            amount = tx.amount,
            account = tx.account,
            transactionCode = tx.transactionCode,
            destinationAccount = tx.destinationAccount
        )

        val request = CreateTransactionRequest(
            action = "createTransaction",
            transaction = payload
        )

        return try {
            val response = NetworkClient.apiService.recordTransaction(request, authKey)
            val body = response.body()

            if (response.isSuccessful && body != null && body.success) {
                // Success: Move from pending to confirmed
                removePendingTransaction(tx.transactionCode)
                val confirmed = tx.copy(status = TransactionStatus.SYNCED)
                _confirmedTransactions.update { listOf(confirmed) + it }
                Result.success(body)
            } else if (response.code() == 409) {
                // Duplicate transaction code detected by backend
                removePendingTransaction(tx.transactionCode)
                val dup = tx.copy(status = TransactionStatus.DUPLICATE)
                _confirmedTransactions.update { listOf(dup) + it }
                Result.failure(Exception("Duplicate transaction code: ${body?.error ?: "Already recorded in sheet"}"))
            } else {
                val errorMsg = body?.error ?: "Server error (${response.code()}): ${response.message()}"
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Network request failed: ${e.localizedMessage ?: e.message}"))
        }
    }
}
