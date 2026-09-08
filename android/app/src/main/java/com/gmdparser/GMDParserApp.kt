package com.gmdparser

import android.app.Application
import com.gmdparser.data.repository.TransactionRepository

class GMDParserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TransactionRepository.init(this)
    }
}
