package com.badoo.automation.deviceserver.command

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.host.Remote
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

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
    private val poolExecutor = Executors.newFixedThreadPool(2)
    private val process: Process
    private val stdOutTask: Future<*>
    private val stdErrTask: Future<*>

    init {
        logger.debug(logMarker, "Starting long living process from command [$command]")
        process = executor.startProcess(command, commandEnvironment)
        stdOutTask = poolExecutor.submit(streamReader(process.inputStream, outWriter))
        stdErrTask = poolExecutor.submit(streamReader(process.errorStream, errWriter))
        logger.debug(logMarker, "Started long living process $this from command [$command]")
    }

    override fun toString(): String = "< PID: ${process.pid()}>"

    fun isAlive(): Boolean = process.isAlive

    fun kill() {
        logger.debug(logMarker, "Sending SIGTERM to process $this")
        process.destroy()

        val exited = process.waitFor(PROCESS_TIMEOUT, TimeUnit.SECONDS)

        if (!exited) {
            logger.warn(logMarker, "Process $this did not terminate gracefully within $PROCESS_TIMEOUT seconds. Sending SIGKILL")
            process.destroyForcibly()
        }
    }

    private fun streamReader(inputStream: InputStream, writer: ((line: String) -> Unit)?): Runnable {
        return Runnable { inputStream.use { readStream(it, writer) } }
    }

    private fun readStream(stream: InputStream, writer: ((line: String) -> Unit)?) {
        val reader = InputStreamReader(stream, StandardCharsets.UTF_8)
        val stringBuilder = StringBuilder(BUFFER_SIZE)

        while (true) {
            val bytes = reader.read()

            if (bytes == EOF) {
                writeString(writer, stringBuilder.toString())
                break
            }

            val char = bytes.toChar()

            if (char == NEWLINE) {
                writeString(writer, stringBuilder.toString())
                stringBuilder.clear()
            } else {
                stringBuilder.append(char)
            }
        }
    }

    private fun writeString(writer: ((line: String) -> Unit)?, string: String) {
        if (writer == null || string.isEmpty()) {
            return
        }

        writer(string)
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

        private const val EOF = -1
        private const val NEWLINE = '\n'
        private const val PROCESS_TIMEOUT = 15L
        private const val BUFFER_SIZE = 65536 // seems arbitrary, but using buffer with this size fastest (jmh) on multiple small commands
    }
}

private fun StringBuilder.clear() = setLength(0)
