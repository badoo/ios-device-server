package com.badoo.automation.deviceserver.util

import java.io.File

interface WdaBundle {
    val bundleId: String
    fun xctestRunnerPath(): File
    fun bundlePath(): File
    val provisionedDevices: List<String>
    val deviceInstrumentationPort: Int
    val testIdentifier: String
    val bundleName: String
}