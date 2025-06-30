package com.badoo.automation.deviceserver.simctl.models

import kotlinx.serialization.Serializable

@Serializable
data class DeviceType(
    /** Possible value: "iPhone" */
    val productFamily: String,
    /** Possible value: "/Library/Developer/CoreSimulator/Profiles/DeviceTypes/iPhone 16 Pro.simdevicetype" */
    val bundlePath: String,
    /** Possible value: "com.apple.CoreSimulator.SimDeviceType.iPhone-16-Pro" */
    val identifier: String,
    /** Possible value: "iPhone17,1" */
    val modelIdentifier: String,
    /** Possible value: "18.0.0" */
    val minRuntimeVersionString: String,
    /** Possible value: "iPhone 16 Pro" */
    val name: String
) {
    override fun toString(): String {
        return "DeviceType(productFamily='$productFamily', identifier='$identifier', name='$name')"
    }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceType) return false

        if (identifier != other.identifier) return false

        return true
    }
}