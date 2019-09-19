package com.badoo.automation.deviceserver.ios.simulator.video

import com.badoo.automation.deviceserver.command.ChildProcess
import com.badoo.automation.deviceserver.command.IShellCommand
import com.badoo.automation.deviceserver.data.DeviceInfo
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.WdaClient
import com.badoo.automation.deviceserver.ios.proc.LongRunningProc
import com.badoo.automation.deviceserver.util.pollFor
import java.awt.Rectangle
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MJPEGVideoRecorder(
    private val deviceInfo: DeviceInfo,
    private val remote: IRemote,
    private val wdaEndpoint: URI,
    private val mjpegServerPort: Int,
    private val childFactory: (remoteHost: String, executor: IShellCommand, cmd: List<String>, commandEnvironment: Map<String, String>,
                               out_reader: (line: String) -> Unit, err_reader: (line: String) -> Unit
        ) -> ChildProcess? = ChildProcess.Companion::fromLocalCommand,
    private val recorderStopTimeout: Duration = Duration.ofSeconds(5),
    private val videoFile: File = File.createTempFile("videoRecording_${deviceInfo.udid}_", ".mjpeg"),
    private val encodedVideoFile: File = File.createTempFile("videoRecording_${deviceInfo.udid}_", ".mp4")
) : LongRunningProc(deviceInfo.udid, remote.hostName), VideoRecorder {

    private val udid = deviceInfo.udid

    @Volatile
    private var isStarted: Boolean = false

    private val lock = ReentrantLock(true)

    override fun toString(): String = udid

    override fun checkHealth(): Boolean = childProcess?.isAlive() ?: false

    private val uniqueTag = "video-recording-$udid"
    private val wdaClient = WdaClient(wdaEndpoint.toURL())

    override fun delete() {
        logger.debug(logMarker, "Deleting video recording")
        deleteVideoFile(videoFile)
        deleteVideoFile(encodedVideoFile)
    }

    private fun deleteVideoFile(file: File) {
        Files.deleteIfExists(file.toPath())

        if (file.exists()) {
            logger.error(logMarker, "Video file still exists: ${file.absolutePath}")
        }
    }

    override fun start() {
        logger.info(logMarker, "Starting video recording")
        lock.withLock {
            if (isStarted) {
                val message = "Video recording already started"
                logger.error(logMarker, message)
                throw SimulatorVideoRecordingException(message)
            }

            delete()

            wdaClient.attachToSession()

            val response = wdaClient.updateAppiumSettings(mapOf("settings" to mapOf("mjpegServerFramerate" to 4, "mjpegServerScreenshotQuality" to 40)))
            logger.debug(logMarker, "Updated MJPEG server settings: $response")


            val cmd = listOf(
                "/usr/bin/curl",
                "-o",
                videoFile.toString(),
                "http://${remote.publicHostName}:${mjpegServerPort}"
            )

            childProcess = childFactory(remote.hostName, remote.localExecutor, cmd, mapOf(),
                { logger.debug(logMarker, "$udid: VideoRecorder <o>: ${it.trim()}") },
                { logger.debug(logMarker, "$udid: VideoRecorder <e>: ${it.trim()}") }
            )

            logger.info(logMarker, "Started video recording")
            isStarted = true
        }
    }

    override fun stop() {
        lock.withLock {
            if (!isStarted) {
                val message = "Video recording has not yet started"
                logger.warn(logMarker, message)
                throw SimulatorVideoRecordingException(message)
            }

            try {
                logger.info(logMarker, "Stopping video recording")
                childProcess?.kill(Duration.ofSeconds(5))

                pollFor(recorderStopTimeout, "Stop video recording", false, Duration.ofMillis(500), logger, logMarker) {
                    logger.warn("${childProcess?.isAlive()}")

                    childProcess?.isAlive() == false
                }
            } finally {
                childProcess?.kill()
                childProcess = null
                logger.info(logMarker, "Stopped video recording")
                isStarted = false
            }
        }
    }

    override fun getRecording(): ByteArray {
        logger.info(logMarker, "Getting video recording")

        if (FFMPEG_PATH == null) {
            logger.error("Failed to find ffmpeg. Returning mjpeg.")
            return videoFile.readBytes()
        }

        var scale = ""
        val resolution = getVideoResolution(videoFile.absoluteFile)
        if (resolution.width > 0 && resolution.height > 0) {
            scale = "-vf scale=\"${resolution.width}x${resolution.height}\" "
        }

        val cmd = "$FFMPEG_PATH " +
                "-hide_banner " +
                "-loglevel warning " +
                "-f mjpeg " +
                "-framerate 4 " +
                "-i ${videoFile.absolutePath} " +
                "-an " +
                "-vcodec h264 " +
                scale +
                "-preset medium " +
                "-tune animation " +
                "-pix_fmt yuv420p " +
                "-metadata comment=$uniqueTag " +
                "-y " +
                "${encodedVideoFile.absolutePath}"

        val result = remote.localExecutor.exec(cmd.split(" "), timeOut = Duration.ofSeconds(60L))

        if (!result.isSuccess && (!encodedVideoFile.exists() || Files.size(encodedVideoFile.toPath()) == 0L)) {
            val message = "Could not read video file. Result stdErr: ${result.stdErr}"
            logger.error(message)
            throw SimulatorVideoRecordingException(message)
        }

        logger.info(logMarker, "Successfully encoded video recording.")
        return encodedVideoFile.readBytes()
    }

    override fun dispose() {
        if (childProcess?.isAlive() != true) {
            return
        }

        logger.info(logMarker, "Terminating video recording process")
        childProcess?.kill(Duration.ofSeconds(1))
        delete()
        logger.info(logMarker, "Disposed video recording")
    }
    private fun getVideoResolution(file: File): Rectangle {
        val cmd = "$FFPROBE_PATH -v error -show_entries stream=width,height -of csv=p=0:s=x ${file.absolutePath}"
        val result = remote.localExecutor.exec(cmd.split(" "), timeOut = Duration.ofSeconds(60L))

        if (!result.isSuccess) {
            return Rectangle(0, 0, 0, 0)
        }

        val res = result.stdOut.split("x")

        return Rectangle(0, 0, 0, 0)
    }

    private companion object {
        val FFMPEG_PATH = listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg").find { File(it).canExecute() }
        val FFPROBE_PATH = listOf("/usr/bin/ffprobe", "/usr/local/bin/ffprobe").find { File(it).canExecute() }
    }
}