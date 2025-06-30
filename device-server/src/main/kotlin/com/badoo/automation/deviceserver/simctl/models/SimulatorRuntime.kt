package com.badoo.automation.deviceserver.simctl.models

import kotlinx.serialization.Serializable

@Serializable
data class SimulatorRuntime(
    val build: String, // e.g. 22D8075
    val deletable: Boolean? = null, // e.g. true
    val identifier: String, // e.g. 768A6313-A660-4FC2-83AD-6E0DFCE1B421
    val kind: String? = null, // e.g. Cryptex Disk Image
    val lastUsedAt: String? = null, // e.g. 2025-06-12T15:54:59Z
    val mountPath: String, // e.g. /Library/Developer/CoreSimulator/Volumes/iOS_22D8075
    val parentIdentifier: String? = null, // e.g. 656CE621-5315-4EAF-90B1-68EAC9D7AACF
    val parentImagePath: String? = null, // e.g. /System/Library/AssetsV2/com_apple_MobileAsset_iOSSimulatorRuntime/fb8de9a3438213a1dda310a6ab5eac9ddf8db858.asset/AssetData/044-77235-048.dmg
    val parentMountPath: String? = null, // e.g. /Library/Developer/CoreSimulator/Cryptex/Images/bundle/SimRuntimeBundle-656CE621-5315-4EAF-90B1-68EAC9D7AACF
    val path: String? = null, // e.g. /Library/Developer/CoreSimulator/Cryptex/Images/bundle/SimRuntimeBundle-656CE621-5315-4EAF-90B1-68EAC9D7AACF/Restore/044-77188-048.dmg
    val platformIdentifier: String, // e.g. com.apple.platform.iphonesimulator
    val runtimeBundlePath: String, // e.g. /Library/Developer/CoreSimulator/Volumes/iOS_22D8075/Library/Developer/CoreSimulator/Profiles/Runtimes/iOS 18.3.simruntime
    val runtimeIdentifier: String, // e.g. com.apple.CoreSimulator.SimRuntime.iOS-18-3
    val signatureState: String? = null, // e.g. Verified
    val sizeBytes: Long? = null, // e.g. 8708125252
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