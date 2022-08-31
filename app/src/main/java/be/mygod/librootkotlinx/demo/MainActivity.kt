package be.mygod.librootkotlinx.demo

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import be.mygod.librootkotlinx.ParcelableInt
import be.mygod.librootkotlinx.RootCommand
import kotlinx.parcelize.Parcelize

class MainActivity : ComponentActivity() {
    @Parcelize
    class GetRoot : RootCommand<ParcelableInt> {
        override suspend fun execute() = ParcelableInt(android.os.Process.myUid())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        lifecycleScope.launchWhenStarted {
            findViewById<TextView>(android.R.id.text1).text = try {
                "Got result from root: ${App.rootManager.use { it.execute(GetRoot()) }.value}"
            } catch (e: Exception) {
                e.printStackTrace()
                "${e.message}\n${e.stackTraceToString()}"
            }
        }
    }
}
