package be.mygod.librootkotlinx

import android.os.Parcel
import android.os.Parcelable
import be.mygod.librootkotlinx.impl.RootCommandResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ParcelableThrowableTest {
    @Test
    fun parcelableThrowableUnwrapsOriginalCause() {
        val cause = ParcelableFailure("parcelable failure")
        val exception = RootCommandResponse(
            RootCommandResponse.EX_THROWABLE,
            ParcelableThrowable(cause),
        ).readException(ParcelableFailure::class.java.classLoader)

        assertSame(cause, exception.cause)
    }

    @Test
    fun serializableThrowableUsesSuppliedClassLoader() {
        val loader = TrackingClassLoader(SerializableFailure::class.java.classLoader)
        val exception = RootCommandResponse(
            RootCommandResponse.EX_THROWABLE,
            ParcelableThrowable(SerializableFailure("serializable failure")),
        ).readException(loader)

        assertTrue(exception.cause is SerializableFailure)
        assertEquals("serializable failure", exception.cause?.message)
        assertTrue(loader.requestedClasses.contains(SerializableFailure::class.java.name))
    }

    @Test
    fun nonSerializableThrowableFallbackReturnsRemoteCause() {
        val exception = RootCommandResponse(
            RootCommandResponse.EX_THROWABLE,
            ParcelableThrowable(NonSerializableFailure()),
        ).readException(NonSerializableFailure::class.java.classLoader)
        val cause = exception.cause

        assertTrue(cause is RuntimeException)
        assertFalse(cause is NonSerializableFailure)
        assertTrue(cause?.message.orEmpty().contains(NonSerializableFailure::class.java.name))
    }

    private class ParcelableFailure(message: String) : RuntimeException(message), Parcelable {
        override fun describeContents() = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = throw AssertionError("Unused")
    }

    private class SerializableFailure(message: String) : RuntimeException(message)

    private class NonSerializableFailure : RuntimeException() {
        @Suppress("unused")
        private val payload = Any()
    }

    private class TrackingClassLoader(parent: ClassLoader?) : ClassLoader(parent) {
        val requestedClasses = ArrayList<String>()

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            requestedClasses.add(name)
            return super.loadClass(name, resolve)
        }
    }
}
