package com.badoo.automation.deviceserver.ios.simulator

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.data.DeviceRef
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.lang.RuntimeException

class SimulatorProcess(
    private val remote: IRemote,
    private val udid: UDID,
    private val deviceRef: DeviceRef
) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val commonLogMarkerDetails = mapOf(
        LogMarkers.DEVICE_REF to deviceRef,
        LogMarkers.UDID to udid,
        LogMarkers.HOSTNAME to remote.publicHostName
    )
    private val logMarker: Marker = MapEntriesAppendingMarker(commonLogMarkerDetails)

    fun getSimulatorMainProcessPid(): Int? {
        val command = listOf("/usr/bin/pgrep", "-fl", "launchd_sim")
        val result = remote.execIgnoringErrors(command)

        if (result.isSuccess) {
            val simulatorProcesses = result.stdOut
                .lines()
                .filter { it.contains(udid) }

            val simulatorProcess = simulatorProcesses.firstOrNull()

            if (simulatorProcess == null) {
                logger.error(logMarker, "No launchd_sim process is found for Simulator $deviceRef")
                return null
            }

            return simulatorProcess.split(" ").first().toInt()
        }

        if (result.exitCode == 1) {
            logger.error(logMarker, "No launchd_sim processes are found on ${remote.publicHostName}. Result: $result")
            return null
        }

        val errorMessage = "Failed to get process list for simulators at $deviceRef. Result: $result"
        logger.error(logMarker, errorMessage)
        throw RuntimeException(errorMessage)
    }

    fun terminateMainSimulatorProcess() {
        val simulatorPid = getSimulatorMainProcessPid()
        if (simulatorPid == null) {
            logger.error(logMarker, "No launchd_sim process is found for Simulator $deviceRef. Unable to terminate process.")
        } else {
            val result = remote.execIgnoringErrors(listOf("/bin/kill", "-15", "$simulatorPid"))

            if (!result.isSuccess) {
                logger.error(logMarker, "Failed to send TERM signal to launchd_sim process for Simulator $deviceRef. Result: $result")
            }
        }
    }

    fun terminateChildProcess(processName: String) {
        val mainProcessPid = getSimulatorMainProcessPid()

        if (mainProcessPid == null) {
            throw IllegalStateException("No launchd_sim process is found for simulator with udid: $udid and ref: $deviceRef")
        }

        // Sends SIGKILL to process with parent pid $mainProcessPid and name $processName
        val command = listOf("/usr/bin/pkill", "-9", "-P", "$mainProcessPid", "-f", processName)
        remote.execIgnoringErrors(command)
    }
}
