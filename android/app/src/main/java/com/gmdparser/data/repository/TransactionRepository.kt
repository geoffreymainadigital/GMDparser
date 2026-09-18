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
    private const val KEY_MONTHLY_DASHBOARD = "cached_monthly_dashboard"
    private const val KEY_FULL_DASHBOARD = "cached_full_dashboard"
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

    private val _monthlyDashboard = MutableStateFlow<com.gmdparser.data.model.MonthlyDashboardData?>(null)
    val monthlyDashboard: StateFlow<com.gmdparser.data.model.MonthlyDashboardData?> = _monthlyDashboard.asStateFlow()

    @Volatile
    private var dashboardFetchRequestId: Long = 0

    private const val FALLBACK_GAS_URL = "https://script.google.com/macros/s/AKfycbyeDkG4ytwqhGzgLsePqUT2ME0TJp9QkI4Kn4kAiX9qwafFeKc7LxQXliX4btt2yRu8kA/exec"

    private fun handleDashboardSuccess(data: com.gmdparser.data.model.DashboardResponse, reqId: Long) {
        if (reqId == dashboardFetchRequestId) {
            _dashboardData.value = data
            prefs?.edit()?.putString(KEY_FULL_DASHBOARD, gson.toJson(data))?.apply()
            val monthData = data.month ?: data.dashboard
            if (monthData != null) {
                _monthlyDashboard.value = monthData
                prefs?.edit()?.putString(KEY_MONTHLY_DASHBOARD, gson.toJson(monthData))?.apply()
            }
        }
    }

    suspend fun fetchDashboard(period: String = "monthly"): Result<com.gmdparser.data.model.DashboardResponse> {
        val currentReqId = synchronized(this) { ++dashboardFetchRequestId }
        return try {
            val response = ApiClient.apiService.getDashboard(period)
            if (response.isSuccessful && response.body() != null && response.body()!!.success) {
                val data = response.body()!!
                handleDashboardSuccess(data, currentReqId)
                Result.success(data)
            } else {
                fetchDashboardDirect(period, currentReqId)
            }
        } catch (e: Exception) {
            fetchDashboardDirect(period, currentReqId)
        }
    }

    private suspend fun fetchDashboardDirect(period: String, reqId: Long): Result<com.gmdparser.data.model.DashboardResponse> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(35, java.util.concurrent.TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build()
                val request = okhttp3.Request.Builder()
                    .url("$FALLBACK_GAS_URL?action=dashboard&period=$period")
                    .get()
                    .build()
                val res = client.newCall(request).execute()
                val bodyStr = res.body?.string()
                if (res.isSuccessful && !bodyStr.isNullOrBlank()) {
                    val data = gson.fromJson(bodyStr, com.gmdparser.data.model.DashboardResponse::class.java)
                    if (data != null && data.success) {
                        handleDashboardSuccess(data, reqId)
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

        val monthlyJson = sp.getString(KEY_MONTHLY_DASHBOARD, null)
        if (!monthlyJson.isNullOrBlank()) {
            try {
                val loaded = gson.fromJson(monthlyJson, com.gmdparser.data.model.MonthlyDashboardData::class.java)
                _monthlyDashboard.value = loaded
            } catch (e: Exception) { e.printStackTrace() }
        }

        val fullDashJson = sp.getString(KEY_FULL_DASHBOARD, null)
        if (!fullDashJson.isNullOrBlank()) {
            try {
                val loaded = gson.fromJson(fullDashJson, com.gmdparser.data.model.DashboardResponse::class.java)
                _dashboardData.value = loaded
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
        tx: Transaction
    ): Result<ApiResponse> {
        if (tx.amount == 0.0)
            return Result.failure(IllegalArgumentException("Amount must be a non-zero number."))
        if (tx.amount < 0.0 && tx.type != "Savings" && tx.type != "Balance")
            return Result.failure(IllegalArgumentException("Negative amounts are only valid for Savings and Balance transactions."))
        if (tx.transactionCode.isBlank())
            return Result.failure(IllegalArgumentException("Transaction code cannot be blank."))
        if ((tx.type == "Transfer" || tx.type == "Balance") && !tx.destinationAccount.isNullOrBlank()) {
            // Transfer payload with destination account is valid
        }

        val isTransferAction = (tx.type == "Transfer" || tx.type == "Balance") && !tx.destinationAccount.isNullOrBlank()
        val payload = TransactionPayload(
            date = tx.date, type = tx.type, category = tx.category,
            description = tx.description, amount = tx.amount, account = tx.account,
            transactionCode = tx.transactionCode, destinationAccount = tx.destinationAccount,
            cost = tx.cost, rawText = tx.rawText
        )
        val request = CreateTransactionRequest(
            action = if (isTransferAction) "createTransferPair" else "createTransaction", 
            transaction = payload
        )

        return try {
            val response = try {
                val vercelRes = NetworkClient.apiService.recordTransaction(request)
                val directUrl = BuildConfig.APPS_SCRIPT_URL
                if (!vercelRes.isSuccessful && vercelRes.code() >= 500 && directUrl.isNotBlank()) {
                    try { NetworkClient.apiService.recordTransactionDirect(directUrl, request) }
                    catch (_: Exception) { vercelRes }
                } else vercelRes
            } catch (netErr: Exception) {
                val directUrl = BuildConfig.APPS_SCRIPT_URL
                if (directUrl.isNotBlank()) NetworkClient.apiService.recordTransactionDirect(directUrl, request)
                else throw netErr
            }

            val body = response.body()
            val errBodyStr = try { response.errorBody()?.string() } catch (_: Exception) { null }
            val rawBodyStr = try { response.body()?.toString() } catch (_: Exception) { null }
            
            // Apps Script web apps return HTTP 200 with HTML/redirect or JSON body
            val errJson = try {
                if (!errBodyStr.isNullOrBlank()) org.json.JSONObject(errBodyStr)
                else null
            } catch (_: Exception) { null }

            val statusStr = body?.status ?: errJson?.optString("status") ?: ""
            val isSuccessStatus = body?.success == true || statusStr == "CREATED" || statusStr == "BATCH_COMPLETE" || response.code() == 201 || response.code() == 200
            val isDuplicate = response.code() == 409 || statusStr == "DUPLICATE" || statusStr == "DUPLICATE_TRANSACTION_CODE"

            if (isSuccessStatus) {
                // HTTP 200/201 or success=true means Apps Script recorded the transaction!
                removePendingTransaction(tx.transactionCode)
                val confirmed = tx.copy(status = TransactionStatus.SYNCED)
                _confirmedTransactions.update { listOf(confirmed) + it }
                persistConfirmed(_confirmedTransactions.value)
                val successBody = body ?: ApiResponse(success = true, status = statusStr.ifBlank { "CREATED" }, message = "Recorded to sheet")
                Result.success(successBody)
            } else if (isDuplicate) {
                removePendingTransaction(tx.transactionCode)
                val confirmed = tx.copy(status = TransactionStatus.SYNCED)
                _confirmedTransactions.update { listOf(confirmed) + it }
                persistConfirmed(_confirmedTransactions.value)
                val existingRow = errJson?.optJSONObject("existingRecord")?.optInt("row")
                    ?: body?.existingRecord?.row ?: errJson?.optInt("row")
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
                val rawErr = errJson?.optString("error")
                val errorMsg = if (!rawErr.isNullOrBlank()) rawErr
                    else if (!body?.error.isNullOrBlank()) body!!.error
                    else if (response.message().isNotBlank()) response.message()
                    else "Recorded to sheet"
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
        transactions: List<Transaction>
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
                val vercelRes = vercelService.recordBatchTransactions(request)
                if (!vercelRes.isSuccessful && vercelRes.code() >= 500 && gasService != null) {
                    try { gasService.recordBatchTransactionsDirect(directAppsScriptUrl, request).body() }
                    catch (_: Exception) { vercelRes.body() }
                } else {
                    vercelRes.body()
                }
            } catch (netErr: Exception) {
                if (gasService != null) {
                    try { gasService.recordBatchTransactionsDirect(directAppsScriptUrl, request).body() }
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
