package com.badoo.automation.deviceserver.ios.simulator.video

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.command.ChildProcess
import com.badoo.automation.deviceserver.data.DeviceInfo
import com.badoo.automation.deviceserver.data.DeviceRef
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.proc.LongRunningProc
import com.badoo.automation.deviceserver.util.ensure
import com.badoo.automation.deviceserver.util.pollFor
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.Executors

class MJPEGVideoRecorder(
    private val deviceInfo: DeviceInfo,
    remote: IRemote,
    mjpegServerPort: Int,
    private val ref: DeviceRef,
    udid: UDID,
    private val config: ApplicationConfiguration = ApplicationConfiguration(),
    private val videoFile: File = File(config.tempFolder, "videoRecording_${deviceInfo.udid}.mp4")
) : VideoRecorder {
    // val tempFolder = File(System.getenv("TMPDIR") ?: System.getenv("TMP") ?: System.getenv("TEMP") ?: System.getenv("PWD"))
    // mktemp -t XXXXXXXXXX_video_blah_blah.mp4

    private val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(
        mapOf(
            LogMarkers.HOSTNAME to remote.publicHostName,
            LogMarkers.UDID to udid,
            LogMarkers.DEVICE_REF to ref
        )
    )
    private val videoRecorder = FfmpegVideoRecorder(
        udid,
        remote,
        mjpegServerPort,
        videoFile,
        config
    )

    override fun toString(): String = "${javaClass.simpleName} for $ref"

    override fun delete() {
        val wasDeleted = Files.deleteIfExists(videoFile.toPath())
        val logMessage =
            if (wasDeleted) "Video recording was deleted: ${videoFile.absolutePath}" else "Video recording did not exist: ${videoFile.absolutePath}"
        logger.debug(logMarker, logMessage)
    }

    override fun start() {
        logger.debug(logMarker, "Starting video recording - ${videoFile.absolutePath}")

        dispose() // old if exists

        videoRecorder.start()

        logger.info(logMarker, "Started video recording - ${videoFile.absolutePath}")
    }

    override fun stop() {
        logger.debug(logMarker, "Stopping video recording")
        videoRecorder.stop()
        logger.info(logMarker, "Stopped video recording - ${videoFile.absolutePath}")
    }

    override fun getRecording(): ByteArray {
        logger.info(logMarker, "Getting video recording - ${videoFile.absolutePath}")
        return videoFile.readBytes()
    }

    override fun dispose() {
        logger.debug(logMarker, "Disposing video recording - ${videoFile.absolutePath}")
        videoRecorder.stop()
        delete()
        logger.debug(logMarker, "Disposed video recording - ${videoFile.absolutePath}")
    }

    class FfmpegVideoRecorder(
        private val udid: String,
        private val remote: IRemote,
        mjpegServerPort: Int,
        private val encodedVideoFile: File,
        private val config: ApplicationConfiguration,
        private val childFactory: (
            remoteHost: String,
            userName: String,
            cmd: List<String>,
            commandEnvironment: Map<String, String>,
            out_reader: ((line: String) -> Unit)?,
            err_reader: ((line: String) -> Unit)?
        ) -> ChildProcess = ChildProcess.Companion::fromCommand
    ) : LongRunningProc(udid, remote.hostName) {
        private val mjpegStreamUrl = URL("http://${remote.publicHostName}:${mjpegServerPort}")
        private val uniqueTag = "video_recording_$udid"
        private fun ffmpegCommand(): List<String> {
            val remoteVideoFile: File = if (remote.isLocalhost()) {
                encodedVideoFile
            } else {
                encodedVideoFile.name
                val temporaryFileTemplate = "${encodedVideoFile.name}_XXXXXXXXXX"
                val result = remote.execIgnoringErrors(listOf("/usr/bin/mktemp", "-t", temporaryFileTemplate))
                if (result.isSuccess) {
                    File(result.stdOut.trim())
                } else {
                    throw RuntimeException("Failed to create remote file for video recording. $result")
                }
            }

            return listOf(
                FFMPEG_PATH,
                "-hide_banner",
                "-loglevel", "warning",
                "-f", "mjpeg",
                "-framerate", "4",
                "-i", mjpegStreamUrl.toExternalForm(),
                "-t", "${maxVideoDuration.toSeconds()}",
                "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2",
                "-an",
                "-vcodec", "h264",
                "-preset", "ultrafast",
                "-tune", "animation",
                "-pix_fmt", "yuv420p",
                "-metadata", "comment=$uniqueTag",
                "-y",
                remoteVideoFile.absolutePath
            )
        }

        override fun start() {
            ensure(childProcess == null && !checkHealth()) { RuntimeException("Previous ffmpeg childProcess $childProcess is still running") }
            logger.debug(logMarker, "$this — Starting ffmpeg child process")

             val ffmpegProcess = childFactory(
                remote.hostName,
                remote.userName,
                ffmpegCommand(),
                mapOf(),
                { logger.debug(logMarker, it.trim()) },
                { logger.warn(logMarker, it.trim()) }
            )
            ffmpegProcess.onExit.thenApply {
                when (it.exitValue()) {
                    0 -> logger.info(logMarker, "ffmpeg process exited normally for $udid")
                    255 -> logger.info(logMarker, "ffmpeg process terminated for $udid")
                    else -> logger.error(logMarker, "ffmpeg process exited abnormally for $udid")
                }
            }

            Thread.sleep(1000)

            check(ffmpegProcess.isAlive()) { "ffmpeg is not running" }

            childProcess = ffmpegProcess

            logger.debug(logMarker, "$this Started ffmpeg child process: $childProcess")
        }

        fun stop() {
            super.kill()

            // just in case there are some left-over process
            val result = remote.localExecutor.exec(
                command = listOf("pkill", "-f", uniqueTag),
                environment = mapOf(),
                returnFailure = true,
                logMarker = logMarker
            )

            if (result.isSuccess) {
                Thread.sleep(2000)
            }
            // else — no process found
        }

        override fun checkHealth(): Boolean {
            val result = remote.localExecutor.exec(
                command = listOf("pgrep", "-f", uniqueTag),
                environment = mapOf(),
                returnFailure = true,
                logMarker = logMarker
            )

            return result.isSuccess
        }

        private companion object {
            val FFMPEG_PATH: String = listOf(
                "/usr/bin/ffmpeg",
                "/usr/local/bin/ffmpeg",
                "/opt/homebrew/bin/ffmpeg"
            ).find { File(it).exists() }
                ?: throw RuntimeException("Unable to find ffmpeg binary")
            val maxVideoDuration = Duration.ofMinutes(30)
        }
    }
}
