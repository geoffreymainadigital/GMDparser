package com.gmdparser.data.network

import com.gmdparser.data.model.ApiResponse
import com.gmdparser.data.model.CreateTransactionRequest
import com.gmdparser.data.model.HealthResponse
import com.gmdparser.data.model.TaxonomyResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface ApiService {

    @GET("/api/health")
    suspend fun getHealth(): Response<HealthResponse>

    @GET("/api/taxonomy")
    suspend fun getTaxonomy(): Response<TaxonomyResponse>

    @POST("/api/transaction")
    suspend fun recordTransaction(
        @Body request: CreateTransactionRequest,
        @Header("X-GMD-Auth-Key") authKey: String? = null
    ): Response<ApiResponse>
}
