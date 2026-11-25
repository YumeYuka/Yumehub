package moe.yumeyuka.yumehub

import android.app.Application
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import moe.yumeyuka.yumehub.data.storage.mmkv.SettingsStore
import moe.yumeyuka.yumehub.di.appModule
import moe.yumeyuka.yumehub.ui.component.initializeGithubImageLoader
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin

class App : Application(), KoinComponent {
    private lateinit var storageStartup: Deferred<Unit>
    internal var storageReady = false
        private set

    internal suspend fun awaitStorage() = storageStartup.await()

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@App)
            modules(appModule)
        }
        storageStartup = CoroutineScope(Dispatchers.Main.immediate).async {
            withContext(Dispatchers.IO) {
                MMKV.initialize(this@App)
                get<SettingsStore>().loadUiPreferences()
                val client = get<OkHttpClient>()
                initializeGithubImageLoader(this@App, client)
            }
            storageReady = true
        }
    }
}
