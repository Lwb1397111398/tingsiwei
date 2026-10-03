package com.tingsiwei.app

import android.app.Application
import com.tingsiwei.app.data.db.AppDatabase
import com.tingsiwei.app.data.SettingsRepository
import com.tingsiwei.app.pipeline.NoteProcessor
import com.tingsiwei.app.transcribe.ModelManager

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
        // 上次没跑完的转写/生成（进程被杀、手机重启）直接续上，不要求用户挨个点开笔记
        NoteProcessor.resumeStuck()
        // 旧版 vits-melo 朗读模型已被 kokoro 替代，后台清掉给用户腾出约 190MB 空间
        Thread { ModelManager.cleanupLegacyTts(this) }.start()
    }
}
