package be.mygod.librootkotlinx

import android.os.Parcelable
import android.os.RemoteException
import kotlinx.parcelize.Parcelize
import java.io.ByteArrayOutputStream
import java.io.NotSerializableException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass

/**
 * More robust Parcelable implementation for Throwable.
 */
sealed class ParcelableThrowable : Parcelable {
    @Parcelize
    internal data class Direct(val t: Parcelable) : ParcelableThrowable() {
        override fun unwrap(classLoader: ClassLoader?) = RemoteException().apply { initCause(t as Throwable) }
    }

    @Parcelize
    internal data class Serialized(val b: ByteArray) : ParcelableThrowable() {
        override fun unwrap(classLoader: ClassLoader?) = RemoteException().apply {
            initCause(parseSerializable(b, classLoader) as Throwable)
        }
    }

    @Parcelize
    internal data class Other(val s: String) : ParcelableThrowable() {
        override fun unwrap(classLoader: ClassLoader?) = RemoteException().apply {
            initCause(parseThrowable(s, classLoader))
        }
    }

    abstract fun unwrap(classLoader: ClassLoader? = ParcelableThrowable::class.java.classLoader): RemoteException

    companion object {
        internal fun parseSerializable(b: ByteArray, classLoader: ClassLoader?) =
            object : ObjectInputStream(b.inputStream()) {
                override fun resolveClass(desc: ObjectStreamClass) = try {
                    Class.forName(desc.name, false, classLoader)
                } catch (e: ClassNotFoundException) {
                    Class.forName(desc.name, false, ParcelableThrowable::class.java.classLoader)
                }
            }.readObject()

        private fun initException(targetClass: Class<*>, message: String): Throwable {
            @Suppress("NAME_SHADOWING")
            var targetClass = targetClass
            while (true) {
                try {
                    // try to find a message constructor
                    return targetClass.getDeclaredConstructor(String::class.java).newInstance(message) as Throwable
                } catch (_: ReflectiveOperationException) { }
                targetClass = targetClass.superclass
            }
        }
        internal fun parseThrowable(s: String, classLoader: ClassLoader?): Throwable {
            val name = s.split(':', limit = 2)[0]
            return initException(try {
                classLoader?.loadClass(name)
            } catch (_: ClassNotFoundException) {
                null
            } ?: Class.forName(name), s)
        }
    }
}

fun ParcelableThrowable(t: Throwable) = if (t is Parcelable) ParcelableThrowable.Direct(t) else try {
    ParcelableThrowable.Serialized(ByteArrayOutputStream().apply {
        ObjectOutputStream(this).use { it.writeObject(t) }
    }.toByteArray())
} catch (_: NotSerializableException) {
    ParcelableThrowable.Other(t.stackTraceToString())
}
