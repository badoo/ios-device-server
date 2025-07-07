package com.badoo.automation.deviceserver.ios.simulator.diagnostic

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.ShellCommand
import com.badoo.automation.deviceserver.data.SysLogCaptureOptions
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.ISysLog
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Future

class OsLog(
    private val remote: IRemote,
    private val udid: UDID,
    override val osLogFile: File = File.createTempFile("iOS_SysLog_${udid}_", ".log"),
    override val osLogStderr: File = File.createTempFile("iOS_SysLog_${udid}_", ".err.log")
) : ISysLog {
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

    override fun truncate(): Boolean {
        val date = remote.execIgnoringErrors(listOf("date", "+%s"))

        if (date.isSuccess) {
            timestamp = date.stdOut.lines().first()
        }

        return date.isSuccess
    }

    override fun content(process: String?): String {
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

    override fun deleteLogFiles() {
        osLogFile.delete()
        osLogStderr.delete()
    }

    override fun stopWritingLog() {
        osLogWriterProcess?.let {
            ShellCommand.destroyProcess(
                it,
                logMarker,
                "xcrun simctl spawn $udid log stream",
                it.pid(),
                logger
            )
        }
        outWritingTask?.cancel(true)
        errWritingTask?.cancel(true)
    }

    override fun startWritingLog(sysLogCaptureOptions: SysLogCaptureOptions) {
        stopWritingLog()
        deleteLogFiles()

        val simulatorBootTimeOutMinutes = 20
        val cmd = mutableListOf(
            "/usr/bin/xcrun", "simctl", "spawn", udid, "log", "stream",
            "--timeout", "${simulatorBootTimeOutMinutes}m",
            "--color", "none",
            "--level", "debug")

        if (sysLogCaptureOptions.predicateString.isNotBlank()) {
            val predicate = sysLogCaptureOptions.predicateString

            cmd.add("--predicate")
            cmd.add(predicate)
        }

        val process: Process = remote.commandExecutor.startProcess(cmd, mapOf(), logMarker)

        outWritingTask = ShellCommand.outErrReaderExecutor.submit(write(process.inputStream, osLogFile.toPath()))
        errWritingTask = ShellCommand.outErrReaderExecutor.submit(write(process.errorStream, osLogStderr.toPath()))

        osLogWriterProcess = process
    }

    private fun write(inputStream: InputStream, path: Path): Runnable {
        logger.debug("Writing log file to ${path.toFile().absolutePath}")
        return Runnable {
            try {
                inputStream.use { stream ->
                    Files.copy(stream, path, StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: IOException) {
                logger.error(logMarker, "Got IOException while reading from stream. Error: ${e.javaClass} ${e.message}", e)
            } catch (e: InterruptedException) {
                logger.error(logMarker, "Got InterruptedException while reading from stream. Error: ${e.javaClass} ${e.message}", e)
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                logger.error(logMarker, "Error while writing log file. Error: ${e.javaClass} ${e.message}", e)
            }
        }
    }
}
