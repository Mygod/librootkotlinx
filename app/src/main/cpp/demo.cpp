#include <jni.h>
#include <unistd.h>

extern "C"
JNIEXPORT jint JNICALL
Java_be_mygod_librootkotlinx_demo_Jni_getuid(JNIEnv *env, jobject thiz) {
    return (jint) getuid();
}
