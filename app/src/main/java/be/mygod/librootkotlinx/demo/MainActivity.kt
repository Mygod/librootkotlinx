package be.mygod.librootkotlinx.demo

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.method.ScrollingMovementMethod
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import be.mygod.librootkotlinx.ParcelableString
import be.mygod.librootkotlinx.RootCommand
import be.mygod.librootkotlinx.RootCommandChannel
import be.mygod.librootkotlinx.RootCommandNoResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize

class MainActivity : ComponentActivity() {
    @Parcelize
    class SimpleTest : RootCommand<ParcelableString> {
        override suspend fun execute() = ParcelableString("uid: ${Jni.getuid()}\n" + withContext(Dispatchers.IO) {
            // Try to execute a restricted subprocess command.
            val process = ProcessBuilder("/system/bin/iptables", "-L", "INPUT").start()
            var output = process.inputStream.reader().readText()
            when (val exit = process.waitFor()) {
                0 -> { }
                else -> output += "Process exited with $exit"
            }
            output
        })
    }

    @Parcelize
    class ChannelDemo : RootCommandChannel<ParcelableString> {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun create(scope: CoroutineScope) = scope.produce {
            send(ParcelableString("Hello"))
            send(ParcelableString("World"))
        }
    }

    @Parcelize
    class FileDescriptorDemo(private val output: ParcelFileDescriptor) : RootCommandNoResult {
        override suspend fun execute() = null.also {
            ParcelFileDescriptor.AutoCloseOutputStream(output).writer().use {
                it.write("fd uid: ${Jni.getuid()}")
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val text = findViewById<TextView>(android.R.id.text1)
        text.movementMethod = ScrollingMovementMethod()
        lifecycleScope.launch {
            text.text = try {
                App.rootManager.use {
                    val pipe = ParcelFileDescriptor.createPipe()
                    try {
                        it.execute(FileDescriptorDemo(pipe[1]))
                    } finally {
                        pipe[1].close()
                    }
                    val fdResult = withContext(Dispatchers.IO) {
                        ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).bufferedReader().readText()
                    }
                    it.execute(SimpleTest()).value + '\n' + it.create(ChannelDemo(), lifecycleScope).toList()
                        .joinToString { it.value } + "\n\n" + fdResult
                }
            } catch (e: Exception) {
                e.printStackTrace()
                e.stackTraceToString()
            }
        }
    }
}
