package com.badoo.automation.deviceserver.command

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.host.Remote
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

class SubProcess private constructor(
    private val command: List<String>,
    executor: IShellCommand,
    remoteHostname: String,
    commandEnvironment: Map<String, String> = mapOf(),
    outWriter: ((line: String) -> Unit)?,
    errWriter: ((line: String) -> Unit)?
) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(mapOf(LogMarkers.HOSTNAME to remoteHostname))
    private val process: Process
    private val stdOutTask: Future<*>
    private val stdErrTask: Future<*>

    init {
        logger.debug(logMarker, "Starting long living process from command [$command]")
        process = executor.startProcess(command, commandEnvironment)

        stdOutTask = ShellCommand.outErrReaderExecutor.submit(readStream(process.inputStream, outWriter))
        stdErrTask = ShellCommand.outErrReaderExecutor.submit(readStream(process.errorStream, errWriter))

        logger.debug(logMarker, "Started long living process $this from command [$command]")
    }

    override fun toString(): String = "< PID: ${process.pid()}>"

    fun isAlive(): Boolean = process.isAlive

    fun kill() {
        logger.debug(logMarker, "Sending SIGTERM to process $this")
        ShellCommand.destroyProcess(process, logMarker, command.joinToString(" "), process.pid(), logger)
        stdOutTask.cancel(true)
        stdErrTask.cancel(true)
    }

    private fun readStream(inputStream: InputStream, writer: ((line: String) -> Unit)?): FutureTask<Unit> {
        return FutureTask {
            try {
                BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8), 1045696).use { reader ->
                    var line: String
                    while ((reader.readLine().also { line = it }) != null) {
                        writer?.invoke(line)
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

    companion object {
        fun fromCommand(
            remoteHost: String,
            userName: String,
            cmd: List<String>,
            commandEnvironment: Map<String, String>,
            out_reader: ((line: String) -> Unit)?,
            err_reader: ((line: String) -> Unit)?
        ): SubProcess {
            val executor = Remote.getLocalCommandExecutor()
            return SubProcess(
                command = cmd,
                commandEnvironment = commandEnvironment,
                executor = executor,
                remoteHostname = remoteHost,
                outWriter = out_reader,
                errWriter = err_reader
            )
        }
    }
}

