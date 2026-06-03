package be.mygod.librootkotlinx.impl

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import be.mygod.librootkotlinx.Logger

/**
 * Root-side direct provider handoff for the root command-service Binder.
 *
 * A root app_process has no app caller record for normal ContentResolver acquisition, so it mirrors platform external
 * provider acquisition/release directly. This is not libsu RootServiceManager behavior; it deliberately keeps the owned
 * direct Binder handoff instead of libsu's manager Binder and broadcast path.
 *
 * Platform sources:
 * https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/core/java/android/content/AttributionSource.java#195
 */
@SuppressLint("MissingPermission")
internal object RootServiceHandoffClient {
    private const val TAG = "RootServer"

    fun handoff(context: Context, authority: String, token: String, serviceBinder: IBinder, userId: Int): Boolean {
        if (Build.VERSION.SDK_INT < 29 && userId != 0) {
            // API 23-28 removeContentProviderExternal releases UserHandle.getCallingUserId(); root is always user 0.
            // https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/services/core/java/com/android/server/am/ActivityManagerService.java#11743
            System.err.println("Root service handoff provider for user $userId is unsupported on API " +
                    "${Build.VERSION.SDK_INT}: platform cannot release the acquired external provider")
            System.err.flush()
            return false
        }
        val providerToken = Binder()
        val (getContentProviderExternal, getContentProviderExternalNew) = getContentProviderExternal
        val holder = if (getContentProviderExternalNew) {
            getContentProviderExternal(activityManager, authority, userId, providerToken, TAG)
        } else {
            getContentProviderExternal(activityManager, authority, userId, providerToken)
        } ?: run {
            System.err.println("Root service handoff provider unavailable: $authority")
            System.err.flush()
            return false
        }
        try {
            val provider = contentProviderHolderProvider[holder] ?: run {
                System.err.println("Root service handoff provider binder missing: $authority")
                System.err.flush()
                return false
            }
            val extras = Bundle().apply {
                putString(RootServiceHandoff.EXTRA_TOKEN, token)
                putBinder(RootServiceHandoff.EXTRA_BINDER, serviceBinder)
            }
            val result = IContentProvider.compat.call(provider,
                context.packageName, RootServiceHandoff.METHOD, authority, extras)
            val accepted = result?.getBoolean(RootServiceHandoff.EXTRA_ACCEPTED, false) == true
            if (!accepted) {
                System.err.println("Root service handoff delivery rejected: $authority")
                System.err.flush()
            }
            return accepted
        } finally {
            try {
                val (removeContentProviderExternal, removeContentProviderExternalNew) = removeContentProviderExternal
                if (removeContentProviderExternalNew) {
                    removeContentProviderExternal(activityManager, authority, providerToken, userId)
                } else removeContentProviderExternal(activityManager, authority, providerToken)
            } catch (e: Exception) {
                Logger.me.w("Failed to release root service handoff provider", e)
            }
        }
    }

    private val activityManager by lazy {
        try {
            // Android 8+ exposes ActivityManager.getService().
            // https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/core/java/android/app/ActivityManager.java#4199
            Class.forName("android.app.ActivityManager").getDeclaredMethod("getService")(null)
        } catch (_: NoSuchMethodException) {
            // API 23-25 exposes ActivityManagerNative.getDefault() instead.
            // https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/app/ActivityManagerNative.java#81
            Class.forName("android.app.ActivityManagerNative").getDeclaredMethod("getDefault")(null)
        }
    }

    private val iActivityManagerClass by lazy { Class.forName("android.app.IActivityManager") }

    private val getContentProviderExternal by lazy {
        try {
            // Android 10+ signature includes a diagnostic tag.
            // https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/android/app/IActivityManager.aidl#317
            iActivityManagerClass.getDeclaredMethod("getContentProviderExternal", String::class.java, Integer.TYPE,
                IBinder::class.java, String::class.java) to true
        } catch (e: NoSuchMethodException) {
            if (Build.VERSION.SDK_INT >= 29) Logger.me.w("Unexpected getContentProviderExternal method missing", e)
            // API 23-28 uses the pre-tag signature.
            // https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/app/IActivityManager.java#145
            iActivityManagerClass.getDeclaredMethod("getContentProviderExternal", String::class.java, Integer.TYPE,
                IBinder::class.java) to false
        }
    }

