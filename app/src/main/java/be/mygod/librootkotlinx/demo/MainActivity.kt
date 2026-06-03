package be.mygod.librootkotlinx.demo

import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.text.method.ScrollingMovementMethod
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import be.mygod.librootkotlinx.ParcelableBinder
import be.mygod.librootkotlinx.ParcelableString
import be.mygod.librootkotlinx.RootCommand
import be.mygod.librootkotlinx.RootCommandNoResult
import be.mygod.librootkotlinx.RootFlow
import be.mygod.librootkotlinx.io.FileDescriptorByteReadChannel
import be.mygod.librootkotlinx.io.ProcessPipes.Companion.startPipes
import be.mygod.librootkotlinx.io.openReadChannel
import be.mygod.librootkotlinx.io.openWriteChannel
import be.mygod.librootkotlinx.systemContext
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

class MainActivity : ComponentActivity() {
    @Parcelize
    class SimpleTest : RootCommand<ParcelableString> {
        override suspend fun execute() = ParcelableString("context: ${systemContext.packageName}\nuid: ${
            Jni.getuid()}\n" + coroutineScope {
            // Try to execute a restricted subprocess command.
            ProcessBuilder("/system/bin/iptables", "-L", "INPUT").startPipes(false).use { process ->
                val handler = Handler(Looper.getMainLooper())
                var stdout: FileDescriptorByteReadChannel? = null
                var stderr: FileDescriptorByteReadChannel? = null
                try {
                    val stdoutChannel = process.stdout!!.openReadChannel(handler)
                    stdout = stdoutChannel
                    val stderrChannel = process.stderr!!.openReadChannel(handler)
                    stderr = stderrChannel
                    val stdoutText = async { stdoutChannel.readRemaining().readText() }
                    val stderrText = async { stderrChannel.readRemaining().readText() }
                    var output = stdoutText.await() + stderrText.await()
                    when (val exit = process.awaitExit()) {
                        0 -> { }
                        else -> output += "Process exited with $exit"
                    }
                    output
                } finally {
                    stdout?.cancel(null)
                    stderr?.cancel(null)
                }
            }
        })
    }

    @Parcelize
    class FlowDemo : RootFlow<ParcelableString> {
        override fun flow() = flowOf(ParcelableString("Hello"), ParcelableString("World"))
    }

    @Parcelize
    class FileDescriptorDemo(private val output: ParcelFileDescriptor) : RootCommandNoResult {
        override suspend fun execute() = null.also {
            val channel = output.openWriteChannel(Handler(Looper.getMainLooper()))
            try {
                channel.writeFully("fd uid: ${Jni.getuid()}".encodeToByteArray())
                channel.flushAndClose()
            } catch (e: Throwable) {
                channel.cancel(e)
                throw e
            }
        }
    }

    @Parcelize
    class BinderDemo(private val callback: ParcelableBinder) : RootCommand<ParcelableString> {
        override suspend fun execute(): ParcelableString {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                callback.value.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)
                ParcelableString("binder caller uid: ${reply.readInt()}")
            } finally {
                reply.recycle()
                data.recycle()
            }
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
                    val pipe = ParcelFileDescriptor.createPipe()
                    try {
                        it.execute(FileDescriptorDemo(pipe[1]))
                    } finally {
                        pipe[1].close()
                    }
                    val fdResult = pipe[0].openReadChannel(Handler(Looper.getMainLooper()))
                        .readRemaining()
                        .readText()
                    val binder = object : Binder() {
                        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                            if (code == FIRST_CALL_TRANSACTION) {
                                reply?.writeInt(Binder.getCallingUid())
                                return true
                            }
                            return super.onTransact(code, data, reply, flags)
                        }
                    }
                    val binderResult = it.execute(BinderDemo(ParcelableBinder(binder))).value
                    it.execute(SimpleTest()).value + '\n' + it.flow(FlowDemo()).toList()
                        .joinToString { it.value } + "\n\n" + fdResult + "\n" + binderResult
                }
            } catch (e: Exception) {
                e.printStackTrace()
                e.stackTraceToString()
            }
        }
    }
}
