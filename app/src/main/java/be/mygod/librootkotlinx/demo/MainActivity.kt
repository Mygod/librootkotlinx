package be.mygod.librootkotlinx.demo

import android.os.Build
import android.os.Bundle
import android.os.Process
import android.text.method.ScrollingMovementMethod
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import be.mygod.librootkotlinx.JniInit
import be.mygod.librootkotlinx.ParcelableString
import be.mygod.librootkotlinx.RootCommand
import be.mygod.librootkotlinx.RootCommandChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize

class MainActivity : ComponentActivity() {
    @Parcelize
    class SimpleTest : RootCommand<ParcelableString> {
        override suspend fun execute() = ParcelableString("uid: " +
                (if (Build.VERSION.SDK_INT >= 23) Jni.getuid() else Process.myUid()) + "\n" +
                withContext(Dispatchers.IO) {
                    // try to execute a restricted subprocess command
                    val process = ProcessBuilder("/system/bin/iptables", "-L", "INPUT").start()
                    var output = process.inputStream.reader().readText()
                    when (val exit = process.waitFor()) {
                        0 -> { }
                        else -> output += "Process exited with $exit".toByteArray()
                    }
                    output
                })
    }

    @Parcelize
    class ChannelDemo : RootCommandChannel<ParcelableString> {
        override fun create(scope: CoroutineScope) = scope.produce {
            send(ParcelableString("Hello"))
            send(ParcelableString("World"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val text = findViewById<TextView>(android.R.id.text1)
        text.movementMethod = ScrollingMovementMethod()
        lifecycleScope.launch {
            text.text = try {
                App.rootManager.use {
                    // it is safe to call this multiple times if you don't feel like remembering in client
                    if (Build.VERSION.SDK_INT >= 23) it.execute(JniInit())
                    it.execute(SimpleTest()).value + '\n' + it.create(ChannelDemo(), lifecycleScope).toList()
                        .joinToString { it.value }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                e.stackTraceToString()
            }
        }
    }
}
