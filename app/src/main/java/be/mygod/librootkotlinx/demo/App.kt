package be.mygod.librootkotlinx.demo

import android.app.Application
import android.util.Log
import be.mygod.librootkotlinx.AppProcess
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.RootServer
import be.mygod.librootkotlinx.RootSession
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class App : Application() {
    companion object {
        lateinit var rootManager: RootSession
    }

    override fun onCreate() {
        super.onCreate()
        // simply initialize a global instance, this is a very lightweight operation
        rootManager = object : RootSession() {
            override val timeout get() = TimeUnit.SECONDS.toMillis(5)
            override suspend fun initServer(server: RootServer) {
                if (BuildConfig.DEBUG) Logger.me = object : Logger {
                    override fun d(m: String?, t: Throwable?) {
                        Log.d("RootServer", m, t)
                    }
                }
                server.init(this@App, AppProcess.shouldRelocateHeuristics)
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
