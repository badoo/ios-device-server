package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.data.DesiredCapabilities
import com.badoo.automation.deviceserver.data.DeviceInfo
import com.badoo.automation.deviceserver.host.management.DesiredCapabilitiesMatcher
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDevice

class SimulatorProvider(
        val remote: IRemote,
        private val desiredCapsMatcher: DesiredCapabilitiesMatcher = DesiredCapabilitiesMatcher()
) : ISimulatorProvider {
    private var cache: List<FBSimctlDevice> = emptyList()

    override fun match(desiredCaps: DesiredCapabilities, usedUdids: Set<String>): FBSimctlDevice? {
        val matchList =
                when {
                    desiredCaps.udid != null -> listOfNotNull(findBy(desiredCaps.udid))
                    desiredCaps.existing -> list().filter { desiredCapsMatcher.isMatch(DeviceInfo(it), desiredCaps) }
                    else -> return create(desiredCaps.model, desiredCaps.os, true)
                }

        val firstMatch = matchList.find { !usedUdids.contains(it.udid) }

        if (firstMatch != null) return firstMatch

        return create(desiredCaps.model, desiredCaps.os, false)
    }

    override fun findBy(udid: String): FBSimctlDevice? {
        return remote.fbsimctl.listDevice(udid)
    }

    override fun list(): List<FBSimctlDevice> {
        if (cache.isEmpty()) {
            cache = remote.fbsimctl.listSimulators().filter { !it.model.isBlank() && !it.os.isBlank() }
        }
        return cache
    }

    override fun create(model: String?, os: String?, transitional: Boolean): FBSimctlDevice {
        cache = emptyList()
        return remote.fbsimctl.create(model, os, transitional)
    }
}