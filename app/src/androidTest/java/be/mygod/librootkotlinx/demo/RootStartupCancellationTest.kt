package be.mygod.librootkotlinx.demo

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class RootStartupCancellationTest {
    @Test
    fun cancelledStartupDoesNotPoisonNextLaunch() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val first = instrumentation.launchDemoActivity()
        first.finishOnMain(instrumentation)

        val second = instrumentation.launchDemoActivity()
        try {
            val output = waitForRootOutput(instrumentation, second.findViewById(android.R.id.text1))
            assertTrue(output, output.contains("uid: 0"))
            assertTrue(output, output.contains("Hello, World"))
            assertTrue(output, output.contains("fd uid: 0"))
            assertTrue(output, output.contains("binder caller uid: 0"))
        } finally {
            second.finishOnMain(instrumentation)
        }
    }

    private fun Instrumentation.launchDemoActivity() = startActivitySync(
        Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    ) as MainActivity

    private fun Activity.finishOnMain(instrumentation: Instrumentation) {
        instrumentation.runOnMainSync { finish() }
        instrumentation.waitForIdleSync()
    }

    private suspend fun waitForRootOutput(instrumentation: Instrumentation, textView: TextView): String = withTimeout(
        20_000,
    ) {
        while (true) {
            val output = textView.textOnMain(instrumentation)
            if (output.contains("uid: 0")) return@withTimeout output
            if (output.isNoRootFailure()) throw AssertionError("Root shell is required for this connected test:\n$output")
            delay(100)
        }
        throw AssertionError("Unreachable")
    }

    private fun TextView.textOnMain(instrumentation: Instrumentation): String {
        var result = ""
        instrumentation.runOnMainSync { result = text.toString() }
        return result
    }

    private fun String.isNoRootFailure() = contains("Root shell is not available")
}
