package be.mygod.librootkotlinx.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootProcessExitTest {
    @Test
    fun codesAreUniqueAndDoNotOverlapShellExecFailures() {
        val codes = RootProcessExit.entries.map { it.code }

        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.all { it in 1..125 })
    }

    @Test
    fun lookupIsScopedToStartupPhase() {
        for (exit in RootProcessExit.entries) {
            assertEquals(exit, RootProcessExit.fromCode(exit.code, exit.phase))
            assertNull(RootProcessExit.fromCode(exit.code, when (exit.phase) {
                RootProcessExit.Phase.BEFORE_STARTUP_MARKER -> RootProcessExit.Phase.AFTER_STARTUP_MARKER
                RootProcessExit.Phase.AFTER_STARTUP_MARKER -> RootProcessExit.Phase.BEFORE_STARTUP_MARKER
            }))
        }
        assertNull(RootProcessExit.fromCode(1, RootProcessExit.Phase.BEFORE_STARTUP_MARKER))
        assertNull(RootProcessExit.fromCode(137, RootProcessExit.Phase.AFTER_STARTUP_MARKER))
    }
}
