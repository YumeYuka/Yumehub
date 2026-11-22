package moe.yumeyuka.yumehub

import android.app.Application
import com.tencent.mmkv.MMKV
import moe.yumeyuka.yumehub.di.appModule
import moe.yumeyuka.yumehub.ui.component.initializeGithubImageLoader
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin

class App : Application(), KoinComponent {
    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)
        startKoin {
            androidContext(this@App)
            modules(appModule)
        }
        initializeGithubImageLoader(this, get<OkHttpClient>())
    }
}
