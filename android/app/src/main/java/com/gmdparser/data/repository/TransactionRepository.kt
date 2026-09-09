package com.gmdparser.data.repository

import com.gmdparser.BuildConfig
import com.gmdparser.data.model.ApiResponse
import com.gmdparser.data.model.BatchApiResponse
import com.gmdparser.data.model.BatchCreateTransactionRequest
import com.gmdparser.data.model.BatchTransactionResult
import com.gmdparser.data.model.CreateTransactionRequest
import com.gmdparser.data.model.Transaction
import com.gmdparser.data.model.TransactionPayload
import com.gmdparser.data.model.TransactionStatus
import com.gmdparser.data.network.ApiClient
import com.gmdparser.data.network.NetworkClient
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object TransactionRepository {

    // Immutable Invariant: AutoSync is permanently disabled to enforce user confirmation
    fun isAutoSync(): Boolean = false

    private const val PREFS_NAME = "gmdparser_tx_repo"
    private const val KEY_PENDING = "pending_transactions"
    private const val KEY_CONFIRMED = "confirmed_transactions"
    /** Max transactions per batchCreateTransactions call — must match Apps Script MAX_BATCH. */
    private const val BATCH_MAX_SIZE = 50

    private var prefs: SharedPreferences? = null
    private val gson = Gson()

    private val _pendingTransactions = MutableStateFlow<List<Transaction>>(emptyList())
    val pendingTransactions: StateFlow<List<Transaction>> = _pendingTransactions.asStateFlow()

    private val _confirmedTransactions = MutableStateFlow<List<Transaction>>(emptyList())
    val confirmedTransactions: StateFlow<List<Transaction>> = _confirmedTransactions.asStateFlow()

    private val _dashboardData = MutableStateFlow<com.gmdparser.data.model.DashboardResponse?>(null)
    val dashboardData: StateFlow<com.gmdparser.data.model.DashboardResponse?> = _dashboardData.asStateFlow()

    private const val FALLBACK_GAS_URL = "https://script.google.com/macros/s/AKfycbzLo8NZHU3rmGIT6R-une9xrjUqwIdSbUG6to1O_ZwohEbvST1-3MjpNvCaNq2TOF4_Xw/exec"

    suspend fun fetchDashboard(): Result<com.gmdparser.data.model.DashboardResponse> {
        return try {
            val response = ApiClient.apiService.getDashboard()
            if (response.isSuccessful && response.body() != null && response.body()!!.success) {
                val data = response.body()!!
                _dashboardData.value = data
                Result.success(data)
            } else {
                fetchDashboardDirect()
            }
        } catch (e: Exception) {
            fetchDashboardDirect()
        }
    }

    private suspend fun fetchDashboardDirect(): Result<com.gmdparser.data.model.DashboardResponse> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build()
                val request = okhttp3.Request.Builder()
                    .url("$FALLBACK_GAS_URL?action=dashboard")
                    .get()
                    .build()
                val res = client.newCall(request).execute()
                val bodyStr = res.body?.string()
                if (res.isSuccessful && !bodyStr.isNullOrBlank()) {
                    val data = gson.fromJson(bodyStr, com.gmdparser.data.model.DashboardResponse::class.java)
                    if (data != null && data.success) {
                        _dashboardData.value = data
                        return@withContext Result.success(data)
                    }
                }
                Result.failure(Exception("Direct Apps Script fetch failed: ${res.code}"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    fun init(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp

        val pendingJson = sp.getString(KEY_PENDING, null)
        if (!pendingJson.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<Transaction>>() {}.type
                val loaded: List<Transaction> = gson.fromJson(pendingJson, type)
                _pendingTransactions.value = loaded
            } catch (e: Exception) { e.printStackTrace() }
        }

        val confirmedJson = sp.getString(KEY_CONFIRMED, null)
        if (!confirmedJson.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<Transaction>>() {}.type
                val loaded: List<Transaction> = gson.fromJson(confirmedJson, type)
                _confirmedTransactions.value = loaded
            } catch (e: Exception) { e.printStackTrace() }
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
        if (addedCount > 0) persistPending(_pendingTransactions.value)
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
     * Single-transaction confirmed write pipeline (unchanged behaviour).
     */
    suspend fun confirmAndSubmitTransaction(
        tx: Transaction,
        authKey: String? = null
    ): Result<ApiResponse> {
        if (tx.amount <= 0.0)
            return Result.failure(IllegalArgumentException("Amount must be greater than zero."))
        if (tx.transactionCode.isBlank())
            return Result.failure(IllegalArgumentException("Transaction code cannot be blank."))
        if (tx.type == "Transfer" && tx.destinationAccount.isNullOrBlank())
            return Result.failure(IllegalArgumentException("Transfers require a destination account."))

        val payload = TransactionPayload(
            date = tx.date, type = tx.type, category = tx.category,
            description = tx.description, amount = tx.amount, account = tx.account,
            transactionCode = tx.transactionCode, destinationAccount = tx.destinationAccount
        )
        val request = CreateTransactionRequest(action = "createTransaction", transaction = payload)

        return try {
            val response = try {
                val vercelRes = NetworkClient.apiService.recordTransaction(request, authKey)
                val directUrl = BuildConfig.APPS_SCRIPT_URL
                if (!vercelRes.isSuccessful && vercelRes.code() >= 500 && directUrl.isNotBlank()) {
                    try { NetworkClient.apiService.recordTransactionDirect(directUrl, request, authKey) }
                    catch (_: Exception) { vercelRes }
                } else vercelRes
            } catch (netErr: Exception) {
                val directUrl = BuildConfig.APPS_SCRIPT_URL
                if (directUrl.isNotBlank()) NetworkClient.apiService.recordTransactionDirect(directUrl, request, authKey)
                else throw netErr
            }

            val body = response.body()
            val errBodyStr = if (!response.isSuccessful) response.errorBody()?.string() else null
            val errJson = try { if (!errBodyStr.isNullOrBlank()) org.json.JSONObject(errBodyStr) else null } catch (_: Exception) { null }
            val statusStr = body?.status ?: errJson?.optString("status") ?: ""
            val isDuplicate = response.code() == 409 || statusStr == "DUPLICATE" || statusStr == "DUPLICATE_TRANSACTION_CODE"

            if (response.isSuccessful && body != null && body.success) {
                removePendingTransaction(tx.transactionCode)
                val confirmed = tx.copy(status = TransactionStatus.SYNCED)
                _confirmedTransactions.update { listOf(confirmed) + it }
                persistConfirmed(_confirmedTransactions.value)
                Result.success(body)
            } else if (isDuplicate) {
                // DUPLICATE means the transaction IS in the sheet — either from a previous
                // session or from our own request that succeeded but whose response was lost
                // (timeout / retry). Either way, the data is recorded. Treat as success so
                // the caller can proceed with follow-up writes (e.g. fee recording).
                removePendingTransaction(tx.transactionCode)
                val confirmed = tx.copy(status = TransactionStatus.SYNCED)
                _confirmedTransactions.update { listOf(confirmed) + it }
                persistConfirmed(_confirmedTransactions.value)
                val existingRow = errJson?.optJSONObject("existingRecord")?.optInt("row")
                    ?: body?.existingRecord?.row ?: errJson?.optInt("row")
                // Build a synthetic success response carrying the existing row
                val syntheticData = com.gmdparser.data.model.TransactionResponseData(
                    row = existingRow ?: 0,
                    transactionCode = tx.transactionCode,
                    amount = tx.amount,
                    type = tx.type,
                    category = tx.category,
                    account = tx.account,
                    date = tx.date,
                    timestamp = java.time.Instant.now().toString()
                )
                Result.success(ApiResponse(
                    success = true,
                    status = "DUPLICATE_OK",
                    message = "Transaction already recorded (row ${existingRow ?: "?"})",
                    data = syntheticData
                ))
            } else {
                val errorMsg = errJson?.optString("error") ?: body?.error
                    ?: "Server error (${response.code()}): ${response.message()}"
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Network request failed: ${e.localizedMessage ?: e.message}"))
        }
    }

    /**
     * Batch confirmation — sends up to [BATCH_MAX_SIZE] transactions per request.
     *
     * Uses the Apps Script [batchCreateTransactions] action which performs all writes in
     * staged field-level range operations with exactly 2 flush() calls for the entire batch
     * (vs. 2 per transaction in the single-write path). This eliminates the per-transaction
     * full-sheet recalculation that was causing timeout errors during SMS inbox catch-up.
     *
     * - Automatically splits batches larger than [BATCH_MAX_SIZE] into sequential chunks.
     * - Per-transaction results: CREATED | DUPLICATE | VALIDATION_ERROR | NETWORK_ERROR.
     * - Updates local pending/confirmed state atomically per chunk.
     * - Uses a 120-second read timeout OkHttpClient to handle large flush recalculations.
     */
    suspend fun confirmAndSubmitBatch(
        transactions: List<Transaction>,
        authKey: String? = null
    ): List<BatchTransactionResult> {
        if (transactions.isEmpty()) return emptyList()

        // Long-timeout HTTP client for batch writes (Apps Script flush can take 60-90 s)
        val batchHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val directAppsScriptUrl = BuildConfig.APPS_SCRIPT_URL

        fun buildBatchService(baseUrl: String): com.gmdparser.data.network.ApiService {
            var url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            return Retrofit.Builder()
                .baseUrl(url)
                .client(batchHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(com.gmdparser.data.network.ApiService::class.java)
        }

        val vercelService = buildBatchService(ApiClient.currentServerUrl)
        val gasService = if (directAppsScriptUrl.isNotBlank()) buildBatchService(directAppsScriptUrl) else null

        val allResults = mutableListOf<BatchTransactionResult>()
        val chunks = transactions.chunked(BATCH_MAX_SIZE)

        for ((chunkIdx, chunk) in chunks.withIndex()) {
            val payloads = chunk.map { tx ->
                TransactionPayload(
                    date = tx.date, type = tx.type, category = tx.category,
                    description = tx.description, amount = tx.amount, account = tx.account,
                    transactionCode = tx.transactionCode, destinationAccount = tx.destinationAccount
                )
            }
            val request = BatchCreateTransactionRequest(transactions = payloads)

            val batchResponse: BatchApiResponse? = try {
                val vercelRes = vercelService.recordBatchTransactions(request, authKey)
                if (!vercelRes.isSuccessful && vercelRes.code() >= 500 && gasService != null) {
                    try { gasService.recordBatchTransactionsDirect(directAppsScriptUrl, request, authKey).body() }
                    catch (_: Exception) { vercelRes.body() }
                } else {
                    vercelRes.body()
                }
            } catch (netErr: Exception) {
                if (gasService != null) {
                    try { gasService.recordBatchTransactionsDirect(directAppsScriptUrl, request, authKey).body() }
                    catch (_: Exception) { null }
                } else null
            }

            if (batchResponse == null) {
                chunk.forEachIndexed { i, tx ->
                    allResults.add(BatchTransactionResult(
                        index = chunkIdx * BATCH_MAX_SIZE + i,
                        transactionCode = tx.transactionCode,
                        status = "NETWORK_ERROR",
                        success = false,
                        error = "Network request failed — please retry"
                    ))
                }
                continue
            }

            // Update local state per result
            val newConfirmed = mutableListOf<Transaction>()
            for (result in batchResponse.results) {
                // Map result back to the original Transaction using code (safer than index across chunks)
                val originalTx = chunk.firstOrNull { it.transactionCode == result.transactionCode }
                    ?: continue
                when (result.status) {
                    "CREATED" -> {
                        removePendingTransaction(originalTx.transactionCode)
                        newConfirmed.add(originalTx.copy(status = TransactionStatus.SYNCED))
                    }
                    "DUPLICATE" -> {
                        removePendingTransaction(originalTx.transactionCode)
                        newConfirmed.add(originalTx.copy(status = TransactionStatus.DUPLICATE))
                    }
                    // VALIDATION_ERROR / NETWORK_ERROR: leave in pending for user to fix
                }
            }

            if (newConfirmed.isNotEmpty()) {
                _confirmedTransactions.update { newConfirmed + it }
                persistConfirmed(_confirmedTransactions.value)
            }

            // Re-index results to the global list position
            val offset = chunkIdx * BATCH_MAX_SIZE
            allResults.addAll(batchResponse.results.map { r -> r.copy(index = r.index + offset) })
        }

        return allResults.sortedBy { it.index }
    }
}
