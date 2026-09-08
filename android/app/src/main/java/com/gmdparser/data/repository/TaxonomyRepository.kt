package com.gmdparser.data.repository

import com.gmdparser.data.model.TaxonomyDefaults
import com.gmdparser.data.network.NetworkClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object TaxonomyRepository {

    private val _types = MutableStateFlow<List<String>>(TaxonomyDefaults.TYPES)
    val types: StateFlow<List<String>> = _types.asStateFlow()

    private val _categoriesByType = MutableStateFlow<Map<String, List<String>>>(TaxonomyDefaults.CATEGORIES_BY_TYPE)
    val categoriesByType: StateFlow<Map<String, List<String>>> = _categoriesByType.asStateFlow()

    private val _accounts = MutableStateFlow<List<String>>(TaxonomyDefaults.ACCOUNTS)
    val accounts: StateFlow<List<String>> = _accounts.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    suspend fun refreshTaxonomy(): Result<Unit> {
        _isRefreshing.value = true
        return try {
            val response = NetworkClient.apiService.getTaxonomy()
            if (response.isSuccessful && response.body()?.success == true) {
                val data = response.body()!!.data
                if (data.types.isNotEmpty()) {
                    _types.value = data.types
                }
                if (data.categoriesByType.isNotEmpty()) {
                    _categoriesByType.value = data.categoriesByType
                }
                if (data.accounts.isNotEmpty()) {
                    _accounts.value = data.accounts
                }
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to fetch taxonomy: ${response.message()}"))
            }
        } catch (e: Exception) {
            // Keep local TaxonomyDefaults on network failure
            Result.failure(e)
        } finally {
            _isRefreshing.value = false
        }
    }
}
