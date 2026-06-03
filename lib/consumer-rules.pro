# RootProcessLauncher names RootProcessBootstrap on the root app_process command line, then Bootstrap reflectively calls
# RootProcessMain after the framework creates the package class loader. Class names come from class literals and may be
# obfuscated; only the reflected main methods need stable names.
-if class be.mygod.librootkotlinx.impl.RootProcessLauncher
-keep,allowobfuscation class be.mygod.librootkotlinx.impl.RootProcessBootstrap
-if class be.mygod.librootkotlinx.impl.RootProcessLauncher
-keepclassmembers class be.mygod.librootkotlinx.impl.RootProcessBootstrap {
    public static void main(java.lang.String[]);
}
-if class be.mygod.librootkotlinx.impl.RootProcessLauncher
-keep,allowobfuscation class be.mygod.librootkotlinx.impl.RootProcessMain
-if class be.mygod.librootkotlinx.impl.RootProcessLauncher
-keepclassmembers class be.mygod.librootkotlinx.impl.RootProcessMain {
    public static void main(android.content.Context, int, java.lang.String, java.lang.String);
}
