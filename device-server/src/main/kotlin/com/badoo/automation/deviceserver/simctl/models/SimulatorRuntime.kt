package com.badoo.automation.deviceserver.simctl.models

import kotlinx.serialization.Serializable

@Serializable
data class SimulatorRuntime(
    val build: String, // e.g. 22D8075
    val identifier: String, // e.g. 768A6313-A660-4FC2-83AD-6E0DFCE1B421
    val platformIdentifier: String, // e.g. com.apple.platform.iphonesimulator
    val runtimeIdentifier: String, // e.g. com.apple.CoreSimulator.SimRuntime.iOS-18-3
    val state: String, // e.g. Ready
    val version: String // e.g. 18.3.1
) {
    override fun toString(): String {
        return "SimulatorRuntime(build='$build', version='$version', state='$state', runtimeIdentifier='$runtimeIdentifier', platformIdentifier='$platformIdentifier')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SimulatorRuntime) return false

        if (runtimeIdentifier != other.runtimeIdentifier) return false
        if (platformIdentifier != other.platformIdentifier) return false

        return true
    }

    override fun hashCode(): Int {
        return runtimeIdentifier.hashCode() +
                platformIdentifier.hashCode()
    }
}