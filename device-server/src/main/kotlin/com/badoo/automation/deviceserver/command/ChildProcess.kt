package com.badoo.automation.deviceserver.command

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.host.Remote
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.RuntimeException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.*

class ChildProcess private constructor(
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
    private val poolExecutor: ExecutorService
    private val stdOutTask: Future<*>
    private val stdErrTask: Future<*>

    init {
        logger.debug(logMarker, "Starting long living process from command [$command]")
        process = executor.startProcess(command, commandEnvironment)

        poolExecutor = Executors.newVirtualThreadPerTaskExecutor()
        stdOutTask = poolExecutor.submit(readStream(process.inputStream, outWriter))
        stdErrTask = poolExecutor.submit(readStream(process.errorStream, errWriter))

        logger.debug(logMarker, "Started long living process $this from command [$command]")
    }

    override fun toString(): String = "< PID: ${process.pid()}>"

    fun isAlive(): Boolean = process.isAlive

    fun kill() {
        logger.debug(logMarker, "Sending SIGTERM to process $this")

        destroyProcess(process, logMarker, command.joinToString(" "), process.pid())
        stdOutTask.cancel(true)
        stdErrTask.cancel(true)
        poolExecutor.shutdown()

        try {
            poolExecutor.awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            poolExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    private fun readStream(inputStream: InputStream, writer: ((line: String) -> Unit)?): FutureTask<Unit> {
        return FutureTask(Callable<Unit> {
            try {
                BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8), 1045696).use { reader ->
                    var line: String
                    while ((reader.readLine().also { line = it }) != null) {
                        writer?.invoke(line)
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        })
    }

    private fun destroyProcess(
        process: Process,
        logMarker: Marker?,
        commandString: String,
        pid: Long,
        destroyTimeOutNanos: Long = Duration.ofSeconds(5).toNanos(),
    ) {
        logger.debug(logMarker, "Trying to kill command with SIGTERM. Command: $commandString, PID: $pid")
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

