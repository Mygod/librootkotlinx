package be.mygod.librootkotlinx.impl

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import be.mygod.librootkotlinx.Logger
import be.mygod.librootkotlinx.io.fdPath
import be.mygod.librootkotlinx.io.openReadChannel
import java.io.Closeable
import java.io.IOException

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
        close(markerWrite)
        markerWrite = null
    }

    override fun close() {
        close(markerRead)
        close(markerWrite)
        markerRead = null
        markerWrite = null
    }

    private fun close(closeable: Closeable?) {
        if (closeable != null) try {
            closeable.close()
        } catch (e: IOException) {
            Logger.me.w("Failed to close root process resource", e)
        }
    }
}
