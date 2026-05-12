# RootProcessLauncher swaps libsu's generated RootServerMain command for our stdio-preserving entry point.
-if class be.mygod.librootkotlinx.impl.RootProcessLauncher
-keepclassmembers class be.mygod.librootkotlinx.impl.RootProcessMain {
    public static void main(java.lang.String[]);
}

# PendingRootServiceBind reflects libsu's queued bind task to undo only our own failed startup task.
-if class be.mygod.librootkotlinx.impl.PendingRootServiceBind
-keepnames class com.topjohnwu.superuser.internal.RootServiceManager
-if class be.mygod.librootkotlinx.impl.PendingRootServiceBind
-keepnames class com.topjohnwu.superuser.internal.RootServiceManager$PendingBindTask
-if class be.mygod.librootkotlinx.impl.PendingRootServiceBind
-keepclassmembernames class com.topjohnwu.superuser.internal.RootServiceManager {
	private int flags;
	private java.util.List pendingTasks;
}
-if class be.mygod.librootkotlinx.impl.PendingRootServiceBind
-keepclassmembernames class com.topjohnwu.superuser.internal.RootServiceManager$PendingBindTask {
	private android.content.ServiceConnection conn;
}
