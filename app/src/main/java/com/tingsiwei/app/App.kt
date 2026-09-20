package com.tingsiwei.app

import android.app.Application
import com.tingsiwei.app.data.db.AppDatabase
import com.tingsiwei.app.data.SettingsRepository

class App : Application() {
    val database: AppDatabase by lazy { AppDatabase.create(this) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }

    companion object {
        private var instance: App? = null
        fun get(): App = instance!!
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
