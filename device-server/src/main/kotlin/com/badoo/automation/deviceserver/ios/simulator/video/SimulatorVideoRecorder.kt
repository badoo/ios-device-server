package com.badoo.automation.deviceserver.ios.simulator.video

import com.badoo.automation.deviceserver.command.ChildProcess
import com.badoo.automation.deviceserver.data.UDID
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
        private val udid: UDID,
        private val remote: IRemote,
        private val childFactory: (remoteHost: String, username: String, cmd: List<String>, isInteractiveShell: Boolean,
                                   environment: Map<String, String>,
                                   out_reader: (line: String) -> Unit, err_reader: (line: String) -> Unit
        ) -> ChildProcess? = ChildProcess.Companion::fromCommand,
        private val recorderStopTimeout: Duration = RECORDER_STOP_TIMEOUT
) : LongRunningProc(udid, remote.hostName) {
    @Volatile private var isStarted: Boolean = false
    private val lock = ReentrantLock(true)
    private val startVideoRecordingCommand = "set -x ;" +
            "$FBSIMCTL_BIN $udid record start -- listen -- record stop -- diagnose &" + // background fbsimctl
            "PID=$! && " +
            "trap \"kill \$PID\" EXIT && " + // ensure we kill fbsimctl even when ssh is killed without sending input to stdin
            "read && " + // block until there is an input on stdin
            "kill -INT \$PID" // gracefully terminate fbsimctl recording

    override fun toString(): String = udid

    override fun checkHealth(): Boolean = childProcess?.isAlive() ?: false

    fun delete() {
        logger.debug(logMarker, "Deleting video recording")
        val info = remote.fbsimctl.diagnose(udid)

        if (info.videoLocation == null || info.videoLocation.isBlank()) {
            logger.debug(logMarker, "No video recording to delete")
            return
        }

        val result = remote.execIgnoringErrors(listOf("rm", "-f", info.videoLocation))
        ensure(result.isSuccess) {
            SimulatorVideoRecordingException("Could not delete stale recordings. Reason: $result")
        }
    }

    fun dispose() {
        if (childProcess?.isAlive() != true) {
            return
        }

        logger.info(logMarker, "Terminating video recording process")
        childProcess?.kill(Duration.ofSeconds(3))
        delete()
        logger.info(logMarker, "Disposed video recording")
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
            childProcess = childFactory(remote.hostName, remote.userName, listOf(startVideoRecordingCommand), false,
                mapOf<String, String>(),
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

            logger.info(logMarker, "Stopping video recording")
            childProcess?.writeStdin("q\n")
            pollFor(recorderStopTimeout, "Stop video recording", false, Duration.ofMillis(500), logger, logMarker) {
                childProcess?.isAlive() == false
            }
            childProcess?.kill()
            childProcess = null
            logger.info(logMarker, "Stopped video recording")
            isStarted = false
        }
    }

    fun getRecording(): ByteArray {
        logger.info(logMarker, "Getting video recording")
        val info = remote.fbsimctl.diagnose(udid)

        if (info.videoLocation == null) {
            val message = "Could not find diagnostic video events in $info"
            logger.error(logMarker, message)
            throw SimulatorVideoRecordingException(message)
        }

        logger.debug(logMarker, "Found video recording ${info.videoLocation}")
        val videoFile: File = tryCompressVideo(File(info.videoLocation))

        // TODO: is there a better way to read binary file over ssh without rsyncing?
        // We should get rid of ssh and move to having 1 http server per 1 host and some proxy node to tie them together
        // once we have proper deployment solution for our macOS machines
        val result = remote.captureFile(videoFile)
        if (!result.isSuccess) {
            val message = "Could not read video file $result"
            logger.error(message)
            throw SimulatorVideoRecordingException(message)
        }
        logger.info(logMarker, "Received video recording. Size ${result.stdOutBytes.size} bytes")
        return result.stdOutBytes
    }

    // because we compress videos, we can't simple forward response from fbsimctl http request to /diagnose/video
    // if we move compression to consumer side, this method can be simplified
    private fun tryCompressVideo(srcVideo: File): File {
        //FIXME: move this ["which", FFMPEG_PATH] logic to host checker. no need to do it every time when fetching a video.
        if (!remote.execIgnoringErrors(listOf("which", FFMPEG_PATH)).isSuccess) {
            return srcVideo
        }

        val dstVideo = File(srcVideo.parent, "compressed.mp4")
        val compressionResult = remote.execIgnoringErrors(
            listOf(
                FFMPEG_PATH,
                "-loglevel", "panic",
                "-i", srcVideo.absolutePath,
                "-y", "-preset", "ultrafast",
                dstVideo.absolutePath
            )
        )

        if (compressionResult.isSuccess) {
            remote.execIgnoringErrors(listOf("rm", "-f", srcVideo.absolutePath))
            logger.debug(logMarker, "Successfully compressed video to $dstVideo")
            return dstVideo
        } else {
            logger.error(logMarker, "Failed to compress video $srcVideo. $compressionResult")
            remote.execIgnoringErrors(listOf("rm", "-f", dstVideo.absolutePath))
            return srcVideo
        }
    }

    private companion object {
        const val FFMPEG_PATH = "/usr/local/bin/ffmpeg"
        val RECORDER_STOP_TIMEOUT: Duration = Duration.ofSeconds(3)
    }
}