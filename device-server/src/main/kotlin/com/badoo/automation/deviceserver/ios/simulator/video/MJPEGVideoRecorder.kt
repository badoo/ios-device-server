package com.badoo.automation.deviceserver.ios.simulator.video

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.data.DeviceInfo
import com.badoo.automation.deviceserver.data.DeviceRef
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.WdaClient
import com.badoo.automation.deviceserver.util.CustomHttpClient
import net.logstash.logback.marker.MapEntriesAppendingMarker
import okhttp3.Call
import okhttp3.Request
import okio.buffer
import okio.sink
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.Rectangle
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.Future

class
MJPEGVideoRecorder(
    private val deviceInfo: DeviceInfo,
    private val remote: IRemote,
    wdaEndpoint: URI,
    mjpegServerPort: Int,
    private val ref: DeviceRef,
    udid: UDID,
    maxVideoDuration: Duration = Duration.ofMinutes(15),
    private val videoFile: File = File.createTempFile("videoRecording_${deviceInfo.udid}_", ".mjpeg"),
    private val encodedVideoFile: File = File.createTempFile("videoRecording_${deviceInfo.udid}_", ".mp4")
) : VideoRecorder {
    private val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(
        mapOf(
            LogMarkers.HOSTNAME to remote.publicHostName,
            LogMarkers.UDID to udid,
            LogMarkers.DEVICE_REF to ref
        )
    )

    init {
        delete()
    }

    private val httpClient = CustomHttpClient.client.newBuilder().readTimeout(maxVideoDuration).build()
    private var videoRecordingTask: Future<*>? = null
    private var videoRecordingHttpCall: Call? = null

    override fun toString(): String = "${javaClass.simpleName} for $ref"

    private val uniqueTag = "video-recording-$udid"
    private val wdaClient = WdaClient(wdaEndpoint.toURL())
    private val mjpegStreamUrl = URL("http://${remote.publicHostName}:${mjpegServerPort}")

    override fun delete() {
        logger.debug(logMarker, "Deleting video recording")
        deleteVideoFile(videoFile)
        deleteVideoFile(encodedVideoFile)
    }

    private fun deleteVideoFile(file: File) {
        try {
            Files.deleteIfExists(file.toPath())

            if (file.exists()) {
                logger.error(logMarker, "Video file still exists: ${file.absolutePath}")
            }
        } catch (e: RuntimeException) {
            logger.error(logMarker, "Error while deleting Video file ${file.absolutePath}", e)
        }
    }

    override fun start() {
        logger.info(logMarker, "Starting video recording")

        stopVideoRecording()
        delete()
        adjustVideoStreamSettings()

        val executor = Executors.newSingleThreadExecutor()
        val request: Request = Request.Builder()
            .get()
            .url(mjpegStreamUrl)
            .build()

        videoRecordingHttpCall = httpClient.newCall(request)

        videoRecordingTask = executor.submit {
            videoRecordingHttpCall!!.execute().use { response ->
                response.body!!.byteStream().use { inputStream ->
                    Files.copy(inputStream, videoFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        executor.shutdown()

        logger.info(logMarker, "Started video recording")
    }

    private fun adjustVideoStreamSettings() {
        wdaClient.attachToSession()
        val response = wdaClient.updateAppiumSettings(
            mapOf(
                "settings" to mapOf(
                    "mjpegServerFramerate" to 4,
                    "mjpegScalingFactor" to 50,
                    "mjpegServerScreenshotQuality" to 100
                )
            )
        )
        logger.debug(logMarker, "Updated MJPEG server settings: $response")
    }

    override fun stop() {
        logger.info(logMarker, "Stopping video recording")

        stopVideoRecording()

        logger.info(logMarker, "Stopped video recording stopped")
    }

    private fun stopVideoRecording() {
        try {
            videoRecordingHttpCall?.cancel()
        } catch (e: RuntimeException) {
            logger.warn(logMarker, "Failed to cancel video recording call. ${e.message}", e)
        } finally {
            videoRecordingHttpCall = null
        }

        try {
            videoRecordingTask?.cancel(true)
        } catch (e: RuntimeException) {
            logger.warn(logMarker, "Failed to cancel video recording task. ${e.message}", e)
        } finally {
            videoRecordingTask = null
        }
    }

    override fun getRecording(): ByteArray {
        try {
            logger.info(logMarker, "Getting video recording")

            if (FFMPEG_PATH == null) {
                logger.error("Failed to find ffmpeg. Returning mjpeg.")
                return videoFile.readBytes()
            }

            val cmd = listOf(
                 FFMPEG_PATH,
                    "-hide_banner",
                    "-loglevel", "warning",
                    "-f", "mjpeg",
                    "-framerate", "4",
                    "-i", videoFile.absolutePath,
                    "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2",
                    "-an",
                    "-vcodec", "h264",
                    "-preset", "ultrafast",
                    "-tune", "fastdecode",
                    "-pix_fmt", "yuv420p",
                    "-metadata", "comment=$uniqueTag",
                    "-y",
                    encodedVideoFile.absolutePath
            )

            val result = remote.localExecutor.exec(cmd, timeOut = Duration.ofSeconds(60L))

            if (!result.isSuccess && (!encodedVideoFile.exists() || Files.size(encodedVideoFile.toPath()) == 0L)) {
                val message = "Could not read video file. Result stdErr: ${result.stdErr}"
                logger.error(message)
                throw SimulatorVideoRecordingException(message)
            }

            logger.info(logMarker, "Successfully encoded video recording.")
            return encodedVideoFile.readBytes()
        } finally {
            delete()
        }
    }

    override fun dispose() {
        logger.info(logMarker, "Terminating video recording process")
        stop()
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