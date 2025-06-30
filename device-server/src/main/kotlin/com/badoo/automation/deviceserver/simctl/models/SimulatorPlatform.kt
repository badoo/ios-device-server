package com.badoo.automation.deviceserver.simctl.models

enum class SimulatorPlatform(
    val platformName: String,
    val platformIdentifier: String,
) {
    IOS("iOS", "com.apple.platform.iphonesimulator"),
    WATCHOS("watchOS", "com.apple.platform.watchsimulator"),
    VISIONOS("visionOS", "com.apple.platform.xrsimulator"),
    TVOS("tvOS", "com.apple.platform.appletvsimulator");

    override fun toString(): String {
        return "$platformName ($platformIdentifier)"
    }
}