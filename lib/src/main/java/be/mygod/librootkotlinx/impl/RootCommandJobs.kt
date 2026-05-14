package be.mygod.librootkotlinx.impl

import androidx.collection.MutableLongObjectMap
import kotlinx.coroutines.Job

internal class RootCommandJobs {
    private var jobs = MutableLongObjectMap<Job>()

    fun track(id: Long, job: Job) {
        synchronized(this) { jobs[id] = job }
        job.invokeOnCompletion { synchronized(this) { jobs.remove(id, job) } }
    }

    fun cancel(id: Long) = synchronized(this) { jobs[id] }?.cancel()

    fun cancelAll() {
        val cancelling = synchronized(this) { jobs.also { jobs = MutableLongObjectMap() } }
        cancelling.forEachValue { it.cancel() }
    }
}
