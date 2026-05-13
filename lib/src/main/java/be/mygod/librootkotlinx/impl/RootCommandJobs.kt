package be.mygod.librootkotlinx.impl

import androidx.collection.MutableLongObjectMap
import kotlinx.coroutines.Job

internal class RootCommandJobs {
    private val jobs = MutableLongObjectMap<Job>()

    fun track(id: Long, job: Job) {
        synchronized(this) { jobs[id] = job }
        job.invokeOnCompletion { synchronized(this) { jobs.remove(id, job) } }
    }

    fun cancel(id: Long) = synchronized(this) { jobs[id] }?.cancel()

    fun cancelAll() {
        val snapshot = ArrayList<Job>()
        synchronized(this) {
            jobs.forEachValue { snapshot.add(it) }
            jobs.clear()
        }
        snapshot.forEach { it.cancel() }
    }
}
