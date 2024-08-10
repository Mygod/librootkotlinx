package be.mygod.librootkotlinx.demo

object Jni {
    init {
        System.loadLibrary("demo")
    }
    external fun getuid(): Int
}
