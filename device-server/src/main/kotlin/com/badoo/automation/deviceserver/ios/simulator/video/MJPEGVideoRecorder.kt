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
import okhttp3.Response
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MJPEGVideoRecorder(
    private val deviceInfo: DeviceInfo,
    private val remote: IRemote,
    wdaEndpoint: URI,
    mjpegServerPort: Int,
    frameRate: Int,
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
    private val uniqueTag = "video-recording-$ref"
    private val wdaClient = WdaClient(wdaEndpoint.toURL())
    private val httpClient = CustomHttpClient().client.newBuilder().readTimeout(maxVideoDuration).build()
    private val mjpegStreamUrl = URL("http://${remote.publicHostName}:${mjpegServerPort}")
    private val mjpegSettings: Map<Any, Any> = mapOf(
        "settings" to mapOf(
            "mjpegServerFramerate" to frameRate,
            "mjpegScalingFactor" to 50,
            "mjpegServerScreenshotQuality" to 100
        )
    )
    private val ffmpegCommand: List<String> = listOf(
        "$FFMPEG_PATH",
        "-hide_banner",
        "-loglevel", "warning",
        "-f", "mjpeg",
        "-framerate", "$frameRate",
        "-i", videoFile.absolutePath,
        "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", // might use ffprobe to get resolution as well
        "-an",
        "-vcodec", "h264",
        "-preset", "ultrafast",
        "-tune", "fastdecode",
        "-pix_fmt", "yuv420p",
        "-metadata", "comment=$uniqueTag",
        "-y",
        encodedVideoFile.absolutePath
    )
    private var videoRecordingTask: Future<*>? = null
    private var videoRecordingHttpCall: Call? = null

    override fun toString(): String = "${javaClass.simpleName} for $ref"

    override fun start() {
        cleanupOldRecordings()
        adjustVideoStreamSettings()
        logger.debug(logMarker, "Starting video recording")

        val request: Request = Request.Builder()
            .get()
            .url(mjpegStreamUrl)
            .build()

        val httpCall = httpClient.newCall(request)
        val executor = Executors.newSingleThreadExecutor()
        val recordingTask = executor.submit(recordStream(httpCall.execute()))
        executor.shutdown()

        videoRecordingTask = recordingTask
        videoRecordingHttpCall = httpCall

        logger.debug(logMarker, "Started video recording")
    }

    override fun stop() {
        logger.debug(logMarker, "Stopping video recording")
        stopVideoRecording()
        logger.debug(logMarker, "Stopped video recording stopped")
    }

    override fun getRecording(): ByteArray {
        logger.debug(logMarker, "Getting video recording")

        return try {
            if (FFMPEG_PATH == null) {
                logger.error("Failed to find ffmpeg utility. Returning uncompressed video.")
                videoFile.readBytes()
            } else {
                compressedVideo()
            }
        } finally {
            delete()
        }
    }

    override fun delete() {
        logger.debug(logMarker, "Deleting video recording")
        Files.deleteIfExists(videoFile.toPath())
        Files.deleteIfExists(encodedVideoFile.toPath())
    }

    override fun dispose() {
        logger.debug(logMarker, "Disposing video recording")
        cleanupOldRecordings()
        logger.debug(logMarker, "Disposed video recording")
    }

    private fun adjustVideoStreamSettings() {
        wdaClient.attachToSession()
        val response = wdaClient.updateAppiumSettings(mjpegSettings)
        logger.trace(logMarker, "Updated MJPEG streaming server settings: $response")
    }

    private fun cleanupOldRecordings() {
        stopVideoRecording()
        delete()
    }

    private fun recordStream(response: Response): Runnable = Runnable {
        response.use {
            it.body?.let { responseBody ->
                responseBody.byteStream().use { inputStream ->
                    Files.copy(inputStream, videoFile.toPath(), REPLACE_EXISTING)
                }
            }
        }
    }

    private fun compressedVideo(): ByteArray {
        val result = remote.localExecutor.exec(ffmpegCommand, timeOut = Duration.ofSeconds(60L))

        if (!result.isSuccess && (!encodedVideoFile.exists() || Files.size(encodedVideoFile.toPath()) == 0L)) {
            val message = "Could not compress video file. Result stdErr: ${result.stdErr}"
            logger.error(message)
            throw SimulatorVideoRecordingException(message)
        }

        logger.debug(logMarker, "Successfully compressed video recording.")
        return encodedVideoFile.readBytes()
    }

    private fun stopVideoRecording() {
        videoRecordingHttpCall?.cancel()
        videoRecordingHttpCall = null

        videoRecordingTask?.cancel(true)
        videoRecordingTask = null
    }

    private companion object {
        private val ffmpegBinaries = listOf("/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg")
        val FFMPEG_PATH = ffmpegBinaries.find { File(it).canExecute() }
    }
}
