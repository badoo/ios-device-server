package com.badoo.automation.deviceserver.command

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.util.ensure
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
    private val commonEnvironment: Map<String, String> = mapOf<String, String>("HOME" to System.getProperty("user.home"))
) : IShellCommand {
    protected val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)
    protected open val logMarker: Marker get() = MapEntriesAppendingMarker(mapOf(LogMarkers.HOSTNAME to "localhost"))

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
            val stdOutBuilder = StringBuilder()
            val stdErrBuilder = StringBuilder()

            val outputReaderExecutor = Executors.newVirtualThreadPerTaskExecutor()

            val stdOutReader = outputReaderExecutor.submit(readStream(process.inputStream, stdOutBuilder))
            val stdErrReader = outputReaderExecutor.submit(readStream(process.errorStream, stdErrBuilder))

            val hasExited = process.waitFor(timeOut.toMillis(), TimeUnit.MILLISECONDS)

            val exitCode = if (hasExited) {
                process.exitValue()
            } else {
                Int.MIN_VALUE
            }

            if (!hasExited) {
                logger.error(pidLogMarker, "Command has failed to complete in time. Timeout: ${timeOut.toSeconds()} seconds. Command: $commandString, PID: $pid")
                destroyProcess(process, pidLogMarker, commandString, pid)
                stdOutReader.cancel(true)
                stdErrReader.cancel(true)
            }

            outputReaderExecutor.shutdown()

            try {
                outputReaderExecutor.awaitTermination(5, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                outputReaderExecutor.shutdownNow()
                Thread.currentThread().interrupt()
            }


            val result = CommandResult(
                stdOut = stdOutBuilder.toString(),
                stdErr = stdErrBuilder.toString(),
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

    private fun destroyProcess(
        process: Process,
        logMarker: Marker?,
        commandString: String,
        pid: Long,
        destroyTimeOutNanos: Long = Duration.ofSeconds(5).toNanos(),
    ) {
        logger.debug(logMarker, "Trying to kill failed command with SIGTERM. Command: $commandString, PID: $pid")
        process.destroy()

        val isDestroyedSuccess: Boolean = process.waitFor(destroyTimeOutNanos, TimeUnit.NANOSECONDS)

        if (isDestroyedSuccess) {
            logger.debug(logMarker, "SIGTERM was a success. Process exited OK. Command: $commandString, PID: $pid")
        } else {
            logger.debug(logMarker, "SIGTERM was ignored. Process has NOT exited. Command: $commandString, PID: $pid. Will send SIGKILL")

            process.destroyForcibly().waitFor()

            val forceDestroyStartTime = System.nanoTime()

            while (process.isAlive && (System.nanoTime() - forceDestroyStartTime) < destroyTimeOutNanos) {
                Thread.sleep(50)
            }

            if (process.isAlive) {
                logger.error(logMarker, "Process did not terminate after SIGKILL PID: $pid")
            } else {
                logger.debug(logMarker, "Process destroyed with SIGKILL PID: $pid.")
            }
        }
    }

    private fun readStream(inputStream: InputStream, stringBuilder: StringBuilder): FutureTask<Unit> {
        return FutureTask(Callable<Unit> {
            try {
                BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8), 1045696).use { reader ->
                    var line: String
                    while ((reader.readLine().also { line = it }) != null) {
                        stringBuilder.append(line).append("\n")
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        })
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
