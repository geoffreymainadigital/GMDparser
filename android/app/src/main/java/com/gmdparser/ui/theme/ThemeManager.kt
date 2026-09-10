package com.gmdparser.ui.theme

import com.gmdparser.util.AppPreferences
import com.gmdparser.util.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reactive theme manager. Compose UI observes [currentTheme] and recomposes
 * immediately when [setTheme] is called from Settings.
 */
object ThemeManager {
    private val _currentTheme = MutableStateFlow(AppPreferences.selectedTheme)
    val currentTheme: StateFlow<AppTheme> = _currentTheme.asStateFlow()

    fun init() {
        _currentTheme.value = AppPreferences.selectedTheme
    }

    fun setTheme(theme: AppTheme) {
        AppPreferences.selectedTheme = theme
        _currentTheme.value = theme
    }
}
