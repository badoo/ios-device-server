package com.badoo.automation.deviceserver.ios.simctl.dto

data class ListResponseDTO(
    val devicetypes: List<DeviceType>,
    val runtimes: List<RunTime>,
    val devices: Map<String, List<Any>>,
    val pairs: Map<String, Any>
)

data class DeviceType(
    val bundlePath: String,
    val name: String,
    val identifier: String,
    val productFamily: String,
    val minRuntimeVersion: Int = 0,
    val maxRuntimeVersion: Int = 0
) {
    /**
     * Not including minRuntimeVersion and maxRuntimeVersion
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DeviceType

        if (bundlePath != other.bundlePath) return false
        if (name != other.name) return false
        if (identifier != other.identifier) return false
        if (productFamily != other.productFamily) return false

        return true
    }

    /**
     * Not including minRuntimeVersion and maxRuntimeVersion
     */
    override fun hashCode(): Int {
        var result = bundlePath.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + identifier.hashCode()
        result = 31 * result + productFamily.hashCode()
        return result
    }
}

data class Device(
    val dataPath: String,
    val logPath: String,
    val udid: String,
    val isAvailable: Boolean,
    val deviceTypeIdentifier: String,
    val state: String,
    val name: String
)

data class RunTime(
    val bundlePath: String,
    val buildversion: String,
    val runtimeRoot: String,
    val identifier: String,
    val version: String,
    val isAvailable: Boolean,
    val name: String,
    val supportedDeviceTypes: List<DeviceType>
)
