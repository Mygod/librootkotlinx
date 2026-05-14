# RootProcessLauncher swaps libsu's generated RootServerMain command for our stdio-preserving entry point.
-if class be.mygod.librootkotlinx.impl.libsu.RootProcessLauncher
-keep class be.mygod.librootkotlinx.impl.libsu.RootProcessMain {
    public static void main(java.lang.String[]);
}

# PendingRootServiceBind reflects libsu's queued bind task to undo only our own failed startup task.
-if class be.mygod.librootkotlinx.impl.libsu.PendingRootServiceBind
-keepnames class com.topjohnwu.superuser.internal.RootServiceManager
-if class be.mygod.librootkotlinx.impl.libsu.PendingRootServiceBind
-keepclassmembernames class com.topjohnwu.superuser.internal.RootServiceManager {
	private int flags;
	private java.util.List pendingTasks;
}