    private val removeContentProviderExternal by lazy {
        try {
            // Android 10+ release path requires the user id.
            // https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/android/app/IActivityManager.aidl#322
            iActivityManagerClass.getDeclaredMethod("removeContentProviderExternalAsUser", String::class.java,
                IBinder::class.java, Integer.TYPE) to true
        } catch (e: NoSuchMethodException) {
            if (Build.VERSION.SDK_INT >= 29) Logger.me.w("Unexpected removeContentProviderExternal method missing", e)
            // API 23-28 exposes only the calling-user release path.
            // https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/app/IActivityManager.java#148
            iActivityManagerClass.getDeclaredMethod("removeContentProviderExternal", String::class.java,
                IBinder::class.java) to false
        }
    }

    private val contentProviderHolderProvider by lazy {
        // API 23-25 keeps ContentProviderHolder nested in IActivityManager; API 26+ exposes the top-level class.
        // https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/app/IActivityManager.java#474
        // https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/core/java/android/app/ContentProviderHolder.java#33
        Class.forName(if (Build.VERSION.SDK_INT >= 26) {
            "android.app.ContentProviderHolder"
        } else "android.app.IActivityManager\$ContentProviderHolder").getDeclaredField("provider").apply {
            isAccessible = true
        }
    }

    sealed class IContentProvider {
        abstract fun call(provider: Any, callingPackage: String, method: String, authority: String, extras: Bundle): Bundle?

        companion object {
            val compat get() = when {
                Build.VERSION.SDK_INT >= 31 -> Api31
                Build.VERSION.SDK_INT >= 30 -> Api30
                Build.VERSION.SDK_INT >= 29 -> Api29
                else -> Api21
            }

            private val clazz by lazy { Class.forName("android.content.IContentProvider") }
        }

        // API 21-28 exposes only the pre-authority overload.
        // https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/content/IContentProvider.java#58
        object Api21 : IContentProvider() {
            private val call by lazy {
                clazz.getDeclaredMethod("call", String::class.java, String::class.java, String::class.java,
                    Bundle::class.java)
            }
            override fun call(provider: Any, callingPackage: String, method: String, authority: String, extras: Bundle) =
                call(provider, callingPackage, method, null, extras) as Bundle?
        }

        // Android 10 validates authority before dispatching ContentProvider.call.
        // https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/android/content/IContentProvider.java#82
        object Api29 : IContentProvider() {
            private val call by lazy {
                clazz.getDeclaredMethod("call", String::class.java, String::class.java, String::class.java,
                    String::class.java, Bundle::class.java)
            }
            override fun call(provider: Any, callingPackage: String, method: String, authority: String, extras: Bundle) =
                call(provider, callingPackage, authority, method, null, extras) as Bundle?
        }

        // Android 11 requires the overload carrying attributionTag and authority.
        // https://android.googlesource.com/platform/frameworks/base/+/android-11.0.0_r1/core/java/android/content/IContentProvider.java#118
        object Api30 : IContentProvider() {
            private val call by lazy {
                clazz.getDeclaredMethod("call", String::class.java, String::class.java, String::class.java,
                    String::class.java, String::class.java, Bundle::class.java)
            }
            override fun call(provider: Any, callingPackage: String, method: String, authority: String, extras: Bundle) =
                call(provider, callingPackage, null, authority, method, null, extras) as Bundle?
        }

        // Android 12+ IContentProvider.call requires AttributionSource.
        // https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/core/java/android/content/IContentProvider.java#123
        @SuppressLint("NewApi")
        object Api31 : IContentProvider() {
            private val call by lazy {
                clazz.getDeclaredMethod("call", AttributionSource::class.java, String::class.java, String::class.java,
                    String::class.java, Bundle::class.java)
            }
            override fun call(provider: Any, callingPackage: String, method: String, authority: String, extras: Bundle) =
                call(provider, AttributionSource.myAttributionSource(), authority, method, null, extras) as Bundle?
        }
    }
}
