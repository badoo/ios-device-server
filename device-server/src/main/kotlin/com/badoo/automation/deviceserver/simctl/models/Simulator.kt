package com.badoo.automation.deviceserver.simctl.models

import kotlinx.serialization.Serializable

@Serializable
data class Simulator(
    val lastBootedAt: String? = null, // e.g. "2025-06-26T19:48:23Z"
    val dataPath: String, // e.g. "/Users/qa/Library/Developer/CoreSimulator/Devices/FD3783BD-3CA6-4C93-9DBB-6620617BC330/data"
    val logPath: String, // e.g. "/Users/qa/Library/Logs/CoreSimulator/FD3783BD-3CA6-4C93-9DBB-6620617BC330"
    val udid: String, // e.g. "FD3783BD-3CA6-4C93-9DBB-6620617BC330"
    val isAvailable: Boolean, // true or false
    val deviceTypeIdentifier: String, // e.g. "com.apple.CoreSimulator.SimDeviceType.iPhone-15-Pro-Max"
    var osVersion: String? = null, // e.g. "com.apple.CoreSimulator.SimRuntime.iOS-18-5"
    val state: String, // e.g. "Shutdown"
    val name: String // e.g. "iPhone 15 Pro Max"
) {
    override fun toString(): String {
        return "Simulator(name='$name', osVersion='$osVersion', udid='$udid', state='$state', deviceTypeIdentifier='$deviceTypeIdentifier', isAvailable=$isAvailable, lastBootedAt=$lastBootedAt)"
    }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Simulator

        return udid == other.udid
    }

    override fun hashCode(): Int {
        return udid.hashCode()
    }
}
