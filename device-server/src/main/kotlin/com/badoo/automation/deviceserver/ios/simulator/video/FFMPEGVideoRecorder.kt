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
import java.io.FileNotFoundException
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
    private val videoFileName = "videoRecording_${udid}.mp4"

    private val videoFile = File(config.tempFolder, videoFileName)
    private val videoLogFile = File(config.tempFolder, "${videoFileName}.log")
    private val videoPidFile = File(config.tempFolder, "${videoFileName}.pid")

    private val remoteVideoPath = File(remote.tmpDir, videoFileName).absolutePath
    private val remoteVideoLogPath = File(remote.tmpDir, "${videoFileName}.log").absolutePath
    private val remoteVideoPidPath = File(remote.tmpDir, "${videoFileName}.pid").absolutePath

    private val mjpegStreamUrl = URL("http://${remote.publicHostName}:${mjpegServerPort}")

    override fun toString(): String = "${javaClass.simpleName} for $ref"

    override fun delete() {
        if (!remote.isLocalhost()) {
            val remoteVideoPaths = listOf(
                remoteVideoPath,
                remoteVideoLogPath,
                remoteVideoPidPath
            ).joinToString(" ")
            remote.shell("rm -vf $remoteVideoPaths")
        }

        listOf(
            videoFile,
            videoLogFile,
            videoPidFile
        ).forEach {
            if (it.exists()) {
                it.delete()
            }
        }
    }

    override fun start() {
        logger.debug(logMarker, "Starting video recording - ${videoFile.name}")
        val command = listOf(
            config.remoteVideoRecorder.absolutePath,
            udid,
            mjpegStreamUrl.toExternalForm(),
            remoteVideoPath,
            remoteVideoLogPath,
            remoteVideoPidPath
        ).joinToString(" ")
        val result = remote.shell(command)

        if (result.isSuccess) {
            logger.info(logMarker, "Started video recording ${videoFile.name}")
        } else {
            val errorMessage =
                "Failed to start video recording ${videoFile.name}. Exit code: ${result.exitCode} StdOut: ${result.stdOut} StdErr: ${result.stdErr}. Log contents: ${getRecordingLog()}"
            logger.error(errorMessage)
            throw VideoRecordingException(errorMessage)
        }
    }

    override fun stop() {
        logger.debug(logMarker, "Stopping video recording ${videoFile.name}")
        val stopResult = remote.shell("/usr/bin/pkill -SIGINT -f ${remoteVideoPath}")

        when (stopResult.exitCode) {
            0 -> {
                logger.debug(logMarker, "Stopping video recording ${videoFile.name}. Successfully sent SIGINT")
                Thread.sleep(500) // Give some time for the process to handle SIGINT
            }
            1 -> logger.warn(logMarker, "Stopping video recording ${videoFile.name}. No process found to send SIGINT")
            else -> logger.error(
                logMarker,
                "Stopping video recording ${videoFile.name}. Failed to send SIGINT. Exit code: ${stopResult.exitCode} StdOut: ${stopResult.stdOut} StdErr: ${stopResult.stdErr}"
            )
        }

        var videoRecorderExited = false
        val duration = Duration.ofSeconds(10)
        pollFor(
            duration,
            reasonName = "Waiting up to ${duration.seconds} seconds for video recording to stop",
            shouldReturnOnTimeout = true,
            retryInterval = Duration.ofMillis(1000),
            logger = logger,
            marker = logMarker
        ) {
            val processList = remote.shell("ps ax")
            if (processList.isSuccess) {
                videoRecorderExited = processList.stdOut.trim().lines().none { it.contains(remoteVideoPath) }
                videoRecorderExited
            } else {
                false
            }
        }

        if (videoRecorderExited) {
            logger.info(logMarker, "Stopped video recording ${videoFile.name}. Successfully waited for video recording to exit")
        } else {
            logger.info(logMarker, "Failed to stop video recording ${videoFile.name}. Recorder process is still running after waiting for ${duration.seconds} seconds")
        }
    }

    private fun downloadRemoteFile(remotePath: String, localFile: File) {
        try {
            remote.scpFromRemoteHost(remotePath, localFile.absolutePath, Duration.ofSeconds(60))
        } catch (e: FileNotFoundException) {
            logger.error("Failed to find $remotePath at ${remote.hostName}")
        }
    }

    override fun getRecordingLog(): String {
        if (!remote.isLocalhost()) {
            downloadRemoteFile(remoteVideoLogPath, videoLogFile)
        }

        return if (videoLogFile.exists()) {
            videoLogFile.readText()
        } else {
            "File $videoLogFile not found"
        }
    }

    override fun getRecording(): ByteArray {
        logger.info(logMarker, "Getting video recording ${videoFile.name}")

        if (!remote.isLocalhost()) {
            downloadRemoteFile(remoteVideoPath, videoFile)
        }

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
        private val whiteSpacesRegex = Regex("\\s+")
    }
}
