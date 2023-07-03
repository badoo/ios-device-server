package com.badoo.automation.deviceserver.host.management

import com.badoo.automation.deviceserver.data.DesiredCapabilities
import com.badoo.automation.deviceserver.data.DeviceInfo
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDevice

class DesiredCapabilitiesMatcher {

    fun isMatch(actual: DeviceInfo, desiredCaps: DesiredCapabilities): Boolean {
        with(desiredCaps) {
            return if (udid.isNullOrBlank()) {
                (model.isNullOrBlank() || model == actual.model) && (os.isNullOrBlank() || isRuntimeMatch(os!!, actual.os))
            } else {
                udid == actual.udid
            }
        }
    }

    internal fun isRuntimeMatch(desired: String, actual: String): Boolean {
        val desiredRuntime = RuntimeVersion(desired)

        val actualRuntime = try {
            RuntimeVersion(actual)
        } catch (e: IllegalArgumentException) {
            return false
        }

        if (desiredRuntime.name != actualRuntime.name) {
            return false
        }

        val significantCount = desiredRuntime.fragments.count()

        return desiredRuntime.fragments.take(significantCount) == actualRuntime.fragments.take(significantCount)
    }
}
