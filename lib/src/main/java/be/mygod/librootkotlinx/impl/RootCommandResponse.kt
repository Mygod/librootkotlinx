package be.mygod.librootkotlinx.impl

import android.os.Bundle
import android.os.Parcelable
import android.os.RemoteException
import be.mygod.librootkotlinx.ParcelableThrowable
import kotlinx.coroutines.CancellationException
import kotlinx.parcelize.Parcelize

/**
 * Binder envelope that keeps command payloads opaque until callbacks can decode them with the caller class loader.
 *
 * Keep [status] outside [payload]. On API < 33, reading a key from a flat [Bundle] unparcels the
 * whole map with the Bundle's current class loader, so reading status first would deserialize the
 * command payload before [RootCommandCallbacks] can supply the caller class loader.
 */
@Parcelize
internal class RootCommandResponse private constructor(val status: Int, private val payload: Bundle?) : Parcelable {
    fun readPayload(classLoader: ClassLoader?): Parcelable? = payload?.readPayload(classLoader)
    override fun describeContents() = payload?.describeContents() ?: 0

    fun readException(classLoader: ClassLoader?) = when (status) {
        EX_THROWABLE -> {
            val responsePayload = payload?.readPayload(ParcelableThrowable::class.java.classLoader)
            makeRemoteException((responsePayload as ParcelableThrowable).unwrap(classLoader).cause
                ?: IllegalArgumentException("Missing remote exception cause"))
        }
        EX_PARCELABLE -> makeRemoteException(readPayload(classLoader) as Throwable)
        else -> IllegalArgumentException("Unexpected result $status")
    }

    companion object {
        const val SUCCESS = 0
        const val EX_THROWABLE = 1
        const val EX_PARCELABLE = 2
        const val COMPLETE = 3

        private const val PAYLOAD = "payload"

        fun success(payload: Parcelable?) = RootCommandResponse(SUCCESS, payload?.bundle())
        fun failure(payload: ParcelableThrowable) = RootCommandResponse(EX_THROWABLE, payload.bundle())
        fun parcelableFailure(payload: Parcelable) = RootCommandResponse(EX_PARCELABLE, payload.bundle())
        val complete by lazy { RootCommandResponse(COMPLETE, null) }

        private fun makeRemoteException(cause: Throwable) = cause as? CancellationException
                ?: RemoteException(cause.message).apply { initCause(cause) }

        private fun Parcelable.bundle() = Bundle(1).also { it.putParcelable(PAYLOAD, this) }

        private fun Bundle.readPayload(classLoader: ClassLoader?): Parcelable? {
            setClassLoader(classLoader ?: RootCommandResponse::class.java.classLoader)
            @Suppress("DEPRECATION")
            return getParcelable(PAYLOAD)
        }
    }
}
