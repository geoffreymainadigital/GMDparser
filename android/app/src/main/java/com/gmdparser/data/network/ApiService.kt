package com.gmdparser.data.network

import com.gmdparser.data.model.ApiResponse
import com.gmdparser.data.model.AuthCheckResponse
import com.gmdparser.data.model.BatchApiResponse
import com.gmdparser.data.model.BatchCreateTransactionRequest
import com.gmdparser.data.model.CreateTransactionRequest
import com.gmdparser.data.model.HealthResponse
import com.gmdparser.data.model.TaxonomyResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

interface ApiService {

    @GET("/api/health")
    suspend fun getHealth(): Response<HealthResponse>

    @GET("/api/auth-check")
    suspend fun authCheck(
        @Header("X-GMD-Auth-Key") authKey: String? = null
    ): Response<AuthCheckResponse>

    @GET("/api/taxonomy")
    suspend fun getTaxonomy(): Response<TaxonomyResponse>

    @POST("/api/transaction")
    suspend fun recordTransaction(
        @Body request: CreateTransactionRequest,
        @Header("X-GMD-Auth-Key") authKey: String? = null
    ): Response<ApiResponse>

    @POST
    suspend fun recordTransactionDirect(
        @Url url: String,
        @Body request: CreateTransactionRequest,
        @Header("X-GMD-Auth-Key") authKey: String? = null
    ): Response<ApiResponse>

    @POST("/api/transaction")
    suspend fun recordBatchTransactions(
        @Body request: BatchCreateTransactionRequest,
        @Header("X-GMD-Auth-Key") authKey: String? = null
    ): Response<BatchApiResponse>

    @POST
    suspend fun recordBatchTransactionsDirect(
        @Url url: String,
        @Body request: BatchCreateTransactionRequest,
        @Header("X-GMD-Auth-Key") authKey: String? = null
    ): Response<BatchApiResponse>

    @POST("/api/log-transaction")
    suspend fun logTransaction(
        @Body request: CreateTransactionRequest,
        @Header("X-GMD-Auth-Key") authKey: String? = null
    ): Response<ApiResponse>

    @POST("/api/auto-sync-sms")
    suspend fun autoSyncSms(
        @Body request: Map<String, Any>,
        @Header("X-GMD-Auth-Key") authKey: String? = null
    ): Response<ApiResponse>
}



