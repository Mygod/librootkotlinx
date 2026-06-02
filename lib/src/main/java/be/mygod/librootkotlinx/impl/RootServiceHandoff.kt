package be.mygod.librootkotlinx.impl

import android.annotation.SuppressLint
import android.content.Context
import android.os.IBinder
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("RestrictedApi")
internal object RootServiceHandoff {
    const val AUTHORITY_ENV = "LIBROOTKOTLINX_HANDOFF_AUTHORITY"
    const val TOKEN_ENV = "LIBROOTKOTLINX_HANDOFF_TOKEN"
    const val METHOD = "be.mygod.librootkotlinx.ROOT_SERVICE_HANDOFF"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_BINDER = "binder"
    const val EXTRA_ACCEPTED = "accepted"

    private const val AUTHORITY_SUFFIX = ".librootkotlinx.root-service-handoff"

    private val pendingTokens = ConcurrentHashMap<String, Pending>()

    fun authority(context: Context) = context.packageName + AUTHORITY_SUFFIX

    fun register(onBinder: (IBinder) -> Boolean): Registration {
        val token = UUID.randomUUID().toString()
        pendingTokens[token] = Pending(onBinder)
        return Registration(token)
    }

    fun deliver(token: String?, binder: IBinder?): Boolean {
        if (token == null || binder == null) return false
        val pending = pendingTokens.remove(token) ?: return false
        return pending.deliver(binder)
    }

    private class Pending(private val onBinder: (IBinder) -> Boolean) {
        fun deliver(binder: IBinder) = onBinder(binder)
    }

    class Registration internal constructor(val token: String) : Closeable {
        override fun close() {
            pendingTokens.remove(token)
        }
    }
}
