package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.command.ShellCommand
import com.sun.jna.ptr.IntByReference
import com.zaxxer.nuprocess.internal.LibC
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.streams.toList

class ZombieReaper {
    private val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val executor = Executors.newScheduledThreadPool(1)
    private val commandExecutor = ShellCommand()

    fun launchReapingZombies() {
        executor.scheduleWithFixedDelay(
                { reapZombies(findZombies()) },
                60L,
                60L,
                TimeUnit.SECONDS
        )
    }

    private fun reapZombies(pids: List<Int>) {
        if (pids.isEmpty()) {
            logger.debug("No zombies to reap")
            return
        }

        val executor = Executors.newFixedThreadPool(pids.size)
        val tasks = mutableListOf<Future<*>>()
        pids.forEach { pid ->
            val task: Future<*> = executor.submit {
                reapZombie(pid)
            }
            tasks.add(task)
        }
        executor.shutdown()

        tasks.forEach { it.get() }

        try {
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (e: InterruptedException) {
            println("Failed to awaitTermination while reaping zombiez due to issue. ${e.javaClass.name}, ${e.message}")
        }
    }

    private fun reapZombie(pid: Int) {
        logger.debug("Reaping zombie process $pid.")
        try {
            val exitCode = IntByReference()
            val waitpidRC = LibC.waitpid(pid, exitCode, 0)
            val status = exitCode.value
            val wExitStatus = LibC.WEXITSTATUS(status)
            val cleanExit = waitpidRC == pid && LibC.WIFEXITED(status) && wExitStatus == 0
            logger.debug(MapEntriesAppendingMarker(mapOf("zombiePID" to pid)), "Reaped zombie process $pid. Exit status: $wExitStatus. Exit status is clean: $cleanExit.")
        } catch (t: Throwable) {
            logger.error("Failed to reap zombie process $pid. Error: ${t.javaClass}, ${t.message}", t)
        }
    }

    private fun findZombies(): List<Int> {
        return try {
            val result = commandExecutor.exec(listOf("/bin/ps", "axo", "pid,stat,command"), returnFailure = true)
            val zombies = result.stdOut.lines().filter { it.contains("Z") }
            val zombiesPids = zombies.map {
                it.trim().split(" ").first().trim().toInt()
            }

            val childrenPids = ProcessHandle.current().children().map { it.pid().toInt() }
            val childrenZombiesPids = childrenPids.filter { zombiesPids.contains(it) }.toList()

            logger.debug(MapEntriesAppendingMarker(mapOf("zombies" to childrenZombiesPids.size)), "Found ${childrenZombiesPids.size} zombie processes: ${childrenZombiesPids.joinToString(",")}")
            childrenZombiesPids
        } catch (t: Throwable) {
            logger.error("Failed to find zombie processes. Error: ${t.javaClass}, ${t.message}", t)
            listOf()
        }
    }
}
