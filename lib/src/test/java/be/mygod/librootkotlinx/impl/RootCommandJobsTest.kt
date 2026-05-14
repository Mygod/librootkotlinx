package be.mygod.librootkotlinx.impl

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCommandJobsTest {
    @Test
    fun cancelCancelsTrackedJob() {
        val jobs = RootCommandJobs()
        val job = Job()

        jobs.track(1, job)
        jobs.cancel(1)

        assertTrue(job.isCancelled)
    }

    @Test
    fun completedOldJobDoesNotUntrackReplacement() {
        val jobs = RootCommandJobs()
        val first = Job()
        val second = Job()

        jobs.track(1, first)
        jobs.track(1, second)
        assertTrue(first.complete())
        jobs.cancel(1)

        assertFalse(first.isCancelled)
        assertTrue(second.isCancelled)
    }

    @Test
    fun cancelAllCancelsEveryTrackedJob() {
        val jobs = RootCommandJobs()
        val first = Job()
        val second = Job()

        jobs.track(1, first)
        jobs.track(2, second)
        jobs.cancelAll()

        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
    }

    @Test
    fun cancelAllUntracksCanceledJobs() {
        val jobs = RootCommandJobs()
        val first = Job()
        val second = Job()

        jobs.track(1, first)
        jobs.cancelAll()
        jobs.track(1, second)
        jobs.cancel(1)

        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
    }
}
