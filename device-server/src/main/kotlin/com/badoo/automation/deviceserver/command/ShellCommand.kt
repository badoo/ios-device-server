package com.badoo.automation.deviceserver.command

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.util.ensure
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.StringBuilder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

open class ShellCommand(
        private val commonEnvironment: Map<String, String>
    ) : IShellCommand {
    protected val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)
    protected open val logMarker: Marker get() = MapEntriesAppendingMarker(mapOf(LogMarkers.HOSTNAME to "localhost"))
    private val executor = ThreadPoolExecutor(60, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, SynchronousQueue<Runnable>());

    override fun exec(command: List<String>, environment: Map<String, String>, timeOut: Duration,
                      returnFailure: Boolean, logMarker: Marker?, processBuilder: ProcessBuilder): CommandResult {
        val commandString = command.joinToString(" ")
        logger.debug(this.logMarker, "Executing command: $commandString")
        processBuilder.command(command)
        processBuilder.environment().clear()
        processBuilder.environment().putAll(commonEnvironment)
        processBuilder.environment().putAll(environment)

        val process: Process = processBuilder.start()
        val stdOut = executor.submit(lineReader(process.inputStream))
        val stdErr = executor.submit(lineReader(process.errorStream))
        val finishedSuccessfully = process.waitFor(timeOut.toMillis(), TimeUnit.MILLISECONDS)

        val exitCode = if (finishedSuccessfully) {
            process.exitValue()
        } else {
            Int.MIN_VALUE
        }

        if (exitCode == Int.MIN_VALUE) { // waiting timed out
            logger.error(logMarker, "Command has failed to complete in time. Command: $commandString")

            try {
                process.destroy()
            } catch (e: RuntimeException) {
                logger.warn(logMarker, "Error while terminating command: $commandString", e)
            }
            try {
                process.destroyForcibly()
            } catch (e: RuntimeException) {
                logger.warn(logMarker, "Error while terminating command forcibly: $commandString", e)
            }
        }

        val result = CommandResult(
                stdOut = stdOut.get(),
                stdErr = stdErr.get(),
                exitCode = exitCode,
                cmd = command // Store actual command - including ssh stuff.
        )
        ensure(exitCode == 0 || returnFailure) {
            val errorMessage = "Error while running command: $commandString Result=$result"
            logger.error(logMarker, errorMessage)
            ShellCommandException(errorMessage)
        }
        return result
    }

    private fun lineReader(inputStream: InputStream): Callable<String> {
        return Callable<String> {
            inputStream.use {
                val inputStreamReader = InputStreamReader(it, StandardCharsets.UTF_8)
                val builder = StringBuilder()
                val reader = BufferedReader(inputStreamReader, 65356)

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

    override fun startProcess(command: List<String>, environment: Map<String, String>, logMarker: Marker?, processBuilder: ProcessBuilder ): Process {
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
