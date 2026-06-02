package be.mygod.librootkotlinx.impl

import android.os.IBinder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class RootServiceHandoffTest {
    @Test
    fun deliverAcceptsOneShotToken() {
        val binder = proxy(IBinder::class.java)
        var delivered: IBinder? = null
        val registration = RootServiceHandoff.register {
            delivered = it
            true
        }

        assertTrue(RootServiceHandoff.deliver(registration.token, binder))
        assertSame(binder, delivered)
        assertFalse(RootServiceHandoff.deliver(registration.token, binder))
    }

    @Test
    fun deliverRejectsMissingTokenOrBinder() {
        assertFalse(RootServiceHandoff.deliver(null, proxy(IBinder::class.java)))

        val registration = RootServiceHandoff.register { true }
        try {
            assertFalse(RootServiceHandoff.deliver(registration.token, null))
        } finally {
            registration.close()
        }
    }

    private companion object {
        fun <T : Any> proxy(type: Class<T>): T = checkNotNull(type.cast(Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type),
        ) { _, method, _ -> defaultValue(method) }))

        fun defaultValue(method: Method): Any? = when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> 0.toChar()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0F
            java.lang.Double.TYPE -> 0.0
            else -> null
        }
    }
}
