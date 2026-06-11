package be.mygod.librootkotlinx.impl

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import be.mygod.librootkotlinx.io.fdPath
import be.mygod.librootkotlinx.io.openReadChannel
import java.io.Closeable

internal class RootProcessStartupMarker : Closeable {
    private var markerRead: ParcelFileDescriptor?
    private var markerWrite: ParcelFileDescriptor?

    val markerPath get() = fdPath(checkNotNull(markerWrite))

    init {
        val marker = ParcelFileDescriptor.createPipe()
        markerRead = marker[0]
        markerWrite = marker[1]
    }

    fun openMarkerReadChannel() =
        checkNotNull(markerRead).openReadChannel(Handler(Looper.getMainLooper())).also { markerRead = null }

    fun closeMarkerWrite() {
        markerWrite?.close()
        markerWrite = null
    }

    override fun close() {
        markerRead?.close()
        markerRead = null
        closeMarkerWrite()
    }
}
