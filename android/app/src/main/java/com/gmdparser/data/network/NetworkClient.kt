package com.gmdparser.data.network

import com.gmdparser.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkClient {

    private var customBaseUrl: String? = null

    val baseUrl: String
        get() = customBaseUrl ?: BuildConfig.BASE_URL

    fun setCustomBaseUrl(url: String?) {
        customBaseUrl = if (!url.isNullOrBlank()) {
            if (url.endsWith("/")) url else "$url/"
        } else {
            null
        }
        retrofitInstance = null
    }

    private val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.BASIC
        }

        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }

    private var retrofitInstance: Retrofit? = null

    val apiService: ApiService
        get() {
            if (retrofitInstance == null) {
                var url = baseUrl
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
