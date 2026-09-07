package com.gmdparser.data.network

import com.gmdparser.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkClient {

    val baseUrl: String
        get() = ApiClient.currentServerUrl

    fun setCustomBaseUrl(url: String?) {
        ApiClient.setServerUrl(url)
    }

    val apiService: ApiService
        get() = ApiClient.apiService
}

