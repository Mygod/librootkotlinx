package be.mygod.librootkotlinx

import android.os.Parcelable
import androidx.annotation.RequiresApi
import dalvik.system.BaseDexClassLoader
import kotlinx.parcelize.Parcelize
import java.io.File

@Parcelize
@RequiresApi(23)
data class JniInit(private val nativeDirs: List<File> = nativeLibraryDirs) : RootCommandNoResult {
    companion object {
        private var initialized = false
        @Suppress("JAVA_CLASS_ON_COMPANION")
        private val nativeLibraryDirs by lazy {
            val pathList = BaseDexClassLoader::class.java.getDeclaredField("pathList").apply {
                isAccessible = true
            }.get(javaClass.classLoader)
            @Suppress("UNCHECKED_CAST")
            pathList.javaClass.getDeclaredField("nativeLibraryDirectories").apply {
                isAccessible = true
            }.get(pathList) as ArrayList<File>
        }
    }

    override suspend fun execute(): Parcelable? {
        if (!initialized) {
            nativeLibraryDirs.addAll(nativeDirs)
            initialized = true
        }
        return null
    }
}
