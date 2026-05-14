package be.mygod.librootkotlinx.impl.libsu

import android.annotation.SuppressLint
import android.content.Intent
import androidx.annotation.MainThread
import com.topjohnwu.superuser.internal.RootServiceManager
import com.topjohnwu.superuser.ipc.RootService

/**
 * Reflection shim for libsu's private pending RootService bind state.
 *
 * This mirrors RootServiceManager's en-route flags and pendingTasks list around createBindTask. librootkotlinx changes
 * the ownership boundary by calling [RootService.bindOrTask] and running the returned Shell.Task itself. If this bind is
 * closed before libsu receives the root service manager broadcast, [cancel] removes only this bind's queued task; only
 * the bind that owned shell startup also clears the matching en-route flag. Keep this file limited to that libsu
 * reflection workaround.
 */
@SuppressLint("RestrictedApi")
internal class PendingRootServiceBind @MainThread constructor(intent: Intent) {
    private val enRouteFlag = if (intent.hasCategory(RootService.CATEGORY_DAEMON_MODE)) {
        DAEMON_EN_ROUTE
    } else REMOTE_EN_ROUTE
    // Mirrors RootServiceManager.createBindTask's en-route guard: if the relevant bit was clear before bindOrTask
    // queues this request, this bind owns starting the root process and must undo that queued state on startup failure.
    // https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/src/main/java/com/topjohnwu/superuser/internal/RootServiceManager.java#L284-L292
    val ownsStartupIfQueued = flagsField.getInt(manager) and enRouteFlag == 0
    private val pendingIndex = pendingTasks.size
    private var pendingTask: Any? = null

    @MainThread
    fun captureQueuedTask(): Boolean {
        val pendingTasks = pendingTasks
        if (pendingIndex in pendingTasks.indices) {
            pendingTask = pendingTasks[pendingIndex]
            return true
        }
        return false
    }

    @MainThread
    fun cancel(clearEnRoute: Boolean) {
        // RootServiceManager queues a BindTask lambda at pendingTasks[pendingIndex] before the root process is started.
        // libsu 6.0.0 has no named pending-task class to reflect, so keep the queued task object when bindOrTask returns
        // or throws after queuing it, and remove that captured object if another pending bind shifted the list. Without
        // a captured task, the original index no longer proves ownership and must not be removed.
        // https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/src/main/java/com/topjohnwu/superuser/internal/RootServiceManager.java#L284-L287
        pendingTask?.let { pendingTasks.remove(it) }
        if (clearEnRoute) flagsField.setInt(manager, flagsField.getInt(manager) and enRouteFlag.inv())
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

        private val manager get() = RootServiceManager.getInstance()
        @Suppress("UNCHECKED_CAST")
        private val pendingTasks: MutableList<Any> get() = pendingTasksField[manager] as MutableList<Any>
    }
}
