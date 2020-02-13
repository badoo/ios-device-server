package com.badoo.automation.deviceserver.ios.simulator

import com.badoo.automation.deviceserver.LogMarkers
import com.badoo.automation.deviceserver.data.DeviceRef
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import net.logstash.logback.marker.MapEntriesAppendingMarker
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import java.util.*

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

    fun terminateChildProcess(processName: String) {
        val mainProcessPid = getSimulatorMainProcessPid()

        // Sends SIGKILL to all processes that:
        // 1. have parent pid $mainProcessPid
        // 2. and full command line contains substring $processName

        remote.execIgnoringErrors(listOf("/usr/bin/pkill", "-9", "-P", "$mainProcessPid", "-f", processName))
    }

    fun getSimulatorMainProcessPid(): Int {
        val result = remote.execIgnoringErrors(listOf("/usr/bin/pgrep", "-fl", "launchd_sim"))

        if (result.isSuccess) {
            return parseSimulatorPid(result.stdOut)
        }

        val errorMessage = if (result.exitCode == 1) {
            "No launchd_sim processes found on ${remote.publicHostName}. Result: $result"
        } else {
            "Failed to get process list for simulators at $deviceRef. StdErr: ${result.stdErr}"
        }

        logger.error(logMarker, errorMessage)
        throw IllegalStateException(errorMessage)
    }

    private fun parseSimulatorPid(result: String): Int {
        val simulatorProcess = result.lineSequence().firstOrNull { it.contains(udid) && !it.contains("pgrep") }

        if (simulatorProcess != null) {
            return Scanner(simulatorProcess).nextInt()
        }

        val errorMessage = "No launchd_sim process for $udid found on ${remote.publicHostName}. Result: $result"
        logger.error(logMarker, errorMessage)
        throw IllegalStateException(errorMessage)
    }
}
