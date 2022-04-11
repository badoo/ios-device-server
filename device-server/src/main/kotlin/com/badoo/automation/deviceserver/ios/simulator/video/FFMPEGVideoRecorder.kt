package com.badoo.automation.deviceserver.ios.simulator.video

import com.badoo.automation.deviceserver.ApplicationConfiguration
import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.data.DeviceRef
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.util.pollFor
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URL
import java.time.Duration

class FFMPEGVideoRecorder(
    private val remote: IRemote,
    mjpegServerPort: Int,
    private val ref: DeviceRef,
    private val udid: UDID,
    private val config: ApplicationConfiguration = ApplicationConfiguration()
) : VideoRecorder {
    private val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(
        mapOf(
            LogMarkers.HOSTNAME to remote.publicHostName,
            LogMarkers.UDID to udid,
            LogMarkers.DEVICE_REF to ref
        )
    )
    private val videoFile = File(config.tempFolder, "videoRecording_${udid}.mp4")
    private val videoLogFile = File(config.tempFolder, "videoRecording_${udid}.mp4.log")
    private val mjpegStreamUrl = URL("http://${remote.publicHostName}:${mjpegServerPort}")

    override fun toString(): String = "${javaClass.simpleName} for $ref"

    override fun delete() {
        if (remote.isLocalhost()) {
            videoFile.delete()
            videoLogFile.delete()
        } else {
            remote.shell("rm -vf ${'$'}{TMPDIR}${videoFile.name}")
            remote.shell("rm -vf ${'$'}{TMPDIR}${videoLogFile.name}")
        }
    }

    override fun start() {
        logger.debug(logMarker, "Starting video recording - ${videoFile.name}")

        val result = remote.execIgnoringErrors(listOf(
                config.remoteVideoRecorder.absolutePath,
                udid,
                mjpegStreamUrl.toExternalForm()
        ))

        if (result.isSuccess) {
            logger.info(logMarker, "Started video recording ${videoFile.name}")
        } else {
            val errorMessage = "Failed to start video recording ${videoFile.name}. Exit code: ${result.exitCode} StdOut: ${result.stdOut} StdErr: ${result.stdErr}. Log contents: ${getLogContents()}"
            logger.error(errorMessage)
            throw VideoRecordingException(errorMessage)
        }
    }

    override fun stop() {
        logger.debug(logMarker, "Stopping video recording ${videoFile.name}")
        val result = remote.shell("pkill -f ${videoFile.name}")

        when {
            result.isSuccess -> {
                logger.info(logMarker, "Stopped video recording ${videoFile.name}")
                pollFor(
                    Duration.ofSeconds(60),
                    reasonName = "Stop video recording",
                    shouldReturnOnTimeout = true,
                    retryInterval = Duration.ofMillis(500),
                    logger = logger,
                    marker = logMarker
                ) {
                    remote.shell("pgrep -f ${videoFile.name}").exitCode == 1 // pgrep has exit code 1 when process not found
                }
            }
            result.exitCode == 1 -> {
                logger.info("No video recording process found for ${videoFile.name}")
            }
            else -> {
                logger.error("Failed to stop video recording process for ${videoFile.name}. Exit code: ${result.exitCode} StdOut: ${result.stdOut} StdErr: ${result.stdErr}")
            }
        }
    }

    private fun getLogContents(): String {
        return if (remote.isLocalhost()) {
            if (videoLogFile.exists()) videoLogFile.readText() else "Failed to find ${videoLogFile.name}"
        } else {
            val logFileResult = remote.shell("find ${'$'}{TMPDIR}${videoLogFile.name}")

            if (logFileResult.isSuccess) {
                remote.execIgnoringErrors(listOf("cat", logFileResult.stdOut.trim())).stdOut
            } else {
                "Failed to find ${videoLogFile.name}"
            }
        }
    }

    override fun getRecording(): ByteArray {
        logger.info(logMarker, "Getting video recording ${videoFile.name}")

        if (!remote.isLocalhost()) {
            val videoFileResult = remote.shell("find ${'$'}{TMPDIR}${videoFile.name}")

            if (videoFileResult.isSuccess) {
                val videoFilePath = videoFileResult.stdOut.trim()
                remote.scpFromRemoteHost(videoFilePath, videoFile.absolutePath, Duration.ofSeconds(60))
            } else {
                val errorMessage = "Failed to find video recording ${videoFile.name} on remote host. ${videoFileResult.stdErr}. Log contents: ${getLogContents()}"
                logger.error(errorMessage)

                val videoLogFileResult = remote.shell("find ${'$'}{TMPDIR}${videoLogFile.name}")

                if (videoLogFileResult.isSuccess) {
                    val videoLogFilePath = videoLogFileResult.stdOut.trim()
                    remote.scpFromRemoteHost(videoLogFilePath, videoFile.absolutePath, Duration.ofSeconds(60))
                }
            }
        }

        return if (videoFile.exists()) {
            videoFile.readBytes()
        } else {
            val errorMessage = "Failed to find video recording ${videoFile.absolutePath}. Log contents: ${getLogContents()}"
            logger.error(errorMessage)
            throw VideoRecordingException(errorMessage)
        }
    }

    override fun dispose() {
        stop()
        delete()
    }
}
