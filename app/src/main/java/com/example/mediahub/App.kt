package com.example.mediahub

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.mediahub.data.AppDatabase
import com.example.mediahub.data.RuleRepository
import com.example.mediahub.engine.EngineManager
import com.example.mediahub.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application(), ImageLoaderFactory {
    lateinit var db: AppDatabase; private set
    lateinit var rules: RuleRepository; private set
    /** 应用级作用域：保存进度等不应随页面取消的写操作 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDatabase.build(this)
        rules = RuleRepository(this, db.ruleDao())
        AppLog.i("App", "started")
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { EngineManager.http }
        .respectCacheHeaders(false)
        .build()

    companion object { lateinit var instance: App; private set }
}
