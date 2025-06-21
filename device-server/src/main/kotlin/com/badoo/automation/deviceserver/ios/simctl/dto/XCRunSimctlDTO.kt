package com.badoo.automation.deviceserver.ios.simctl.dto

data class Device(
    val dataPath: String,
    val logPath: String,
    val udid: String,
    val isAvailable: Boolean,
    val deviceTypeIdentifier: String,
    val state: String,
    val name: String
)
