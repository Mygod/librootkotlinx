-if public class be.mygod.librootkotlinx.RootServer {
    private void doInit(android.content.Context, boolean, java.lang.String);
}
-keep class be.mygod.librootkotlinx.RootServer {
    public static void main(java.lang.String[]);
}

# Strip out debugging stuffs
-assumenosideeffects class be.mygod.librootkotlinx.AppProcess {
	boolean hasStartupAgents*(android.content.Context) return false;
}
-assumenosideeffects class android.os.Debug {
	public static boolean isDebuggerConnected() return false;
}
