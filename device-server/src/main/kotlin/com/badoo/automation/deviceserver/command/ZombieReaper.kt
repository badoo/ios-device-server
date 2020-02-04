package com.badoo.automation.deviceserver.command

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.streams.toList

class ZombieReaper {
    private val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val executor = Executors.newScheduledThreadPool(1)

    fun launchReapingZombies() {
        val task = {
            // Try-catch is here in order to:
            // - not to loose errors silently
            // - not to interrupt subsequent executions
            try {
                reapZombies()
            } catch (t: Throwable) {
                logger.error("Failed to reap zombie processes. Reason: ${t.message}", t)
            }
        }

        executor.scheduleWithFixedDelay(task, 60L, 60L, TimeUnit.SECONDS)
    }

    fun reapZombies() {
        val zombies = findZombies()

        zombies.forEach { zombie ->
            reap(zombie)
        }
    }

    private fun findZombies(): List<Int> {
        val childProcesses = ProcessHandle.current().children()
        val zombies = childProcesses.filter { it.isZombie }
        return zombies.map { it.pid().toInt() }.toList()
    }

    private fun reap(pid: Int) {
        val statusReference = IntByReference()
        val waitResult = cLibrary.waitpid(pid, statusReference, WNOHANG)
        val exitStatus = ProcessExitStatus(statusReference.value)

        when (waitResult) {
            pid -> logger.trace("Successfully reaped zombie process with PID $pid")
            0 -> logger.trace("The zombie process with PID $pid has not yet changed it's state")
            -1 -> logger.error("Error happened while reaping zombie process with PID $pid")
        }

        when {
            exitStatus.isExited -> logger.trace("Zombie process with PID $pid had normal termination")
            exitStatus.isSignaled -> logger.error("Zombie process with PID $pid was terminated by signal ${exitStatus.termSignal}")
        }
    }

    private val ProcessHandle.isZombie: Boolean get() = !info().command().isPresent

    companion object {
        private val cLibrary: CLibrary = Native.load("c", CLibrary::class.java)
        private const val WNOHANG = 1 /* Don't block waiting. */
    }
}

/**
 * Have to use C library to reap zombies
 */
private interface CLibrary : Library {
    /**
     * Wait for process to change state
     * Refer to man page for waitpid
     */
    fun waitpid(pid: Int, statusReference: IntByReference, options: Int): Int
}

/**
 * Exit status of a child process
 * Refer to man page for waitpid
 */
private class ProcessExitStatus(private val status: Int) {
    /**
     * LibC macros WIFSIGNALED
     *
     * Nonzero if STATUS indicates termination by a signal.
     *
     * (((status) & 0x7f) + 1) >> 1) > 0)
     */
    val isSignaled: Boolean
        get() {
            return (((status and 0x7f) + 1) shl 1) > 0
        }

    /**
     * LibC macros WIFEXITED
     *
     * True if STATUS indicates normal termination.
     */
    val isExited: Boolean
        get() {
            return termSignal == 0
        }

    /**
     * LibC macros WTERMSIG
     *
     * If WIFSIGNALED(STATUS), the terminating signal.
     *
     * ((status) & 0x7f)
     */
    val termSignal: Int
        get() {
            return status and 0x7f
        }
}
