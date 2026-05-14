package be.mygod.librootkotlinx.impl

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.RemoteException
import be.mygod.librootkotlinx.RootCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootCommandServiceTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun commandFailureResponseFallsBackWhenRichFailureCannotBeDelivered() = runTest {
        assertFailureResponseFallsBack(RemoteException("rich failure too large"))
    }

    @Test
    fun commandFailureResponseFallsBackWhenRichFailureCannotBeParceled() = runTest {
        assertFailureResponseFallsBack(RuntimeException("rich failure cannot be parceled"))
    }

    private suspend fun assertFailureResponseFallsBack(firstFailure: Throwable) {
        val service = RootCommandService()
        val callback = FailingFirstResponseCallback(firstFailure)

        service.binder().execute(7, RootCommandRequest(FailingCommand), callback)
        val response = callback.fallbackResponse.await()

        assertEquals(2, callback.responseCalls)
        assertEquals(7, callback.responseId)
        assertEquals(RootCommandResponse.EX_THROWABLE, response.status)
    }

    private class FailingFirstResponseCallback(private val firstFailure: Throwable) : IRootCommandCallback.Default() {
        val fallbackResponse = CompletableDeferred<RootCommandResponse>()
        var responseCalls = 0
        var responseId = -1L

        override fun onResponse(id: Long, response: RootCommandResponse) {
            ++responseCalls
            responseId = id
            if (responseCalls == 1) throw firstFailure
            fallbackResponse.complete(response)
        }

        override fun asBinder(): IBinder? = null
    }

    private object FailingCommand : RootCommand<Parcelable?> {
        override suspend fun execute(): Parcelable? = throw IllegalArgumentException("boom")
        override fun describeContents() = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    }

    private fun RootCommandService.binder(): IRootCommandService =
        RootCommandService::class.java.getDeclaredField("binder").let {
            it.isAccessible = true
            it.get(this) as IRootCommandService
        }
}
