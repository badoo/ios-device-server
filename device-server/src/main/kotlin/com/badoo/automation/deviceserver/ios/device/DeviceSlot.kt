package com.badoo.automation.deviceserver.ios.device

class DeviceSlot(val device: Device) {
    private var reserved = false

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