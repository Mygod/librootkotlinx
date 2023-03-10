package be.mygod.librootkotlinx.demo

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import be.mygod.librootkotlinx.ParcelableInt
import be.mygod.librootkotlinx.ParcelableString
import be.mygod.librootkotlinx.RootCommand
import be.mygod.librootkotlinx.RootCommandChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

class MainActivity : ComponentActivity() {
    @Parcelize
    class GetRoot : RootCommand<ParcelableInt> {
        override suspend fun execute() = ParcelableInt(android.os.Process.myUid())
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
        lifecycleScope.launch {
            findViewById<TextView>(android.R.id.text1).text = try {
                val result = App.rootManager.use {
                    it.execute(GetRoot()).value.toString() + "\n" +
                            it.create(ChannelDemo(), lifecycleScope).toList().joinToString { it.value }
                }
                "Got result from root: $result"
            } catch (e: Exception) {
                e.printStackTrace()
                e.stackTraceToString()
            }
        }
    }
}
