package com.badoo.automation.deviceserver.ios.simulator

import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.IRemote
import java.lang.RuntimeException

class SimulatorProcess(
    private val remote: IRemote,
    private val udid: UDID
) {
    val mainProcessPid: Int
        get() {
            val command = listOf("/usr/bin/pgrep", "-fl", "launchd_sim")
            val result = remote.execIgnoringErrors(command)

            check(result.isSuccess) {
                "No launchd_sim process is found for simulator with udid: $udid. Result: $result"
            }

            val processList = result
                .stdOut
                .lines()
                .filter { it.contains(udid) }

            check(processList.isNotEmpty()) {
                "No launchd_sim process is found for simulator with udid: $udid. Result: $result"
            }

            return processList
                .first()
                .split(" ")
                .first()
                .toInt()
        }

    fun terminateChildProcess(processName: String) {
        // Sends SIGKILL to process with parent pid $mainProcessPid and name $processName
        val command = listOf("/usr/bin/pkill", "-9", "-P", "$mainProcessPid", "-f", processName)
        remote.execIgnoringErrors(command)
    }

    private fun childProcessPid(processName: String): Int {
        val command = listOf("/usr/bin/pgrep", "-P", "$mainProcessPid", "-f", processName)
        val result = remote.execIgnoringErrors(command)

        if (result.isSuccess) {
            return result.stdOut.toInt()
        }

        if (result.exitCode == 1) {
            return -1
        }

        throw RuntimeException("Failed to get child process PID. StdErr: ${result.stdErr}")
    }

    fun isChildProcessRunning(processName: String): Boolean {
        return childProcessPid(processName) > 0
    }
}
