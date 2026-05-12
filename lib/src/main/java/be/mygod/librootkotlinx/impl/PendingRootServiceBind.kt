package be.mygod.librootkotlinx.impl

import android.annotation.SuppressLint
import android.content.Intent
import androidx.annotation.MainThread
import com.topjohnwu.superuser.internal.RootServiceManager
import com.topjohnwu.superuser.ipc.RootService

/**
 * Cleans up libsu's private pending bind state when [RootService.bindOrTask] has already queued a startup
 * task, but root shell startup fails before libsu receives the root service manager broadcast.
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
    fun captureQueuedTask() {
        val pendingTasks = pendingTasks
        if (pendingIndex in pendingTasks.indices) pendingTask = pendingTasks[pendingIndex]
    }

    @MainThread
    fun cancel() {
        val pendingTasks = pendingTasks
        // RootServiceManager queues a BindTask lambda at pendingTasks[pendingIndex] before the root process is started.
        // libsu 6.0.0 has no named pending-task class to reflect, so keep the queued task object when bindOrTask returns
        // and remove that object by identity if another pending bind shifted the list.
        // https://github.com/topjohnwu/libsu/blob/4910d8dcc1ea3273246614b356fba56e1ce002a5/service/src/main/java/com/topjohnwu/superuser/internal/RootServiceManager.java#L284-L287
        val pendingTask = pendingTask
        val index = if (pendingTask != null) {
            pendingTasks.indexOfFirst { it === pendingTask }.takeIf { it >= 0 }
        } else pendingIndex.takeIf { it in pendingTasks.indices }
        if (index != null) pendingTasks.removeAt(index)
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

        private val manager get() = RootServiceManager.getInstance()
        @Suppress("UNCHECKED_CAST")
        private val pendingTasks: MutableList<Any> get() = pendingTasksField[manager] as MutableList<Any>
    }
}
