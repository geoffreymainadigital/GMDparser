package com.gmdparser

import android.app.Application
import com.gmdparser.data.repository.TransactionRepository
import com.gmdparser.ui.theme.ThemeManager
import com.gmdparser.util.AppPreferences

class GMDParserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPreferences.init(this)          // must be first — ThemeManager reads it
        TransactionRepository.init(this)
        ThemeManager.init()                // loads saved theme into the StateFlow
    }
}
