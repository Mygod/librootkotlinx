package be.mygod.librootkotlinx.demo

import android.app.Application
import android.os.StrictMode
import android.util.Log
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.RootServer
import be.mygod.librootkotlinx.RootSession
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class App : Application() {
    companion object {
        lateinit var rootManager: RootSession
    }

    override fun onCreate() {
        super.onCreate()
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyDeath().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyDeath().build())
        // simply initialize a global instance, this is a very lightweight operation
        rootManager = object : RootSession() {
            override val context get() = this@App
            override val timeout get() = 5.seconds
            override suspend fun initServer(server: RootServer) {
                if (BuildConfig.DEBUG) Logger.me = object : Logger {
                    override fun d(m: String?, t: Throwable?) {
                        Log.d("RootServer", m, t)
                    }
                }
                super.initServer(server)
            }
        }
        Log.d("App", "init")
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // optional: try to close existing root session when memory is running low
        if (level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_BACKGROUND) GlobalScope.launch {
            rootManager.closeExisting()
        }
    }
}
