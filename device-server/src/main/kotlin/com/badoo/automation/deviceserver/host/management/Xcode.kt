package com.badoo.automation.deviceserver.host.management

import java.io.File

data class Xcode(val version: XcodeVersion, val developerDir: File) {
    companion object {
        const val DEVELOPER_DIR = "DEVELOPER_DIR"
        const val CONTENTS_FOLDER = "Contents/Developer"
    }
}