package com.badoo.automation.deviceserver.command

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.util.ensure
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.*
import kotlin.system.measureNanoTime

open class ShellCommand(
    private val commonEnvironment: Map<String, String> = mapOf<String, String>("HOME" to System.getProperty("user.home"))
) : IShellCommand {
    protected val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)
    protected open val logMarker: Marker get() = MapEntriesAppendingMarker(mapOf(LogMarkers.HOSTNAME to "localhost"))

    override fun exec(
        command: List<String>, environment: Map<String, String>, timeOut: Duration,
        returnFailure: Boolean, logMarker: Marker?, processBuilder: ProcessBuilder
    ): CommandResult {
        val fullCommand = mutableListOf(
            "/usr/bin/nice", "-n", "10"
        )
        fullCommand.addAll(command)
        processBuilder.command(fullCommand)
        processBuilder.environment().clear()
        processBuilder.environment().putAll(commonEnvironment)
        processBuilder.environment().putAll(environment)

        val process: Process = processBuilder.start()
        val pid = process.pid()

        val commandString = fullCommand.joinToString(" ")
        val processLogMarker = MapEntriesAppendingMarker(mapOf("PID" to pid, "command" to commandString))
        logMarker?.let { processLogMarker.add(it) }

        val stdOutBuilder = StringBuilder()
        val stdErrBuilder = StringBuilder()

        val stdOutReader: Future<*> = outErrReaderExecutor.submit(streamReader(process.inputStream, stdOutBuilder))
        val stdErrReader: Future<*> = outErrReaderExecutor.submit(streamReader(process.errorStream, stdErrBuilder))

        val startTime = System.nanoTime()
        try {
            val hasExited = process.waitFor(timeOut.toMillis(), TimeUnit.MILLISECONDS)
            val elapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)

            val exitCode = if (hasExited) {
                process.exitValue()
            } else {
                Int.MIN_VALUE
            }

            processLogMarker.add(
                MapEntriesAppendingMarker(
                    mapOf(
                        "exit_code" to exitCode, "elapsed_time_ms" to elapsedTime
                    )
                )
            )

            if (hasExited) {
                val outReadingTimeNanos = measureNanoTime {
                    waitForStreamCompletion(stdOutReader, processLogMarker, commandString, pid)
                    waitForStreamCompletion(stdErrReader, processLogMarker, commandString, pid)
                }

                val outReadingTimeMillis = TimeUnit.NANOSECONDS.toMillis(outReadingTimeNanos)

                if (outReadingTimeMillis > 10) {
                    logger.debug(processLogMarker, "Stream reading completed later than command exited. Command: $commandString, PID: $pid. Took: ${outReadingTimeMillis}ms")
                }

                if (exitCode == 0) {
                    logger.debug(processLogMarker, "Command completed successfully. Command: $commandString, PID: $pid. Took: ${elapsedTime}ms")
                } else {
                    logger.error(processLogMarker, "Command completed with non-zero exit code. Command: $commandString, PID: $pid. Took: ${elapsedTime}ms. Exit Code: $exitCode")
                }
            } else {
                val stackTrace = Thread.currentThread().stackTrace.joinToString("\n")
                processLogMarker.add(MapEntriesAppendingMarker(mapOf("stack_trace" to stackTrace)))
                logger.error(processLogMarker, "Command has failed to complete in time. Timeout: ${timeOut.toSeconds()} seconds. Command: $commandString, PID: $pid")
                destroyProcess(process, processLogMarker, commandString, pid, logger)
                stdOutReader.cancel(true)
                stdErrReader.cancel(true)
            }

            val result = CommandResult(
                stdOut = stdOutBuilder.toString(),
                stdErr = stdErrBuilder.toString(),
                exitCode = exitCode,
                cmd = fullCommand,
                pid = pid
            )
            ensure(exitCode == 0 || returnFailure) {
                val errorMessage = "Error while running command: $commandString Result=$result"
                logger.error(processLogMarker, errorMessage)
                ShellCommandException(errorMessage)
            }
            return result
        } catch (e: InterruptedException) {
            val elapsedInterruptedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)
            val timeoutMessage = if (elapsedInterruptedTime > timeOut.toMillis()) {
                "Elapsed time is greater than expected timeout: ${elapsedInterruptedTime - timeOut.toMillis()} ms. i.e. interrupt came after timeout"
            } else {
                "Elapsed time is less than expected timeout: ${elapsedInterruptedTime - timeOut.toMillis()} ms. i.e. interrupt came sooner than expected"
            }

            logger.error(logMarker, "Got InterruptedException, while executing command $commandString. Will destroy process $pid. Error: ${e.javaClass} . $timeoutMessage", e)

            destroyProcess(process, processLogMarker, commandString, pid, logger)
            stdOutReader.cancel(true)
            stdErrReader.cancel(true)

            Thread.currentThread().interrupt()

            return CommandResult(
                stdOut = stdOutBuilder.toString(),
                stdErr = stdErrBuilder.toString(),
                exitCode = Int.MIN_VALUE,
                cmd = fullCommand,
                pid = pid
            )
        }
    }

    private fun waitForStreamCompletion(futureTask: Future<*>, logMarker: Marker?, commandString: String, pid: Long) {
        val timeout = 60L
        try {
            futureTask.get(timeout, TimeUnit.SECONDS) // Wait for the stream reader to finish
        } catch (e: TimeoutException) {
            logger.error(logMarker, "Timeout while waiting for stream reader to finish after $timeout seconds. Command: $commandString, PID: $pid")
        } catch (e: ExecutionException) {
            logger.error(logMarker, "Error while executing stream reader. Command: $commandString, PID: $pid", e)
        }
    }

    private fun streamReader(inputStream: InputStream, stringBuilder: StringBuilder): FutureTask<Unit> {
        return FutureTask {
            try {
                inputStream.reader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(524288)
                    var charsRead: Int
                    while (reader.read(buffer).also { charsRead = it } != EOF) {
                        stringBuilder.append(buffer, 0, charsRead)
                    }
                }
            } catch (e: IOException) {
                logger.error(logMarker, "Got IOException while reading from stream. Error: ${e.javaClass} ${e.message}", e)
            } catch (e: InterruptedException) {
                logger.error(logMarker, "Got InterruptedException while reading from stream. Error: ${e.javaClass} ${e.message}", e)
                Thread.currentThread().interrupt()
            }
        }
    }

    override fun startProcess(
        command: List<String>, environment: Map<String, String>, logMarker: Marker?, processBuilder: ProcessBuilder
    ): Process {
        val fullCommand = mutableListOf(
            "/usr/bin/nice", "-n", "10"
        )
        fullCommand.addAll(command)
        logger.debug(this.logMarker, "Executing command: ${fullCommand.joinToString(" ")}")
        processBuilder.command(fullCommand)
        processBuilder.environment().clear()
        processBuilder.environment().putAll(commonEnvironment)
        processBuilder.environment().putAll(environment)
        return processBuilder.start()
    }

    override fun escape(value: String): String {
        return value
    }

    companion object {
        private const val EOF = -1
        val outErrReaderExecutor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

        fun destroyProcess(
            process: Process,
            logMarker: Marker?,
            commandString: String,
            pid: Long,
            logger: Logger,
            destroyTimeOutNanos: Long = Duration.ofSeconds(10).toNanos(),
        ) {
            logger.debug(logMarker, "Sending SIGTERM to command: $commandString, PID: $pid")
            process.destroy()

            val isDestroyedGracefully: Boolean = process.waitFor(destroyTimeOutNanos, TimeUnit.NANOSECONDS)

            if (isDestroyedGracefully) {
                logger.debug(logMarker, "SIGTERM was a success. Process exited OK. Command: $commandString, PID: $pid")
            } else {
                logger.debug(logMarker, "SIGTERM was ignored. Process has NOT exited. Command: $commandString, PID: $pid. Will send SIGKILL")

                process.destroyForcibly().waitFor()

                val forceDestroyEndTime = System.nanoTime() + destroyTimeOutNanos

                while (process.isAlive && System.nanoTime() < forceDestroyEndTime ) {
                    Thread.sleep(50)
                }

                if (process.isAlive) {
                    logger.error(logMarker, "Process did not terminate after SIGKILL PID: $pid")
                } else {
                    logger.debug(logMarker, "Process destroyed with SIGKILL PID: $pid.")
                }
            }
        }
    }
}
