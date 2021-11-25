package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.data.DesiredCapabilities
import com.badoo.automation.deviceserver.data.DeviceInfo
import com.badoo.automation.deviceserver.data.UDID
import com.badoo.automation.deviceserver.host.management.DesiredCapabilitiesMatcher
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDevice
import java.io.File
import java.lang.RuntimeException

class SimulatorProvider(
        val remote: IRemote,
        simulatorBackupsConfiguration: String?,
        private val desiredCapsMatcher: DesiredCapabilitiesMatcher = DesiredCapabilitiesMatcher()
) {
    val deviceSetPath: String by lazy { remote.fbsimctl.defaultDeviceSet() }
    private val simulatorBackupsPath = File(simulatorBackupsConfiguration ?: deviceSetPath)

    private var cachedSimulatorList: List<FBSimctlDevice> = emptyList()
    private var cachedBackupsList: List<String> = emptyList()

    fun provideSimulator(desiredCaps: DesiredCapabilities, usedUdids: Set<String>): FBSimctlDevice? {
        val simulators = listSimulators()

        if (desiredCaps.udid != null && desiredCaps.udid.isNotBlank()) {
            val matched = simulators.find { fbSimctlDevice -> desiredCaps.udid == fbSimctlDevice.udid }

            if (matched == null) {
                throw RuntimeException("Unable to find requested device with UDID ${desiredCaps.udid}. List of known devices is $simulators")
            }

            if (usedUdids.contains(matched.udid)) {
                throw RuntimeException("Simulator with UDID ${matched.udid} is already in use. List of used devices is $usedUdids")
            }

            return matched
        }

        val matched: FBSimctlDevice? = simulators.find { fbSimctlDevice ->
            val deviceInfo = DeviceInfo(fbSimctlDevice)
            desiredCapsMatcher.isMatch(deviceInfo, desiredCaps) && !usedUdids.contains(deviceInfo.udid) && backupExists(deviceInfo.udid)
        }

        return matched ?: create(desiredCaps.model, desiredCaps.os)
    }

    private fun listSimulators(): List<FBSimctlDevice> {
        if (cachedSimulatorList.isEmpty()) {
            cachedSimulatorList = remote.fbsimctl.listSimulators().filter { !it.model.isBlank() && !it.os.isBlank() }
        }
        return cachedSimulatorList
    }

    private fun backupExists(udid: UDID): Boolean {
        if (cachedBackupsList.isEmpty()) {
            val command = listOf("/bin/ls", "-1", simulatorBackupsPath.absolutePath)
            val commandResult = remote.exec(command, mapOf<String, String>(), false, 60L)
            val stdOut: String = commandResult.stdOut
            val lines: List<String> = stdOut.lines()
            cachedBackupsList = lines
        }

        return cachedBackupsList.find { it.contains(udid) } != null
    }

    private fun create(model: String?, os: String?): FBSimctlDevice {
        cachedSimulatorList = emptyList()
        cachedBackupsList = emptyList()
        return remote.xcrunSimctl.create(model, os)
    }
}
