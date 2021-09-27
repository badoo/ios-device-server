package com.badoo.automation.deviceserver.command

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.host.Remote
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.RuntimeException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.*

class ChildProcess private constructor(
    command: List<String>,
    executor: IShellCommand,
    remoteHostname: String,
    commandEnvironment: Map<String, String> = mapOf(),
    outWriter: ((line: String) -> Unit)?,
    errWriter: ((line: String) -> Unit)?
) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(mapOf(LogMarkers.HOSTNAME to remoteHostname))
    private val process: Process
    private val poolExecutor: ExecutorService
    val stdOutTask: Future<*>
    val stdErrTask: Future<*>

    init {
        logger.debug(logMarker, "Starting long living process from command [$command]")
        process = executor.startProcess(command, commandEnvironment)

        poolExecutor = Executors.newFixedThreadPool(2)
        stdOutTask = poolExecutor.submit(lineReader(process.inputStream, outWriter))
        stdErrTask = poolExecutor.submit(lineReader(process.errorStream, errWriter))

        logger.debug(logMarker, "Started long living process $this from command [$command]")
    }

    val onExit: CompletableFuture<Process> = process.onExit()

    override fun toString(): String = "< PID: ${process.pid()}>"

    fun isAlive(): Boolean = process.isAlive
    private val processDestroyTimeOut = Duration.ofSeconds(15)

    fun kill() {
        logger.debug(logMarker, "Sending SIGTERM to process $this")
        try {
            process.destroy()
            val exited = process.waitFor(processDestroyTimeOut.seconds, TimeUnit.SECONDS)
            if (!exited) {
                logger.warn(logMarker, "Process $this did not terminate gracefully within [${processDestroyTimeOut.seconds}] seconds. Sending SIGKILL")
                process.destroyForcibly()
            }
        } catch (e: RuntimeException) {
            logger.error(logMarker, "Error while terminating process $this. ${e.message}", e)
        }
    }

    private fun lineReader(inputStream: InputStream, writer: ((line: String) -> Unit)?): Runnable {
        return Runnable {
            inputStream.use { stream ->
                val inputStreamReader = InputStreamReader(stream, StandardCharsets.UTF_8)
                val reader = BufferedReader(inputStreamReader, 65356)

                var line: String? = reader.readLine()

                while (line != null) {
                    writer?.invoke(line)
                    line = reader.readLine()
                }
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
        ): ChildProcess {
            val executor = Remote.getRemoteCommandExecutor(hostName = remoteHost, userName = userName)
            return ChildProcess(
                command = cmd,
                commandEnvironment = commandEnvironment,
                executor = executor,
                remoteHostname = remoteHost,
                outWriter = out_reader,
                errWriter = err_reader
            )
        }
        fun fromLocalCommand(
            remoteHost: String,
            userName: String,
            cmd: List<String>,
            commandEnvironment: Map<String, String>,
            out_reader: ((line: String) -> Unit)?,
            err_reader: ((line: String) -> Unit)?
        ): ChildProcess {
            return ChildProcess(
                command = cmd,
                commandEnvironment = commandEnvironment,
                executor = Remote.getLocalCommandExecutor(),
                remoteHostname = remoteHost,
                outWriter = out_reader,
                errWriter = err_reader
            )
        }
    }
}

