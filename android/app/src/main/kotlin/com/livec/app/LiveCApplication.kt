package com.livec.app

import android.app.Application
import com.livec.app.data.ConfigStore
import com.livec.app.service.LiveCService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LiveCApplication : Application() {
    val configStore by lazy { ConfigStore(this) }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch { configStore.ensureDeviceId() }
        LiveCService.start(this)
    }
}
