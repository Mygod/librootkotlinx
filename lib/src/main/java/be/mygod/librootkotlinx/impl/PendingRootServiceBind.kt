package be.mygod.librootkotlinx.impl

import android.annotation.SuppressLint
import android.content.Intent
import android.content.ServiceConnection
import androidx.annotation.MainThread
import com.topjohnwu.superuser.internal.RootServiceManager
import com.topjohnwu.superuser.ipc.RootService

/**
 * Cleans up libsu's private pending bind state when [RootService.bindOrTask] has already queued a startup
 * task, but root shell startup fails before libsu receives the root service manager broadcast.
 *
 * References:
 * - [RootServiceManager.REMOTE_EN_ROUTE]: https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/src/main/java/com/topjohnwu/superuser/internal/RootServiceManager.java#L98-L99
 * - [RootServiceManager.flags]: https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/src/main/java/com/topjohnwu/superuser/internal/RootServiceManager.java#L141-L143
 * - [RootServiceManager.createBindTask]: https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/src/main/java/com/topjohnwu/superuser/internal/RootServiceManager.java#L284-L292
 */
internal class PendingRootServiceBind @MainThread constructor(
    intent: Intent,
    private val connection: ServiceConnection,
) {
    private val enRouteFlag = if (intent.hasCategory(RootService.CATEGORY_DAEMON_MODE)) {
        DAEMON_EN_ROUTE
    } else {
        REMOTE_EN_ROUTE
    }
    val ownsStartupIfQueued: Boolean = flagsField.getInt(manager) and enRouteFlag == 0
    private val pendingIndex: Int = pendingTasks.size

    @MainThread
    fun cancel() {
        val pendingTasks = pendingTasks
        if (pendingIndex in pendingTasks.indices && pendingTasks[pendingIndex].references(connection)) {
            pendingTasks.removeAt(pendingIndex)
        } else pendingTasks.removeAll { it.references(connection) }
        flagsField.setInt(manager, flagsField.getInt(manager) and enRouteFlag.inv())
    }

    companion object {
        private const val REMOTE_EN_ROUTE = 1 shl 0
        private const val DAEMON_EN_ROUTE = 1 shl 1

        private val pendingTasksField by lazy {
            RootServiceManager::class.java.getDeclaredField("pendingTasks").apply { isAccessible = true }
        }
        private val flagsField by lazy {
            RootServiceManager::class.java.getDeclaredField("flags").apply { isAccessible = true }
        }

        private val manager @SuppressLint("RestrictedApi") get() = RootServiceManager.getInstance()
        @Suppress("UNCHECKED_CAST")
        private val pendingTasks: MutableList<Any> get() = pendingTasksField[manager] as MutableList<Any>

        private fun Any.references(value: Any): Boolean {
            var clazz: Class<*>? = javaClass
            while (clazz != null) {
                for (field in clazz.declaredFields) {
                    if (field.type.isPrimitive) continue
                    field.isAccessible = true
                    if (field.get(this) === value) return true
                }
                clazz = clazz.superclass
            }
            return false
        }
    }
}
