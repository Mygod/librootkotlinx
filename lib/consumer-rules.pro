# RootProcessLauncher names RootProcessMain on the root app_process command line.
-if class be.mygod.librootkotlinx.impl.RootProcessLauncher
-keep class be.mygod.librootkotlinx.impl.RootProcessMain {
    public static void main(java.lang.String[]);
}
