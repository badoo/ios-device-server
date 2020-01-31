package com.badoo.automation.deviceserver.ios.simulator.video

import com.badoo.automation.deviceserver.command.ChildProcess
import com.badoo.automation.deviceserver.data.DeviceInfo
import com.badoo.automation.deviceserver.host.IRemote
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctl.Companion.FBSIMCTL_BIN
import com.badoo.automation.deviceserver.ios.proc.LongRunningProc
import com.badoo.automation.deviceserver.util.ensure
import com.badoo.automation.deviceserver.util.pollFor
import java.io.File
import java.time.Duration
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SimulatorVideoRecorder(
    private val deviceInfo: DeviceInfo,
    private val remote: IRemote,
    private val childFactory: (remoteHost: String, username: String, cmd: List<String>, commandEnvironment: Map<String, String>,
                                   out_reader: (line: String) -> Unit, err_reader: (line: String) -> Unit
        ) -> ChildProcess? = ChildProcess.Companion::fromCommand,
    private val recorderStopTimeout: Duration = RECORDER_STOP_TIMEOUT,
    location: File
) : LongRunningProc(deviceInfo.udid, remote.hostName) {

    private val udid = deviceInfo.udid

    @Volatile
    private var isStarted: Boolean = false

    private val recordingLocation = location.absoluteFile

    private val lock = ReentrantLock(true)

    override fun toString(): String = udid

    override fun checkHealth(): Boolean = childProcess?.isAlive() ?: false

    private val uniqueTag = "video-recording-$udid"

    fun delete() {
        logger.debug(logMarker, "Deleting video recording")

        val result = remote.execIgnoringErrors(listOf("rm", "-f", recordingLocation.toString()))
        ensure(result.isSuccess) {
            SimulatorVideoRecordingException("Could not delete stale recordings. Reason: $result")
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

            val properties = surfaceAttributes()

            if (!properties.pixelFormat.contains("BGRA")) {
                throw RuntimeException("Unexpected pixel format ${properties.pixelFormat}")
            }

            val frameWidth = properties.width
            val frameHeight = properties.height

            val cmd = shell(videoRecordingCmd(fps = 5, frameWidth = frameWidth, frameHeight = frameHeight))

            childProcess = childFactory(remote.hostName, remote.userName, cmd, mapOf(),
                { logger.debug(logMarker, "$udid: VideoRecorder <o>: ${it.trim()}") },
                { logger.debug(logMarker, "$udid: VideoRecorder <e>: ${it.trim()}") }
            )

            logger.info(logMarker, "Started video recording")
            isStarted = true
        }
    }

    fun stop() {
        lock.withLock {
            if (!isStarted) {
                val message = "Video recording has not yet started"
                logger.warn(logMarker, message)
                throw SimulatorVideoRecordingException(message)
            }

            try {
                logger.info(logMarker, "Stopping video recording")

                // Regex.escape is incompatible with pkill regex, so let's not escape and hope
                val pattern = """^$FFMPEG_PATH.*$uniqueTag"""
                remote.execIgnoringErrors(listOf("pkill", "-f", pattern))

                pollFor(recorderStopTimeout, "Stop video recording", false, Duration.ofMillis(500), logger, logMarker) {
                    logger.warn("${childProcess?.isAlive()}")

                    childProcess?.isAlive() == false
                }
            }
            finally {
                childProcess?.kill()
                childProcess = null
                logger.info(logMarker, "Stopped video recording")
                isStarted = false
            }
        }
    }

    fun getRecording(): ByteArray {
        logger.info(logMarker, "Getting video recording")

        val videoFile = recordingLocation

        // TODO: is there a better way to read binary file over ssh without rsyncing?
        // We should get rid of ssh and move to having 1 http server per 1 host and some proxy node to tie them together
        // once we have proper deployment solution for our macOS machines
        try {
            val bytes = remote.captureFile(videoFile)
            logger.info(logMarker, "Received video recording. Size ${bytes.size} bytes")
            return bytes
        } catch (e: RuntimeException) {
            val message = "Could not read video file. Cause: ${e.message}"
            logger.error(message)
            throw SimulatorVideoRecordingException(message)
        }
    }

    fun dispose() {
        if (childProcess?.isAlive() != true) {
            return
        }

        logger.info(logMarker, "Terminating video recording process")
        childProcess?.kill()
        delete()
        logger.info(logMarker, "Disposed video recording")
    }

    private fun shell(command: String): List<String> {
        return if (remote.isLocalhost()) {
            listOf("bash", "-c", command)
        } else {
            listOf(command)
        }
    }

    private fun videoRecordingCmd(fps: Int = 5, frameWidth: Int, frameHeight: Int, crf: Int = 35): String {
        val fbsimctlStream = "$FBSIMCTL_BIN $udid stream --bgra --fps $fps -"

        val maxRecording = Duration.ofMinutes(15) // Video recording duration is capped

        val frameSize = "${frameWidth}x$frameHeight"
        val recorder = "$FFMPEG_PATH -hide_banner -loglevel warning " +
                "-f rawvideo " +
                "-pixel_format bgra " +
                "-s:v $frameSize " +
                "-framerate $fps " +
                "-i pipe:0 " +
                "-f mp4 -vcodec h264 " +
                "-t ${durationToString(maxRecording)} " +
                "-crf $crf " +
                "-metadata comment=$uniqueTag " +
                "-y $recordingLocation"

        return "set -xeo pipefail; $fbsimctlStream | $recorder"
    }

    private fun durationToString(duration: Duration): String {
        return "%02d:%02d:%02d".format(duration.toHoursPart(), duration.toMinutesPart(), duration.toSecondsPart())
    }

    private fun surfaceAttributes(): IOSurfaceAttributes {
        val cmd = shell("set -eo pipefail; $FBSIMCTL_BIN --debug-logging $udid stream --bgra --fps 1 - | exit")

        val rv = remote.execIgnoringErrors(cmd)


        try {
            return IOSurfaceAttributes.fromFbSimctlLog(rv.stdErr)
        } catch(e: RuntimeException) {
            throw(RuntimeException("Could not get IO surface attributes: $rv", e))
        }
    }

    private companion object {
        const val FFMPEG_PATH = "/usr/local/bin/ffmpeg"
        val RECORDER_STOP_TIMEOUT: Duration = Duration.ofSeconds(3)
    }
}