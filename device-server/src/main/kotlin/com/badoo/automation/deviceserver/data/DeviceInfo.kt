package com.badoo.automation.deviceserver.data

import com.badoo.automation.deviceserver.ios.fbsimctl.FBSimctlDevice
import com.badoo.automation.deviceserver.simctl.models.Simulator

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

    constructor(device: Simulator):
            this(
                udid = device.udid,
                model = device.model ?: device.deviceTypeIdentifier,
                os = "iOS ${device.osVersion}",
                arch = "x86",
                name = device.model ?: device.name
            )

        override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as DeviceInfo

            return udid == other.udid
        }

    val osMajorVersion: Int = os.substringAfter("iOS").trim().split(".").first().toInt()
    val isRemoteNotificationsSupported get() : Boolean = osMajorVersion > 15
}
