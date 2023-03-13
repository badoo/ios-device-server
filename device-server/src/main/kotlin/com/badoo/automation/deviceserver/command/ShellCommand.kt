package com.badoo.automation.deviceserver.command

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.util.ensure
import com.zaxxer.nuprocess.internal.LibC
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.*

open class ShellCommand(
    private val commonEnvironment: Map<String, String> = mapOf<String, String>("HOME" to System.getProperty("user.home"), "TMPDIR" to (System.getenv("TMPDIR") ?: "/tmp"))
) : IShellCommand {
    protected val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)
    protected open val logMarker: Marker get() = MapEntriesAppendingMarker(mapOf(LogMarkers.HOSTNAME to "localhost"))
    private val executor = Executors.newCachedThreadPool()

    override fun exec(
        command: List<String>, environment: Map<String, String>, timeOut: Duration,
        returnFailure: Boolean, logMarker: Marker?, processBuilder: ProcessBuilder
    ): CommandResult {
        val commandString = command.joinToString(" ")
        processBuilder.command(command)
        processBuilder.environment().clear()
        processBuilder.environment().putAll(commonEnvironment)
        processBuilder.environment().putAll(environment)

        try {
            val process: Process = processBuilder.start()
            val pid = process.pid()
            val pidLogMarker = MapEntriesAppendingMarker(mapOf("PID" to pid))
            logMarker?.let { pidLogMarker.add(it) }
            logger.debug(pidLogMarker, "Executing command: $commandString, PID: $pid")
            val stdOut = executor.submit(lineReader(process.inputStream))
            val stdErr = executor.submit(lineReader(process.errorStream))

            val hasExited = process.waitFor(timeOut.toMillis(), TimeUnit.MILLISECONDS)

            val exitCode = if (hasExited) {
                process.exitValue()
            } else {
                Int.MIN_VALUE
            }

            if (!hasExited) {
                logger.error(pidLogMarker, "Command has failed to complete in time. Timeout: ${timeOut.toSeconds()} seconds. Command: $commandString, PID: $pid")
                executor.submit {
                    waitForProcessToComplete(process, pidLogMarker, commandString, pid.toInt(), timeOut)
                }
            }

            val result = CommandResult(
                stdOut = stdOut.get(),
                stdErr = stdErr.get(),
                exitCode = exitCode,
                cmd = command, // Store actual command - including ssh stuff.
                pid = pid
            )
            ensure(exitCode == 0 || returnFailure) {
                val errorMessage = "Error while running command: $commandString Result=$result"
                logger.error(pidLogMarker, errorMessage)
                ShellCommandException(errorMessage)
            }
            return result
        } catch (e: IOException) {
            logger.error(logMarker, "Failed to execute command $command. Error: ${e.javaClass} ${e.message}", e)
            val message = e.message ?: "Failed to execute command. ${e.javaClass}"
            return CommandResult(
                stdOut = message,
                stdErr = message,
                exitCode = -1,
                cmd = command,
                pid = -1
            )
        }
    }

    private fun waitForProcessToComplete(
        process: Process,
        logMarker: Marker?,
        commandString: String,
        pid: Int,
        timeOut: Duration
    ) {
        logger.debug(logMarker, "Trying to kill failed command. Command: $commandString, PID: $pid")
        LibC.kill(pid, LibC.SIGTERM)
        process.waitFor(timeOut.toMillis(), TimeUnit.MILLISECONDS)

        if (process.isAlive) {
            logger.debug(logMarker, "Trying to destroy failed command. Command: $commandString, PID: $pid")
            process.destroy()
            process.waitFor(timeOut.toMillis(), TimeUnit.MILLISECONDS)
            logger.debug(logMarker, "Destroyed failed command. Command: $commandString, PID: $pid")
        }

        if (process.isAlive) {
            logger.debug(logMarker, "Trying to destroy forcibly failed command. Command: $commandString, PID: $pid")
            process.destroyForcibly()
            process.waitFor(timeOut.toMillis(), TimeUnit.MILLISECONDS)
            logger.debug(logMarker, "Destroyed forcibly failed command. Command: $commandString, PID: $pid")
        }

        process.waitFor(timeOut.toMillis(), TimeUnit.MILLISECONDS)
    }

    private fun lineReader(inputStream: InputStream): Callable<String> {
        return Callable<String> {
            inputStream.use {
                val inputStreamReader = InputStreamReader(it, StandardCharsets.UTF_8)
                val builder = StringBuilder()
                val reader = BufferedReader(inputStreamReader, 1045696)

                var line: String? = reader.readLine()

                while (line != null) {
                    builder.append(line)
                    builder.append("\n")
                    line = reader.readLine()
                }

                builder.toString()
            }
        }
    }

    override fun startProcess(
        command: List<String>,
        environment: Map<String, String>,
        logMarker: Marker?,
        processBuilder: ProcessBuilder
    ): Process {
        logger.debug(this.logMarker, "Executing command: ${command.joinToString(" ")}")
        processBuilder.command(command)
        processBuilder.environment().clear()
        processBuilder.environment().putAll(commonEnvironment)
        processBuilder.environment().putAll(environment)
        return processBuilder.start()
    }

    override fun escape(value: String): String {
        return value
    }
}
