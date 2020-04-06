package com.badoo.automation.deviceserver.ios.simulator.diagnostic

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.Future

class OsLog(
    private val remote: IRemote,
    private val udid: UDID,
    val osLogFile: File = File.createTempFile("iOS_SysLog_${udid}_", ".log"),
    val osLogStderr: File = File.createTempFile("iOS_SysLog_${udid}_", ".err.log")
) {
    private var outWritingTask: Future<*>? = null
    private var errWritingTask: Future<*>? = null
    private var osLogWriterProcess: Process? = null
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker: Marker = MapEntriesAppendingMarker(
        mapOf(
            LogMarkers.UDID to udid,
            LogMarkers.HOSTNAME to remote.hostName
        )
    )

    private var timestamp: String? = null

    fun truncate(): Boolean {
        val date = remote.execIgnoringErrors(listOf("date", "+%s"))

        if (date.isSuccess) {
            timestamp = date.stdOut.lines().first()
        }

        return date.isSuccess
    }

    fun content(process: String?): String {
        val cmd = mutableListOf("xcrun", "simctl", "spawn", udid, "log", "show", "--style", "syslog")

        if (timestamp != null) {
            cmd.addAll(listOf("--start", "@$timestamp"))
        }

        if (process != null) {
            cmd.addAll(listOf("--predicate", remote.escape("process==\"$process\"")))
        }

        val result = remote.execIgnoringErrors(cmd)

        if (!result.isSuccess) {
            val message = "Could not read OS Log. Result stdErr: ${result.stdErr}"
            logger.error(logMarker, message)
            throw RuntimeException(message)
        }

        return result.stdOut
    }

    fun deleteLogFiles() {
        osLogFile.delete()
        osLogStderr.delete()
    }

    fun stopWritingLog() {
        osLogWriterProcess?.destroy()
        outWritingTask?.cancel(true)
        errWritingTask?.cancel(true)
    }

    fun startWritingLog(predicateString: String) {
        stopWritingLog()
        deleteLogFiles()

        val simulatorBootTimeOutMinutes = 20
        val cmd = mutableListOf(
            "/usr/bin/xcrun", "simctl", "spawn", udid, "log", "stream",
            "--timeout", "${simulatorBootTimeOutMinutes}m",
            "--color", "none",
            "--level", "debug")

        if (predicateString.isNotBlank()) {
            val predicate = if (remote.isLocalhost()) {
                predicateString
            } else {
                "\"$predicateString\""
            }

            cmd.add("--predicate")
            cmd.add(predicate)
        }

        val process: Process = remote.remoteExecutor.startProcess(cmd, mapOf(), logMarker)

        val executor = Executors.newFixedThreadPool(2)
        outWritingTask = executor.submit(write(process.inputStream, osLogFile.toPath()))
        errWritingTask = executor.submit(write(process.errorStream, osLogStderr.toPath()))
        executor.shutdown()

        osLogWriterProcess = process
    }

    private fun write(inputStream: InputStream, path: Path): Runnable {
        logger.debug("Writing log file to ${path.toFile().absolutePath}")
        return Runnable {
            inputStream.use { stream ->
                Files.copy(stream, path, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
