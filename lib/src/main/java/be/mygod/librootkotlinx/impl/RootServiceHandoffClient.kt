package be.mygod.librootkotlinx.impl

import android.annotation.SuppressLint
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
 * https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/core/java/android/app/ActivityManager.java#4199
 * https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/app/ActivityManagerNative.java#81
 * https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/android/app/IActivityManager.aidl#317
 * https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/app/IActivityManager.java#145
 * https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/android/app/IActivityManager.aidl#322
 * https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/app/IActivityManager.java#148
 * https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/app/IActivityManager.java#474
 * https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/core/java/android/app/ContentProviderHolder.java#33
 * https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/core/java/android/content/IContentProvider.java#123
 * https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/content/IContentProvider.java#58
 * https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/core/java/android/content/AttributionSource.java#195
 */
@SuppressLint("MissingPermission")
internal object RootServiceHandoffClient {
    private const val TAG = "RootServer"
    private const val PER_USER_RANGE = 100000

    fun handoff(context: Context, authority: String, token: String, serviceBinder: IBinder, targetUid: Int): Boolean {
        val userId = targetUid / PER_USER_RANGE
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
            val result = if (Build.VERSION.SDK_INT >= 31) {
                // Android 12+ IContentProvider.call requires AttributionSource.
                // https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/core/java/android/content/IContentProvider.java#123
                callWithAttributionSource(provider, myAttributionSource(null), authority, RootServiceHandoff.METHOD,
                    null, extras)
            } else {
                // Android 11 and older expose the calling-package overload.
                // https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/content/IContentProvider.java#58
                callLegacy(provider, context.packageName, RootServiceHandoff.METHOD, null, extras)
            } as Bundle?
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
            if (Build.VERSION.SDK_INT >= 29) Logger.me.w("Unexpected getContentProviderExternal method missing", e)
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

    private val iContentProviderClass by lazy { Class.forName("android.content.IContentProvider") }

    private val callLegacy by lazy {
        iContentProviderClass.getDeclaredMethod("call", String::class.java, String::class.java, String::class.java,
            Bundle::class.java)
    }

    private val attributionSourceClass by lazy { Class.forName("android.content.AttributionSource") }

    private val myAttributionSource by lazy { attributionSourceClass.getDeclaredMethod("myAttributionSource") }

    private val callWithAttributionSource by lazy {
        iContentProviderClass.getDeclaredMethod("call", attributionSourceClass, String::class.java, String::class.java,
            String::class.java, Bundle::class.java)
    }
}
