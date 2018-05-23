package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.data.DesiredCapabilities
import com.badoo.automation.deviceserver.data.DeviceInfo
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDevice

interface IDesiredCapabilitiesMatcher {
    fun isMatch(actual: FBSimctlDevice, desiredCaps: DesiredCapabilities): Boolean
    fun isMatch(actual: DeviceInfo, desiredCaps: DesiredCapabilities): Boolean
}

class DesiredCapabilitiesMatcher : IDesiredCapabilitiesMatcher {
    override fun isMatch(actual: DeviceInfo, desiredCaps: DesiredCapabilities): Boolean {
        if (desiredCaps.udid != null) {
            return desiredCaps.udid == actual.udid
        }

        with(desiredCaps) {
            return (model == null || model == actual.model) && (os == null || isRuntimeMatch(os, actual.os))
        }

    }

    override fun isMatch(actual: FBSimctlDevice, desiredCaps: DesiredCapabilities): Boolean {
        val deviceInfo = DeviceInfo(actual)

        return isMatch(deviceInfo, desiredCaps)
    }

    internal fun isRuntimeMatch(desired: String, actual: String): Boolean {
        val desiredRuntime = RuntimeVersion(desired)
        val actualRuntime = RuntimeVersion(actual)

        if (desiredRuntime.name != actualRuntime.name) {
            return false
        }

        val significantCount = desiredRuntime.fragments.count()

        return desiredRuntime.fragments.take(significantCount) == actualRuntime.fragments.take(significantCount)
    }
}
