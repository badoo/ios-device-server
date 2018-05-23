package com.badoo.automation.deviceserver.ios.device

class DeviceSlot(val device: Device) {
    private var reserved = false

    val udid get() = device.udid

    fun reserve() {
        reserved = true
    }

    fun release() {
        reserved = false
    }

    fun isReserved(): Boolean {
        return reserved
    }
}