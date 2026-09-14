package com.gmdparser.data.network

import com.gmdparser.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    const val DEFAULT_SERVER_URL = "https://gmdparser.vercel.app"

    // Critical Safety Invariant
    fun isAutoSync(): Boolean = false

    // Authoritative API Routes
    const val PATH_AUTH_CHECK = "/api/auth-check"
    const val PATH_AUTO_SYNC_SMS = "/api/auto-sync-sms"
    const val PATH_LOG_TRANSACTION = "/api/log-transaction"
    const val PATH_HEALTH = "/api/health"
    const val PATH_TRANSACTION = "/api/transaction"
    const val PATH_TAXONOMY = "/api/taxonomy"
    const val PATH_TEST_CONNECTION = "/api/test-connection"

    private var serverUrl: String = DEFAULT_SERVER_URL

    val currentServerUrl: String
        get() = serverUrl

    fun setServerUrl(url: String?) {
        serverUrl = if (!url.isNullOrBlank()) {
            if (url.endsWith("/")) url.removeSuffix("/") else url
        } else {
            DEFAULT_SERVER_URL
        }
        retrofitInstance = null
    }

    private val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.BASIC
        }

        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)   // flush() + formula recalc can take 60-80s on large sheets
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)      // Prevent silent re-sends that cause ghost duplicates
            .addInterceptor { chain ->
                val original = chain.request()
                val requestBuilder = original.newBuilder()
                    .header("X-GMD-Auth-Key", BuildConfig.AUTH_SECRET)
                val request = requestBuilder.build()
                chain.proceed(request)
            }
            .addInterceptor(logging)
            .build()
    }

    private var retrofitInstance: Retrofit? = null

    val apiService: ApiService
        get() {
            if (retrofitInstance == null) {
                var url = currentServerUrl
                if (!url.endsWith("/")) url += "/"
                retrofitInstance = Retrofit.Builder()
                    .baseUrl(url)
                    .client(okHttpClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
            }
            return retrofitInstance!!.create(ApiService::class.java)
        }
}
