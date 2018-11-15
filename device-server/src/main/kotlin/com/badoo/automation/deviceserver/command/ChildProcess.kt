package com.badoo.automation.deviceserver.command

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.host.Remote
import com.badoo.automation.deviceserver.ios.proc.LongRunningProcessListener
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import java.time.Duration

class ChildProcess private constructor(
    command: List<String>,
    executor: IShellCommand,
    remoteHostname: String,
    environment: Map<String, String>,
    private val processListener: LongRunningProcessListener
) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val logMarker = MapEntriesAppendingMarker(mapOf(LogMarkers.HOSTNAME to remoteHostname))

    init {
        logger.debug(logMarker, "Starting long living process from command [$command]")
        executor.startProcess(command, environment, processListener = processListener)
        logger.debug(logMarker, "Started long living process $this from command [$command]")
    }

    override fun toString(): String = "< PID: ${processListener.pid}>"

    fun isAlive(): Boolean = processListener.isAlive

    fun kill(timeOut: Duration = Duration.ofSeconds(2)) {
        logger.debug(logMarker, "Sending SIGTERM to process $this")
        val result = processListener.destroy(false, timeOut)
        if (result == Int.MIN_VALUE) {
            logger.warn(logMarker, "Process $this did not terminate gracefully within [${timeOut.seconds}] seconds. Sending SIGKILL")
            processListener.destroy(true, timeOut)
        }
    }

    fun writeStdin(string: String) {
        processListener.writeStdin(string)
    }

    companion object {
        fun fromCommand(
            remoteHost: String, userName: String, cmd: List<String>, isInteractiveShell: Boolean,
            environment: Map<String, String>,
            out_reader: ((line: String) -> Unit)?,
            err_reader: ((line: String) -> Unit)?
        ): ChildProcess {
            val executor = Remote.getRemoteCommandExecutor(hostName = remoteHost, userName = userName, isInteractiveShell = isInteractiveShell)
            return ChildProcess(cmd, executor, remoteHost, environment, LongRunningProcessListener(out_reader, err_reader))
        }
    }
}

