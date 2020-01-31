package com.badoo.automation.deviceserver.command

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.util.ensure
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

open class ShellCommand(
    private val commonEnvironment: Map<String, String>
) : IShellCommand {
    protected val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)
    protected open val logMarker: Marker get() = MapEntriesAppendingMarker(mapOf(LogMarkers.HOSTNAME to "localhost"))
    private val executor = Executors.newCachedThreadPool()

    override fun exec(command: List<String>, environment: Map<String, String>, timeOut: Duration,
                      returnFailure: Boolean, logMarker: Marker?, processBuilder: ProcessBuilder): CommandResult {
        processBuilder.command(command)
        setEnvironment(processBuilder, environment)

        val process: Process = processBuilder.start()
        val stdOut = executor.submit(streamReader(process.inputStream))
        val stdErr = executor.submit(streamReader(process.errorStream))
        val pid = process.pid()

        val commandString = command.joinToString(" ")
        logger.debug(MapEntriesAppendingMarker(mapOf("PID" to pid)), "Executing command: $commandString, PID: $pid")

        val hasExited = process.waitFor(timeOut.toMillis(), TimeUnit.MILLISECONDS)
        val exitCode = if (hasExited) process.exitValue() else Int.MIN_VALUE

        if (!hasExited) {
            logger.error(logMarker, "Command has failed to complete in time. Command: $commandString, PID: $pid")
            executor.submit { waitForProcessToComplete(process, logMarker, commandString, pid.toInt(), timeOut) }
        }

        val result = CommandResult(
            stdOut = stdOut.get(),
            stdErr = stdErr.get(),
            exitCode = exitCode,
            cmd = command,
            pid = pid
        )

        ensure(exitCode == 0 || returnFailure) {
            val errorMessage = "Error while running command: $commandString Result=$result"
            logger.error(logMarker, errorMessage)
            ShellCommandException(errorMessage)
        }

        return result
    }

    override fun startProcess(command: List<String>, environment: Map<String, String>, logMarker: Marker?,
                              processBuilder: ProcessBuilder): Process {
        logger.debug(this.logMarker, "Executing command: ${command.joinToString(" ")}")
        processBuilder.command(command)
        setEnvironment(processBuilder, environment)
        return processBuilder.start()
    }

    override fun escape(value: String): String {
        return value
    }

    private fun setEnvironment(processBuilder: ProcessBuilder, environment: Map<String, String>) {
        processBuilder.environment().clear() // do not inherit current process environment
        processBuilder.environment().putAll(commonEnvironment)
        processBuilder.environment().putAll(environment)
    }

    private fun streamReader(inputStream: InputStream): Callable<String> {
        return Callable { readStream(inputStream) }
    }

    private fun readStream(inputStream: InputStream): String {
        val reader = InputStreamReader(inputStream, StandardCharsets.UTF_8)
        val writer = StringBuilder(BUFFER_SIZE)
        val buffer = CharArray(BUFFER_SIZE)

        try {
            while (true) {
                val charCount = reader.read(buffer)

                if (charCount == EOF) break

                writer.append(buffer, 0, charCount)
            }
        } catch (e: IOException) {
            logger.error("Failed to read from input stream", e)
        } finally {
            inputStream.close()
        }

        return writer.toString()
    }

    private fun waitForProcessToComplete(process: Process, logMarker: Marker?, commandString: String, pid: Int, timeOut: Duration) {
        terminateProcessGracefully(process, logMarker, commandString, pid, timeOut)
        terminateProcessForcibly(logMarker, commandString, pid, process, timeOut)
    }

    private fun terminateProcessGracefully(process: Process, logMarker: Marker?, commandString: String, pid: Int, timeOut: Duration) {
        if (!process.isAlive) return

        logger.debug(logMarker, "Trying to destroy failed command. Command: $commandString, PID: $pid")
        process.destroy()
        process.waitFor(timeOut.toMillis(), TimeUnit.MILLISECONDS)
    }

    private fun terminateProcessForcibly(logMarker: Marker?, commandString: String, pid: Int, process: Process, timeOut: Duration) {
        if (!process.isAlive) return

        logger.debug(logMarker, "Trying to destroy failed command forcibly. Command: $commandString, PID: $pid")
        process.destroyForcibly()
        process.waitFor(timeOut.toMillis(), TimeUnit.MILLISECONDS)
    }

    companion object {
        private const val EOF = -1
        private const val BUFFER_SIZE = 65536 // seems arbitrary, but using buffer with this size fastest (jmh) on multiple small commands
    }
}
