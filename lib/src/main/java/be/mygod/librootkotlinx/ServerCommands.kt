package be.mygod.librootkotlinx

import android.os.Parcelable
import androidx.annotation.MainThread
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.Flow

interface RootCommand<Result : Parcelable?> : Parcelable {
    /**
     * If a throwable was thrown, it will be wrapped in RemoteException only if it implements [Parcelable].
     */
    @MainThread
    suspend fun execute(): Result
}

typealias RootCommandNoResult = RootCommand<Parcelable?>

/**
 * Execute a command and discard its result, even if an exception occurs.
 *
 * Use [RootCommandNoResult] and return null for almost all commands that do not return data. Use this only for
 * intentionally detached work where the caller does not need completion, failure, or cancellation feedback.
 */
@DelicateCoroutinesApi
interface RootCommandOneWay : Parcelable {
    @MainThread
    suspend fun execute()
}

interface RootFlow<T : Parcelable?> : Parcelable {
    /**
     * Returns a cold flow. Each client collection starts one root-side collection.
     */
    @MainThread
    fun flow(): Flow<T>
}
