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
        val holder = getContentProviderExternal(activityManager, authority, userId, providerToken) ?: run {
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
            val accepted = callContentProvider(provider, context.packageName, RootServiceHandoff.METHOD, authority,
                extras)?.getBoolean(RootServiceHandoff.EXTRA_ACCEPTED, false) == true
            if (!accepted) {
                System.err.println("Root service handoff delivery rejected: $authority")
                System.err.flush()
            }
            return accepted
        } finally {
            try {
                removeContentProviderExternal(activityManager, authority, providerToken, userId)
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
        } catch (e: NoSuchMethodException) {
            if (Build.VERSION.SDK_INT >= 26) Logger.me.w("Unexpected missing method", e)
            // API 23-25 exposes ActivityManagerNative.getDefault() instead.
            // https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/app/ActivityManagerNative.java#81
            Class.forName("android.app.ActivityManagerNative").getDeclaredMethod("getDefault")(null)
        }
    }

    private val iActivityManagerClass by lazy { Class.forName("android.app.IActivityManager") }

    private val getContentProviderExternal: (Any, String, Int, Binder) -> Any? by lazy {
        try {
            // Android 10+ signature includes a diagnostic tag.
            // https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/android/app/IActivityManager.aidl#317
            val method = iActivityManagerClass.getDeclaredMethod("getContentProviderExternal",
                String::class.java, Integer.TYPE, IBinder::class.java, String::class.java);
            { me, authority, userId, providerToken -> method(me, authority, userId, providerToken, TAG) }
        } catch (e: NoSuchMethodException) {
            if (Build.VERSION.SDK_INT >= 29) Logger.me.w("Unexpected getContentProviderExternal method missing", e)
            // API 23-28 uses the pre-tag signature.
            // https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/app/IActivityManager.java#145
            val method = iActivityManagerClass.getDeclaredMethod("getContentProviderExternal",
                String::class.java, Integer.TYPE, IBinder::class.java);
            { me, authority, userId, providerToken -> method(me, authority, userId, providerToken) }
        }
    }

    private val removeContentProviderExternal: (Any, String, Binder, Int) -> Unit by lazy {
        try {
            // Android 10+ release path requires the user id.
            // https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/android/app/IActivityManager.aidl#322
            val method = iActivityManagerClass.getDeclaredMethod("removeContentProviderExternalAsUser",
                String::class.java, IBinder::class.java, Integer.TYPE);
            { me, authority, providerToken, userId -> method(me, authority, providerToken, userId) }
        } catch (e: NoSuchMethodException) {
            if (Build.VERSION.SDK_INT >= 29) Logger.me.w("Unexpected removeContentProviderExternal method missing", e)
            // API 23-28 exposes only the calling-user release path.
            // https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/app/IActivityManager.java#148
            val method = iActivityManagerClass.getDeclaredMethod("removeContentProviderExternal",
                String::class.java, IBinder::class.java);
            { me, authority, providerToken, _ -> method(me, authority, providerToken) }
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

    private val callContentProvider: (Any, String, String, String, Bundle) -> Bundle? by lazy {
        val clazz = Class.forName("android.content.IContentProvider")
        when {
            Build.VERSION.SDK_INT >= 31 -> {
                // Android 12+ IContentProvider.call requires AttributionSource.
                // https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/core/java/android/content/IContentProvider.java#123
                val method = clazz.getDeclaredMethod("call", AttributionSource::class.java, String::class.java,
                    String::class.java, String::class.java, Bundle::class.java);
                { provider, _, methodName, authority, extras ->
                    @SuppressLint("NewApi")
                    val source = AttributionSource.myAttributionSource()
                    method(provider, source, authority, methodName, null, extras) as Bundle?
                }
            }
            Build.VERSION.SDK_INT >= 30 -> {
                // Android 11 requires the overload carrying attributionTag and authority.
                // https://android.googlesource.com/platform/frameworks/base/+/android-11.0.0_r1/core/java/android/content/IContentProvider.java#118
                val method = clazz.getDeclaredMethod("call", String::class.java, String::class.java, String::class.java,
                    String::class.java, String::class.java, Bundle::class.java);
                { provider, callingPackage, methodName, authority, extras ->
                    method(provider, callingPackage, null, authority, methodName, null, extras) as Bundle?
                }
            }
            Build.VERSION.SDK_INT >= 29 -> {
                // Android 10 validates authority before dispatching ContentProvider.call.
                // https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/android/content/IContentProvider.java#82
                val method = clazz.getDeclaredMethod("call", String::class.java, String::class.java, String::class.java,
                    String::class.java, Bundle::class.java);
                { provider, callingPackage, methodName, authority, extras ->
                    method(provider, callingPackage, authority, methodName, null, extras) as Bundle?
                }
            }
            else -> {
                // API 21-28 exposes only the pre-authority overload.
                // https://android.googlesource.com/platform/frameworks/base/+/android-5.0.0_r1/core/java/android/content/IContentProvider.java#58
                val method = clazz.getDeclaredMethod("call", String::class.java, String::class.java, String::class.java,
                    Bundle::class.java);
                { provider, callingPackage, methodName, _, extras ->
                    method(provider, callingPackage, methodName, null, extras) as Bundle?
                }
            }
        }
    }
}
