package be.mygod.librootkotlinx.impl

import android.os.Parcelable
import android.os.RemoteException
import be.mygod.librootkotlinx.ParcelableThrowable
import kotlinx.coroutines.CancellationException
import kotlinx.parcelize.Parcelize

@Parcelize
internal class RootCommandResponse(val status: Int, val payload: Parcelable?) : Parcelable {
    fun readException(classLoader: ClassLoader?) = when (status) {
        EX_THROWABLE -> makeRemoteException((payload as ParcelableThrowable).unwrap(classLoader).cause
            ?: IllegalArgumentException("Missing remote exception cause"))
        EX_PARCELABLE -> makeRemoteException(payload as Throwable)
        else -> IllegalArgumentException("Unexpected result $status")
    }

    companion object {
        const val SUCCESS = 0
        const val EX_THROWABLE = 1
        const val EX_PARCELABLE = 2
        const val COMPLETE = 3

        private fun makeRemoteException(cause: Throwable) = cause as? CancellationException
                ?: RemoteException(cause.message).apply { initCause(cause) }
    }
}
