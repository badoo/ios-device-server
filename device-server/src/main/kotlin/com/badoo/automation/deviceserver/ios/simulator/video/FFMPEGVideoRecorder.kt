package com.badoo.automation.deviceserver.ios.simulator.video

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.ShellCommand.Companion.destroyProcess
import com.badoo.automation.deviceserver.data.DeviceRef
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.util.pollFor
import com.zaxxer.nuprocess.internal.LibC
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.URL
import java.time.Duration
import java.util.concurrent.TimeUnit

class FFMPEGVideoRecorder(
    private val remote: IRemote, mjpegServerPort: Int, private val ref: DeviceRef, private val udid: UDID, private val config: ApplicationConfiguration = ApplicationConfiguration()
) : VideoRecorder {
    private val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(
        mapOf(
            LogMarkers.HOSTNAME to remote.publicHostName, LogMarkers.UDID to udid, LogMarkers.DEVICE_REF to ref
        )
    )
    private val videoFileName = "videoRecording_${udid}.mp4"

    private val videoFile = File(config.tempFolder, videoFileName)
    private val videoLogFile = File(config.tempFolder, "${videoFileName}.log")

    private val mjpegStreamUrl = URI("http://${remote.publicHostName}:${mjpegServerPort}").toURL()

    override fun toString(): String = "${javaClass.simpleName} for $ref"

    override fun delete() {
        listOf(videoFile, videoLogFile).forEach {
            if (it.exists()) {
                it.delete()
            }
        }
    }

    @Volatile
    private var recorderProcess: Process? = null

    override fun start() {
        checkNoActiveRecording()
        logger.debug(logMarker, "Starting video recording - ${videoFile.name}")

        val command = ffmpegCommand()
        val process = startFfmpegProcess(command)

        if (waitForRecordingToStart(process)) {
            recorderProcess = process
            logger.info(logMarker, "Started video recording ${videoFile.name}. PID: ${process.pid()}")
        } else {
            handleFailedStart(process)
        }
    }

    private fun checkNoActiveRecording() {
        recorderProcess?.let {
            val message = "Video recording process is still running ${videoFile.name}. Please stop it before starting a new one. PID: ${it.pid()}"
            logger.error(logMarker, message)
            throw VideoRecordingException(message)
        }
    }

    private fun startFfmpegProcess(command: List<String>): Process {
        val processBuilder = ProcessBuilder(command).apply {
            redirectOutput(videoLogFile)
            redirectError(videoLogFile)
        }

        return remote.commandExecutor.startProcess(
            command = command, environment = System.getenv(), logMarker = logMarker, processBuilder = processBuilder
        )
    }

    private fun waitForRecordingToStart(process: Process): Boolean {
        var started = false

        pollFor(
            Duration.ofSeconds(10), "Starting video recording ${videoFile.name}", true, Duration.ofMillis(500), logger, logMarker
        ) {
            started = process.isAlive == true && videoLogFile.exists() && videoLogFile.length() > 0 && videoLogFile.readLines().any { it.startsWith("frame=") }
            started
        }

        return started
    }

    private fun handleFailedStart(process: Process) {
        val errorMessage = if (process.isAlive) {
            "Video recording process is still running but did not start correctly. PID: ${process.pid()}. Log contents: ${getRecordingLog()}"
        } else {
            "Failed to start video recording ${videoFile.name}. Exit code: ${process.exitValue()} Video log: ${getRecordingLog()}"
        }
        logger.error(logMarker, errorMessage)
        throw VideoRecordingException(errorMessage)
    }

    private fun ffmpegCommand(): List<String> = listOf(
        FFMPEG_PATH,
        "-hide_banner",
        "-loglevel", "info",
        "-f", "mjpeg",
        "-framerate", "5",
        "-i", mjpegStreamUrl.toExternalForm(),
        "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2",
        "-vf", "scale=400:-2",
        "-an",
        "-threads", "1",
        "-t", "00:15:00",
        "-vcodec", "h264",
        "-preset", "ultrafast",
        "-tune", "animation",
        "-pix_fmt", "yuv420p",
        "-metadata", "comment=\"${videoFile.absolutePath}\"",
        "-y",
    )

    private fun gracefullyStopFfmpeg(pid: Int): Boolean {
        return LibC.kill(pid, SIGINT) == 0
    }

    override fun stop() {
        val process = recorderProcess ?: return

        try {
            if (!process.isAlive) {
                logger.warn(logMarker, "Video recording process is not running for ${videoFile.name}")
                return
            }

            logger.debug(logMarker, "Stopping video recording ${videoFile.name}. PID: ${process.pid()}")

            val processCommand = ffmpegCommand().joinToString(" ")
            val pid = process.pid()

            val stoppedGracefully = gracefullyStopFfmpeg(pid.toInt()) && process.waitFor(20, TimeUnit.SECONDS)

            if (stoppedGracefully) {
                logger.info(logMarker, "Video recording ${videoFile.name} stopped successfully. Exit code: ${process.exitValue()}")
            } else {
                val reason = if (!process.isAlive) "process died unexpectedly" else "timed out waiting for exit"
                logger.warn(logMarker, "Graceful stop failed ($reason). Forcing termination for PID: $pid")
                destroyProcess(process, logMarker, processCommand, pid, logger)
            }
        } catch (e: Exception) {
            logger.error(logMarker, "Failed to gracefully stop video recording ${videoFile.name}: ${e.message}", e)
            if (process.isAlive) {
                destroyProcess(process, logMarker, ffmpegCommand().joinToString(" "), process.pid(), logger)
            }
            throw VideoRecordingException("Failed to stop video recording ${videoFile.name}: ${e.message}", e)
        } finally {
            recorderProcess = null
        }
    }

    override fun getRecordingLog(): String {
        return if (videoLogFile.exists()) {
            videoLogFile.readText()
        } else {
            "File $videoLogFile not found"
        }
    }

    override fun getRecording(): ByteArray {
        logger.info(logMarker, "Getting video recording ${videoFile.name}")

        return if (videoFile.exists()) {
            videoFile.readBytes()
        } else {
            val errorMessage = "Failed to find video recording ${videoFile.absolutePath}. Log contents: ${getRecordingLog()}"
            logger.error(errorMessage)
            throw VideoRecordingException(errorMessage)
        }
    }

    override fun dispose() {
        stop()
        delete()
    }

    companion object {
        private fun ffmpegBinaryPath(): String {
            return listOf(
                "/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg"
            ).find { File(it).exists() } ?: throw RuntimeException("FFMPEG binary not found. Please install FFMPEG")
        }

        private val FFMPEG_PATH = ffmpegBinaryPath()
        private const val SIGINT = 2 // Signal number for SIGINT
    }
}
