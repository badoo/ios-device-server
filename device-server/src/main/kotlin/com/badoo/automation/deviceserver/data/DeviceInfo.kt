package com.badoo.automation.deviceserver.data

import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDevice

typealias UDID = String

// FIXME: DeviceInfo is same as FBSimctlDevice
data class DeviceInfo (
        val udid: UDID,
        val model: String,
        val os: String,
        val arch: String,
        val name: String
) {
    constructor(device: FBSimctlDevice):
            this(device.udid, device.model, device.os, device.arch, device.name)
}
