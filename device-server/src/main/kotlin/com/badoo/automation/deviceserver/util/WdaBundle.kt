package com.badoo.automation.deviceserver.util

import java.io.File

interface WdaBundle {
    val bundleId: String
    fun xctestRunnerPath(isLocalhost: Boolean): File
    fun bundlePath(isLocalhost: Boolean): File
}