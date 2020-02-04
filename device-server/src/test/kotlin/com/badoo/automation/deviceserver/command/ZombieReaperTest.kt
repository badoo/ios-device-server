package com.badoo.automation.deviceserver.command

import com.sun.jna.Library
import com.sun.jna.Native
import org.junit.After
import org.junit.Assert
import org.junit.Test
import kotlin.streams.toList

class ZombieReaperTest {
    private val reaper = ZombieReaper()

    @After
    fun tearDown() {
        cleanupChildProcesses()
        assertChildProcessCount(0)
    }

    @Test
    fun testReapZombie() {
        createZombie()
        assertChildProcessCount(1)

        reaper.reapZombies()

        assertChildProcessCount(0)
    }

    @Test
    fun testReapOnlyZombies() {
        createZombie()
        createRegularChildProcess()
        assertChildProcessCount(2)

        reaper.reapZombies()

        assertChildProcessCount(1)
    }

    private fun assertChildProcessCount(expectedCount: Int) {
        Assert.assertEquals(
            "Wrong number of child processes",
            expectedCount.toLong(),
            ProcessHandle.current().children().count()
        )
    }

    private fun cleanupChildProcesses() {
        val pids = ProcessHandle.current().children().map { it.pid().toInt() }.toList()

        pids.forEach { pid ->
            testCLibrary.kill(pid, SIGKILL)
        }

        Thread.sleep(100L) // to ensure process has changed it's state

        reaper.reapZombies()
    }

    private fun createRegularChildProcess(): Int {
        val forkPID = testCLibrary.fork()

        if (forkPID == 0) {
            Thread.sleep(2000L) // ensure no actions are taken by a fork
        }

        return forkPID
    }

    private fun createZombie(): Int {
        val forkPID = testCLibrary.fork()

        if (forkPID == 0) {
            Thread.sleep(2000L) // ensure no actions are taken by a fork
        } else {
            Thread.sleep(100L) // wait until initialized properly
            killProcess(forkPID)
        }

        return forkPID
    }

    private fun killProcess(pid: Int) {
        val signalResult = testCLibrary.kill(pid, SIGKILL)
        Assert.assertEquals("Failed to send SIGKILL to process $pid", 0, signalResult)
        Thread.sleep(50L) // to ensure process has changed it's state
    }

    companion object {
        private const val SIGKILL = 9
        private val testCLibrary: TestCLibrary = Native.load("c", TestCLibrary::class.java)
    }
}

private interface TestCLibrary : Library {
    fun kill(pid: Int, signal: Int): Int
    fun fork(): Int
}
