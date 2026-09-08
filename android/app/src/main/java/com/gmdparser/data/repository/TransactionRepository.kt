package com.gmdparser.data.repository

import com.gmdparser.BuildConfig
import com.gmdparser.data.model.ApiResponse
import com.gmdparser.data.model.CreateTransactionRequest
import com.gmdparser.data.model.DuplicateTransactionException
import com.gmdparser.data.model.Transaction
import com.gmdparser.data.model.TransactionPayload
import com.gmdparser.data.model.TransactionStatus
import com.gmdparser.data.network.NetworkClient
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object TransactionRepository {

    // Immutable Invariant: AutoSync is permanently disabled to enforce user confirmation
    fun isAutoSync(): Boolean = false

    private const val PREFS_NAME = "gmdparser_tx_repo"
    private const val KEY_PENDING = "pending_transactions"
    private const val KEY_CONFIRMED = "confirmed_transactions"

    private var prefs: SharedPreferences? = null
    private val gson = Gson()

    private val _pendingTransactions = MutableStateFlow<List<Transaction>>(emptyList())
    val pendingTransactions: StateFlow<List<Transaction>> = _pendingTransactions.asStateFlow()

    private val _confirmedTransactions = MutableStateFlow<List<Transaction>>(emptyList())
    val confirmedTransactions: StateFlow<List<Transaction>> = _confirmedTransactions.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp

        // Restore pending transactions from persistent storage
        val pendingJson = sp.getString(KEY_PENDING, null)
        if (!pendingJson.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<Transaction>>() {}.type
                val loaded: List<Transaction> = gson.fromJson(pendingJson, type)
                _pendingTransactions.value = loaded
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Restore confirmed history from persistent storage
        val confirmedJson = sp.getString(KEY_CONFIRMED, null)
        if (!confirmedJson.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<Transaction>>() {}.type
                val loaded: List<Transaction> = gson.fromJson(confirmedJson, type)
                _confirmedTransactions.value = loaded
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun persistPending(list: List<Transaction>) {
        prefs?.edit()?.putString(KEY_PENDING, gson.toJson(list))?.apply()
    }

    private fun persistConfirmed(list: List<Transaction>) {
        prefs?.edit()?.putString(KEY_CONFIRMED, gson.toJson(list))?.apply()
    }

    fun addPendingTransaction(tx: Transaction) {
        _pendingTransactions.update { current ->
            if (current.none { it.transactionCode == tx.transactionCode } &&
                _confirmedTransactions.value.none { it.transactionCode == tx.transactionCode }) {
                listOf(tx) + current
            } else {
                current
            }
        }
        persistPending(_pendingTransactions.value)
    }

    fun addPendingTransactions(list: List<Transaction>): Int {
        var addedCount = 0
        _pendingTransactions.update { current ->
            val newItems = list.filter { item ->
                current.none { it.transactionCode == item.transactionCode } &&
                _confirmedTransactions.value.none { it.transactionCode == item.transactionCode }
            }
            addedCount = newItems.size
            newItems + current
        }
        if (addedCount > 0) {
            persistPending(_pendingTransactions.value)
        }
        return addedCount
    }

    fun updatePendingTransaction(updated: Transaction) {
        _pendingTransactions.update { current ->
            current.map { if (it.transactionCode == updated.transactionCode) updated else it }
        }
        persistPending(_pendingTransactions.value)
    }

    fun removePendingTransaction(txCode: String) {
        _pendingTransactions.update { current ->
            current.filter { it.transactionCode != txCode }
        }
        persistPending(_pendingTransactions.value)
    }

    /**
     * Executes the mandatory confirmed transaction write pipeline.
     * Per Section 11 architecture: writes directly to Apps Script (/exec)
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
            val response = try {
                val vercelRes = NetworkClient.apiService.recordTransaction(request, authKey)
                val directAppsScriptUrl = BuildConfig.APPS_SCRIPT_URL
                if (!vercelRes.isSuccessful && vercelRes.code() >= 500 && directAppsScriptUrl.isNotBlank()) {
                    try {
                        NetworkClient.apiService.recordTransactionDirect(directAppsScriptUrl, request, authKey)
                    } catch (_: Exception) {
                        vercelRes
                    }
                } else {
                    vercelRes
                }
            } catch (netErr: Exception) {
                val directAppsScriptUrl = BuildConfig.APPS_SCRIPT_URL
                if (directAppsScriptUrl.isNotBlank()) {
                    NetworkClient.apiService.recordTransactionDirect(directAppsScriptUrl, request, authKey)
                } else {
                    throw netErr
                }
            }
            val body = response.body()
            val errBodyStr = if (!response.isSuccessful) response.errorBody()?.string() else null
            val errJson = try {
                if (!errBodyStr.isNullOrBlank()) org.json.JSONObject(errBodyStr) else null
            } catch (_: Exception) { null }

            val statusStr = body?.status ?: errJson?.optString("status") ?: ""
            val isDuplicate = response.code() == 409 ||
                statusStr == "DUPLICATE" ||
                statusStr == "DUPLICATE_TRANSACTION_CODE"

            if (response.isSuccessful && body != null && body.success) {
                // Success: Move from pending to confirmed
                removePendingTransaction(tx.transactionCode)
                val confirmed = tx.copy(status = TransactionStatus.SYNCED)
                _confirmedTransactions.update { listOf(confirmed) + it }
                persistConfirmed(_confirmedTransactions.value)
                Result.success(body)
            } else if (isDuplicate) {
                // Duplicate transaction code detected by backend
                removePendingTransaction(tx.transactionCode)
                val dup = tx.copy(status = TransactionStatus.DUPLICATE)
                _confirmedTransactions.update { listOf(dup) + it }
                persistConfirmed(_confirmedTransactions.value)
                val existingRow = errJson?.optJSONObject("existingRecord")?.optInt("row")
                    ?: body?.existingRecord?.row
                    ?: errJson?.optInt("row")
                val dupMsg = errJson?.optString("error")
                    ?: body?.error
                    ?: "Transaction code ${tx.transactionCode} already recorded in sheet"
                Result.failure(DuplicateTransactionException(if (existingRow != null && existingRow > 0) existingRow else null, tx.transactionCode, dupMsg))
            } else {
                val errorMsg = errJson?.optString("error")
                    ?: body?.error
                    ?: "Server error (${response.code()}): ${response.message()}"
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Network request failed: ${e.localizedMessage ?: e.message}"))
        }
    }
}
