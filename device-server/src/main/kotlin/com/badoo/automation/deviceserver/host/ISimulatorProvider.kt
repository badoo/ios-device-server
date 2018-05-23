package com.badoo.automation.deviceserver.host

import com.badoo.automation.deviceserver.data.DesiredCapabilities
import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDevice

interface ISimulatorProvider {
    fun findBy(udid: String): FBSimctlDevice?
    fun list(): List<FBSimctlDevice>
    fun create(model: String?, os: String?, transitional: Boolean): FBSimctlDevice
    fun match(desiredCaps: DesiredCapabilities, usedUdids: Set<String>): FBSimctlDevice?
}
